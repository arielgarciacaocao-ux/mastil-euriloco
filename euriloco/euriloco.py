#!/usr/bin/env python3
"""Euríloco — el daemon que guarda la memoria de Mástil.

Etapa 1 (espejo): recibe los eventos que Mástil registra en el teléfono
(qué apareció en X, cuánto tiempo te detuviste), los guarda para siempre
y los muestra en un panel con gráficos. Todavía no hay cuerdas activas.

Solo usa la biblioteca estándar de Python. Si está instalado el paquete
`qrcode`, el panel muestra el código QR de emparejamiento; si no, muestra
el enlace para copiar a mano.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

VERSION = "0.1.1"
PUERTO_POR_DEFECTO = 8737
MAX_CUERPO = 5 * 1024 * 1024  # 5 MB por lote de eventos
MAX_TEXTO = 1000              # caracteres guardados por post

DIR_CONFIG = Path(os.environ.get("EURILOCO_CONFIG", Path.home() / ".config" / "euriloco"))
DIR_DATOS = Path(os.environ.get("EURILOCO_DATOS", Path.home() / ".local" / "share" / "euriloco"))

CUERDAS_INICIALES = """\
# Cuerdas de Mástil — reglas que decidís en modo consciente.
#
# Etapa 1 (espejo): las cuerdas todavía no actúan. Mástil solo observa
# y registra. Este archivo se completa en la etapa 2.
#
# Ejemplo de cómo se verá una cuerda:
#
# [[cuerda]]
# nombre = "Sin violencia gráfica"
# tipo = "cera"
# condicion = { temas = ["violencia gráfica"] }
# accion = "salto"
# aviso = "ambos"
# redes = "todas"
"""


# ---------------------------------------------------------------- configuración

def cargar_config(dir_config: Path = None) -> dict:
    """Lee (o crea) la configuración. La clave de emparejamiento se genera una sola vez."""
    dir_config = dir_config or DIR_CONFIG
    dir_config.mkdir(parents=True, exist_ok=True)
    ruta = dir_config / "config.json"
    config = {}
    if ruta.exists():
        config = json.loads(ruta.read_text(encoding="utf-8"))
    cambio = False
    if "clave" not in config:
        config["clave"] = secrets.token_urlsafe(24)
        cambio = True
    if "puerto" not in config:
        config["puerto"] = PUERTO_POR_DEFECTO
        cambio = True
    if "panel_solo_local" not in config:
        # El panel muestra la clave en el QR: por defecto, solo desde la propia Raspberry.
        config["panel_solo_local"] = True
        cambio = True
    if cambio:
        ruta.write_text(json.dumps(config, indent=2, ensure_ascii=False), encoding="utf-8")
        os.chmod(ruta, 0o600)
    cuerdas = dir_config / "cuerdas.toml"
    if not cuerdas.exists():
        cuerdas.write_text(CUERDAS_INICIALES, encoding="utf-8")
    return config


# ---------------------------------------------------------------- base de datos

ESQUEMA = """
CREATE TABLE IF NOT EXISTS eventos (
    id            TEXT PRIMARY KEY,
    dispositivo   TEXT,
    red           TEXT NOT NULL,
    inicio_ms     INTEGER NOT NULL,
    fin_ms        INTEGER NOT NULL,
    permanencia_ms INTEGER NOT NULL,
    cuenta        TEXT,
    texto         TEXT,
    tiene_imagen  INTEGER NOT NULL DEFAULT 0,
    clave         TEXT,
    recibido_ms   INTEGER NOT NULL,
    anuncio       INTEGER NOT NULL DEFAULT 0,
    medio         TEXT
);
CREATE INDEX IF NOT EXISTS eventos_inicio ON eventos(inicio_ms);
CREATE INDEX IF NOT EXISTS eventos_cuenta ON eventos(cuenta);
CREATE TABLE IF NOT EXISTS sincros (
    momento_ms  INTEGER NOT NULL,
    dispositivo TEXT,
    recibidos   INTEGER,
    nuevos      INTEGER
);
"""


class Memoria:
    """La memoria histórica: SQLite, una sola conexión protegida por un candado."""

    def __init__(self, ruta: Path):
        ruta.parent.mkdir(parents=True, exist_ok=True)
        self.con = sqlite3.connect(str(ruta), check_same_thread=False)
        self.con.row_factory = sqlite3.Row
        self.con.executescript(ESQUEMA)
        self._migrar()
        self.candado = threading.Lock()

    def _migrar(self) -> None:
        """Agrega las columnas nuevas a una base creada por una versión anterior, sin tocar los datos."""
        columnas = {f[1] for f in self.con.execute("PRAGMA table_info(eventos)")}
        if "anuncio" not in columnas:
            self.con.execute("ALTER TABLE eventos ADD COLUMN anuncio INTEGER NOT NULL DEFAULT 0")
        if "medio" not in columnas:
            self.con.execute("ALTER TABLE eventos ADD COLUMN medio TEXT")
        self.con.commit()

    def guardar_lote(self, dispositivo: str, eventos: list) -> tuple[int, int]:
        ahora = int(time.time() * 1000)
        filas = []
        for e in eventos:
            fila = validar_evento(e)
            if fila:
                filas.append((fila["id"], dispositivo, fila["red"], fila["inicio"], fila["fin"],
                              fila["permanencia_ms"], fila["cuenta"], fila["texto"],
                              1 if fila["tiene_imagen"] else 0, fila["clave"], ahora,
                              1 if fila["anuncio"] else 0, fila["medio"]))
        with self.candado:
            antes = self.con.total_changes
            self.con.executemany(
                "INSERT OR IGNORE INTO eventos (id, dispositivo, red, inicio_ms, fin_ms, permanencia_ms,"
                " cuenta, texto, tiene_imagen, clave, recibido_ms, anuncio, medio)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", filas)
            nuevos = self.con.total_changes - antes
            self.con.execute("INSERT INTO sincros VALUES (?,?,?,?)", (ahora, dispositivo, len(eventos), nuevos))
            self.con.commit()
        return len(eventos), nuevos

    def consulta(self, sql: str, params=()) -> list[dict]:
        with self.candado:
            return [dict(f) for f in self.con.execute(sql, params).fetchall()]


def validar_evento(e) -> dict | None:
    """Acepta solo eventos bien formados; recorta lo que sobra. Devuelve None si no sirve."""
    if not isinstance(e, dict):
        return None
    try:
        id_ = str(e["id"])[:64]
        red = str(e.get("red", "x"))[:16]
        inicio = int(e["inicio"])
        fin = int(e["fin"])
        permanencia = int(e.get("permanencia_ms", fin - inicio))
    except (KeyError, TypeError, ValueError):
        return None
    if not id_ or fin < inicio or permanencia < 0 or permanencia > 24 * 3600 * 1000:
        return None
    cuenta = e.get("cuenta")
    cuenta = str(cuenta)[:64] if cuenta else None
    texto = e.get("texto")
    texto = str(texto)[:MAX_TEXTO] if texto else None
    clave = e.get("clave")
    clave = str(clave)[:64] if clave else None
    medio = e.get("medio")
    medio = str(medio)[:16] if medio else None
    return {"anuncio": bool(e.get("anuncio")), "medio": medio,
            "id": id_, "red": red, "inicio": inicio, "fin": fin, "permanencia_ms": permanencia,
            "cuenta": cuenta, "texto": texto, "tiene_imagen": bool(e.get("tiene_imagen")), "clave": clave}


# ---------------------------------------------------------------- datos del espejo

def datos_espejo(memoria: Memoria, dias: int = 30, umbral_ms: int = 3000, zona_seg: int = None) -> dict:
    """Todo lo que el panel necesita para dibujar el espejo."""
    if zona_seg is None:
        zona_seg = -time.localtime().tm_gmtoff  # para agrupar por día local
    desde = int((time.time() - dias * 86400) * 1000)
    por_dia = memoria.consulta(
        "SELECT date((inicio_ms/1000) - ?, 'unixepoch') AS dia, "
        " SUM(permanencia_ms) AS total_ms, COUNT(*) AS posts, "
        " SUM(CASE WHEN permanencia_ms >= ? THEN 1 ELSE 0 END) AS detenciones, "
        " SUM(CASE WHEN permanencia_ms >= ? THEN permanencia_ms ELSE 0 END) AS detenciones_ms "
        "FROM eventos WHERE inicio_ms >= ? GROUP BY dia ORDER BY dia",
        (zona_seg, umbral_ms, umbral_ms, desde))
    cuentas = memoria.consulta(
        "SELECT cuenta, SUM(permanencia_ms) AS total_ms, COUNT(*) AS posts "
        "FROM eventos WHERE inicio_ms >= ? AND cuenta IS NOT NULL "
        "GROUP BY cuenta ORDER BY total_ms DESC LIMIT 12", (desde,))
    detenciones = memoria.consulta(
        "SELECT inicio_ms, permanencia_ms, cuenta, texto, tiene_imagen, anuncio, medio FROM eventos "
        "WHERE permanencia_ms >= ? ORDER BY inicio_ms DESC LIMIT 40", (umbral_ms,))
    tramos = memoria.consulta(
        "SELECT CASE WHEN permanencia_ms < 1000 THEN 0 WHEN permanencia_ms < 2000 THEN 1 "
        " WHEN permanencia_ms < 3000 THEN 2 WHEN permanencia_ms < 5000 THEN 3 "
        " WHEN permanencia_ms < 10000 THEN 4 ELSE 5 END AS tramo, COUNT(*) AS posts "
        "FROM eventos WHERE inicio_ms >= ? GROUP BY tramo ORDER BY tramo", (desde,))
    anuncios = memoria.consulta(
        "SELECT COUNT(*) AS posts, COALESCE(SUM(permanencia_ms), 0) AS total_ms "
        "FROM eventos WHERE inicio_ms >= ? AND anuncio = 1", (desde,))[0]
    ultima = memoria.consulta("SELECT momento_ms, recibidos, nuevos FROM sincros ORDER BY momento_ms DESC LIMIT 1")
    total = memoria.consulta("SELECT COUNT(*) AS n FROM eventos")[0]["n"]
    return {"dias": dias, "umbral_ms": umbral_ms, "por_dia": por_dia, "cuentas": cuentas,
            "detenciones": detenciones, "tramos": tramos, "anuncios": anuncios,
            "ultima_sincro": ultima[0] if ultima else None, "eventos_total": total}


# ---------------------------------------------------------------- emparejamiento

def direcciones_locales() -> list[str]:
    """Direcciones de esta máquina que el teléfono podría usar: red de casa y Tailscale."""
    ips = []
    try:
        salida = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=3).stdout
        ips = salida.split()
    except (OSError, subprocess.SubprocessError):
        pass
    if not ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("10.255.255.255", 1))
            ips = [s.getsockname()[0]]
            s.close()
        except OSError:
            ips = ["127.0.0.1"]
    utiles = []
    for ip in ips:
        try:
            dir_ = ipaddress.ip_address(ip)
        except ValueError:
            continue
        if dir_.version == 4 and not dir_.is_loopback and not dir_.is_link_local:
            utiles.append(ip)
    # Tailscale (100.64.0.0/10) al final: el teléfono prueba primero la red de casa.
    tailscale = ipaddress.ip_network("100.64.0.0/10")
    utiles.sort(key=lambda ip: ipaddress.ip_address(ip) in tailscale)
    return utiles or ["127.0.0.1"]


def enlace_emparejar(config: dict) -> str:
    hosts = ",".join(f"{ip}:{config['puerto']}" for ip in direcciones_locales())
    return f"mastil://emparejar?h={quote(hosts, safe=',:')}&t={quote(config['clave'])}"


def qr_svg(texto: str) -> str | None:
    try:
        import qrcode
        import qrcode.image.svg
    except ImportError:
        return None
    img = qrcode.make(texto, image_factory=qrcode.image.svg.SvgPathImage, box_size=8, border=2)
    return img.to_string(encoding="unicode")


def version_cuerdas(texto: str) -> str:
    return hashlib.sha256(texto.encode("utf-8")).hexdigest()[:12]


# ---------------------------------------------------------------- servidor

class Manejador(BaseHTTPRequestHandler):
    server_version = f"Euriloco/{VERSION}"
    # Atributos que asigna crear_servidor:
    config: dict = {}
    memoria: Memoria = None
    dir_config: Path = DIR_CONFIG
    panel_html: str = ""

    def log_message(self, formato, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), formato % args))

    # --- utilidades
    def _json(self, codigo: int, datos) -> None:
        cuerpo = json.dumps(datos, ensure_ascii=False).encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(cuerpo)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(cuerpo)

    def _autorizado(self) -> bool:
        cabecera = self.headers.get("Authorization", "")
        if not cabecera.startswith("Bearer "):
            return False
        return secrets.compare_digest(cabecera[7:].strip(), self.config["clave"])

    def _es_local(self) -> bool:
        try:
            return ipaddress.ip_address(self.client_address[0]).is_loopback
        except ValueError:
            return False

    def _panel_permitido(self) -> bool:
        return self._es_local() or not self.config.get("panel_solo_local", True)

    # --- rutas
    def do_GET(self):
        url = urlparse(self.path)
        ruta = url.path
        if ruta == "/api/estado":
            if not self._autorizado():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
            texto = (self.dir_config / "cuerdas.toml").read_text(encoding="utf-8")
            total = self.memoria.consulta("SELECT COUNT(*) AS n FROM eventos")[0]["n"]
            return self._json(HTTPStatus.OK, {"version": VERSION, "eventos_total": total,
                                              "cuerdas_version": version_cuerdas(texto)})
        if ruta == "/api/cuerdas":
            if not self._autorizado():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
            texto = (self.dir_config / "cuerdas.toml").read_text(encoding="utf-8")
            return self._json(HTTPStatus.OK, {"version": version_cuerdas(texto), "toml": texto})
        if ruta in ("/", "/panel"):
            if not self._panel_permitido():
                return self._json(HTTPStatus.FORBIDDEN, {"error": "el panel solo se abre desde la Raspberry"})
            cuerpo = self.panel_html.encode("utf-8")
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(cuerpo)))
            self.end_headers()
            self.wfile.write(cuerpo)
            return None
        if ruta == "/panel/datos":
            if not self._panel_permitido():
                return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
            q = parse_qs(url.query)
            try:
                dias = max(1, min(365, int(q.get("dias", ["30"])[0])))
                umbral = max(500, min(60000, int(q.get("umbral", ["3000"])[0])))
            except ValueError:
                dias, umbral = 30, 3000
            return self._json(HTTPStatus.OK, datos_espejo(self.memoria, dias, umbral))
        if ruta == "/panel/emparejar":
            if not self._panel_permitido():
                return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
            enlace = enlace_emparejar(self.config)
            return self._json(HTTPStatus.OK, {"enlace": enlace, "qr_svg": qr_svg(enlace)})
        return self._json(HTTPStatus.NOT_FOUND, {"error": "no existe"})

    def do_POST(self):
        if urlparse(self.path).path != "/api/eventos":
            return self._json(HTTPStatus.NOT_FOUND, {"error": "no existe"})
        if not self._autorizado():
            return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
        try:
            largo = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            largo = -1
        if largo <= 0 or largo > MAX_CUERPO:
            return self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "tamaño inválido"})
        try:
            datos = json.loads(self.rfile.read(largo).decode("utf-8"))
            eventos = datos["eventos"]
            if not isinstance(eventos, list):
                raise ValueError
        except (ValueError, KeyError, TypeError, UnicodeDecodeError):
            return self._json(HTTPStatus.BAD_REQUEST, {"error": "formato inválido"})
        dispositivo = str(datos.get("dispositivo", ""))[:64]
        recibidos, nuevos = self.memoria.guardar_lote(dispositivo, eventos)
        return self._json(HTTPStatus.OK, {"recibidos": recibidos, "nuevos": nuevos})


def crear_servidor(config: dict, dir_config: Path, dir_datos: Path, host: str = "0.0.0.0",
                   puerto: int = None) -> ThreadingHTTPServer:
    panel = (Path(__file__).with_name("panel.html")).read_text(encoding="utf-8")
    atributos = {"config": config, "memoria": Memoria(dir_datos / "euriloco.db"),
                 "dir_config": dir_config, "panel_html": panel}
    clase = type("ManejadorEuriloco", (Manejador,), atributos)
    return ThreadingHTTPServer((host, config["puerto"] if puerto is None else puerto), clase)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Euríloco: la memoria de Mástil.")
    p.add_argument("--host", default="0.0.0.0", help="dirección donde escuchar (por defecto, todas)")
    p.add_argument("--enlace", action="store_true", help="mostrar el enlace de emparejamiento y salir")
    p.add_argument("--nueva-clave", action="store_true",
                   help="generar una clave nueva (el teléfono tendrá que volver a emparejarse)")
    args = p.parse_args(argv)

    if args.nueva_clave:
        ruta = DIR_CONFIG / "config.json"
        if ruta.exists():
            config = json.loads(ruta.read_text(encoding="utf-8"))
            config.pop("clave", None)
            ruta.write_text(json.dumps(config, indent=2, ensure_ascii=False), encoding="utf-8")
    config = cargar_config()
    if args.enlace or args.nueva_clave:
        print(enlace_emparejar(config))
        return 0

    servidor = crear_servidor(config, DIR_CONFIG, DIR_DATOS, args.host)
    print(f"Euríloco {VERSION} escuchando en el puerto {config['puerto']}.")
    print(f"Panel: http://localhost:{config['puerto']}/")
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
