#!/usr/bin/env python3
"""Euríloco — el daemon que guarda la memoria de Mástil.

Etapa 1 (espejo): recibe los eventos que Mástil registra en el teléfono
(qué apareció en X, cuánto tiempo te detuviste, las señales de cada post y
una miniatura de los posts que te detuvieron), los guarda y los muestra en
un panel donde podés etiquetar cuentas. Si en esta máquina corre Ollama con
un modelo de visión, Euríloco describe cada miniatura con palabras, de a poco.
Todavía no hay cuerdas activas.

Solo usa la biblioteca estándar de Python. Si está instalado el paquete
`qrcode`, el panel muestra el código QR de emparejamiento; si no, muestra
el enlace para copiar a mano.
"""

from __future__ import annotations

import argparse
import base64
import re
import urllib.error
import urllib.request
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

import veda as veda_mod

VERSION = "0.4.0"
PUERTO_POR_DEFECTO = 8737
MAX_CUERPO = 5 * 1024 * 1024  # 5 MB por lote de eventos
MAX_TEXTO = 1000              # caracteres guardados por post
MAX_CAPTURA = 2 * 1024 * 1024 # 2 MB por miniatura
ID_VALIDO = re.compile(r"^[0-9a-fA-F-]{8,64}$")

# Etiquetas que ofrece el panel para clasificar cuentas; se pueden agregar otras.
ETIQUETAS_BASE = ["anzuelo", "morbo", "violencia", "indignación", "humor", "política",
                  "noticias", "ciencia", "arte", "cultura", "deporte", "publicidad", "valioso"]

# Acciones que Mástil reconoce y su peso aproximado en el algoritmo de X, según el código
# que X publicó en 2023 (pueden haber cambiado). Lo negativo resta: es la señal que más corrige.
PESOS_ACCIONES = {
    "me gusta": 0.5, "quitar me gusta": -0.5, "repostear": 1.0, "deshacer repost": -1.0,
    "responder": 13.5, "entrar al post": 11.0, "abrir perfil": 12.0, "guardar": 1.0,
    "quitar guardado": -1.0, "compartir": 1.0, "activar sonido": 0.3, "ver traducción": 0.3,
    "seguir": 4.0, "dejar de seguir": -4.0, "no me interesa": -74.0, "silenciar": -74.0,
    "dejar de silenciar cuenta": 0.0, "bloquear": -74.0, "denunciar": -369.0,
}

VISION_POR_DEFECTO = {
    "activa": True,
    "url": "http://127.0.0.1:11434",
    "modelo": "moondream",
    "pausa_seg": 120,
    "prompt": ("Describí en una sola oración, en castellano, qué muestra esta captura de un post "
               "de una red social: quiénes aparecen, qué pasa y en qué tono. Sin rodeos."),
}

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
    if "vision" not in config:
        config["vision"] = dict(VISION_POR_DEFECTO)
        cambio = True
    if "capturas_dias" not in config:
        config["capturas_dias"] = 60  # cuánto se guardan las miniaturas en la Raspberry
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
    medio         TEXT,
    vistas        INTEGER,
    verificada    INTEGER NOT NULL DEFAULT 0,
    traducido     INTEGER NOT NULL DEFAULT 0,
    pestana       TEXT,
    captura       INTEGER NOT NULL DEFAULT 0,
    descripcion   TEXT,
    cuerda        TEXT,
    intervencion  TEXT,
    respuesta     TEXT
);
CREATE INDEX IF NOT EXISTS eventos_inicio ON eventos(inicio_ms);
CREATE INDEX IF NOT EXISTS eventos_cuenta ON eventos(cuenta);
CREATE TABLE IF NOT EXISTS acciones (
    id          TEXT PRIMARY KEY,
    dispositivo TEXT,
    momento_ms  INTEGER NOT NULL,
    accion      TEXT NOT NULL,
    cuenta      TEXT,
    clave       TEXT,
    texto       TEXT,
    pestana     TEXT,
    duracion_ms INTEGER,
    recibido_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS acciones_momento ON acciones(momento_ms);
CREATE INDEX IF NOT EXISTS acciones_clave ON acciones(clave);
CREATE TABLE IF NOT EXISTS etiquetas (
    cuenta      TEXT PRIMARY KEY,
    etiqueta    TEXT NOT NULL,
    momento_ms  INTEGER NOT NULL
);
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
        nuevas = [("anuncio", "INTEGER NOT NULL DEFAULT 0"), ("medio", "TEXT"), ("vistas", "INTEGER"),
                  ("verificada", "INTEGER NOT NULL DEFAULT 0"), ("traducido", "INTEGER NOT NULL DEFAULT 0"),
                  ("pestana", "TEXT"), ("captura", "INTEGER NOT NULL DEFAULT 0"), ("descripcion", "TEXT"),
                  ("cuerda", "TEXT"), ("intervencion", "TEXT"), ("respuesta", "TEXT")]
        for nombre, tipo in nuevas:
            if nombre not in columnas:
                self.con.execute(f"ALTER TABLE eventos ADD COLUMN {nombre} {tipo}")
        self.con.execute("CREATE INDEX IF NOT EXISTS eventos_pendientes ON eventos(captura, descripcion)")
        self.con.commit()

    def guardar_lote(self, dispositivo: str, eventos: list) -> tuple[int, int]:
        ahora = int(time.time() * 1000)
        filas = []
        acciones = []
        for e in eventos:
            if isinstance(e, dict) and e.get("tipo") == "accion":
                a = validar_accion(e)
                if a:
                    acciones.append((a["id"], dispositivo, a["momento"], a["accion"], a["cuenta"], a["clave"],
                                     a["texto"], a["pestana"], a["duracion_ms"], ahora))
                continue
            fila = validar_evento(e)
            if fila:
                filas.append((fila["id"], dispositivo, fila["red"], fila["inicio"], fila["fin"],
                              fila["permanencia_ms"], fila["cuenta"], fila["texto"],
                              1 if fila["tiene_imagen"] else 0, fila["clave"], ahora,
                              1 if fila["anuncio"] else 0, fila["medio"], fila["vistas"],
                              1 if fila["verificada"] else 0, 1 if fila["traducido"] else 0, fila["pestana"],
                              fila["cuerda"], fila["intervencion"], fila["respuesta"]))
        with self.candado:
            antes = self.con.total_changes
            self.con.executemany(
                "INSERT OR IGNORE INTO eventos (id, dispositivo, red, inicio_ms, fin_ms, permanencia_ms,"
                " cuenta, texto, tiene_imagen, clave, recibido_ms, anuncio, medio, vistas, verificada,"
                " traducido, pestana, cuerda, intervencion, respuesta)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", filas)
            self.con.executemany("INSERT OR IGNORE INTO acciones VALUES (?,?,?,?,?,?,?,?,?,?)", acciones)
            nuevos = self.con.total_changes - antes
            self.con.execute("INSERT INTO sincros VALUES (?,?,?,?)", (ahora, dispositivo, len(eventos), nuevos))
            self.con.commit()
        return len(eventos), nuevos

    def ejecutar(self, sql: str, params=()) -> int:
        with self.candado:
            cursor = self.con.execute(sql, params)
            self.con.commit()
            return cursor.rowcount

    def consulta(self, sql: str, params=()) -> list[dict]:
        with self.candado:
            return [dict(f) for f in self.con.execute(sql, params).fetchall()]


def validar_accion(e: dict) -> dict | None:
    """Una acción tuya en X (me gusta, entrar al post…). Solo se aceptan las que Mástil conoce."""
    try:
        id_ = str(e["id"])[:64]
        momento = int(e["momento"])
        accion = str(e["accion"])[:40]
    except (KeyError, TypeError, ValueError):
        return None
    if not id_ or accion not in PESOS_ACCIONES:
        return None
    def corto(campo, largo):
        v = e.get(campo)
        return str(v)[:largo] if v else None
    try:
        duracion = int(e["duracion_ms"]) if e.get("duracion_ms") is not None else None
    except (TypeError, ValueError):
        duracion = None
    return {"id": id_, "momento": momento, "accion": accion, "cuenta": corto("cuenta", 64),
            "clave": corto("clave", 64), "texto": corto("texto", MAX_TEXTO), "pestana": corto("pestana", 32),
            "duracion_ms": duracion}


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
    try:
        vistas = int(e["vistas"]) if e.get("vistas") is not None else None
    except (TypeError, ValueError):
        vistas = None
    pestana = e.get("pestana")
    pestana = str(pestana)[:32] if pestana else None
    cuerda = str(e["cuerda"])[:40] if e.get("cuerda") else None
    intervencion = e.get("intervencion") if e.get("intervencion") in ("aviso", "salto") else None
    respuesta = e.get("respuesta") if e.get("respuesta") in ("paso", "quedo") else None
    return {"anuncio": bool(e.get("anuncio")), "medio": medio, "vistas": vistas,
            "verificada": bool(e.get("verificada")), "traducido": bool(e.get("traducido")), "pestana": pestana,
            "cuerda": cuerda, "intervencion": intervencion, "respuesta": respuesta,
            "id": id_, "red": red, "inicio": inicio, "fin": fin, "permanencia_ms": permanencia,
            "cuenta": cuenta, "texto": texto, "tiene_imagen": bool(e.get("tiene_imagen")), "clave": clave}


# ---------------------------------------------------------------- datos del espejo

def datos_espejo(memoria: Memoria, dias: int = 30, umbral_ms: int = 3000, zona_seg: int = None,
                 limite: int = 60) -> dict:
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
        "SELECT e.cuenta, SUM(e.permanencia_ms) AS total_ms, COUNT(*) AS posts, t.etiqueta "
        "FROM eventos e LEFT JOIN etiquetas t ON t.cuenta = e.cuenta "
        "WHERE e.inicio_ms >= ? AND e.cuenta IS NOT NULL "
        "GROUP BY e.cuenta ORDER BY total_ms DESC LIMIT 12", (desde,))
    detenciones = memoria.consulta(
        "SELECT e.id, e.clave, e.inicio_ms, e.permanencia_ms, e.cuenta, e.texto, e.tiene_imagen, e.anuncio, e.medio,"
        " e.vistas, e.verificada, e.traducido, e.pestana, e.captura, e.descripcion, t.etiqueta "
        "FROM eventos e LEFT JOIN etiquetas t ON t.cuenta = e.cuenta "
        "WHERE e.permanencia_ms >= ? AND e.inicio_ms >= ? ORDER BY e.inicio_ms DESC LIMIT ?",
        (umbral_ms, desde, limite))
    por_etiqueta = memoria.consulta(
        "SELECT COALESCE(t.etiqueta, CASE WHEN e.anuncio = 1 THEN 'publicidad' ELSE 'sin etiqueta' END) AS etiqueta,"
        " SUM(e.permanencia_ms) AS total_ms, COUNT(*) AS posts "
        "FROM eventos e LEFT JOIN etiquetas t ON t.cuenta = e.cuenta "
        "WHERE e.inicio_ms >= ? GROUP BY 1 ORDER BY total_ms DESC", (desde,))
    por_pestana = memoria.consulta(
        "SELECT COALESCE(pestana, 'sin dato') AS pestana, SUM(permanencia_ms) AS total_ms, COUNT(*) AS posts "
        "FROM eventos WHERE inicio_ms >= ? GROUP BY 1 ORDER BY total_ms DESC", (desde,))
    # Acciones: las de cada post de la lista, y los totales del período.
    claves = sorted({e["clave"] for e in detenciones if e.get("clave")})
    por_clave: dict[str, list[str]] = {}
    if claves:
        marcas = ",".join("?" * len(claves))
        for f in memoria.consulta(f"SELECT DISTINCT clave, accion FROM acciones WHERE clave IN ({marcas})", claves):
            por_clave.setdefault(f["clave"], []).append(f["accion"])
    for e in detenciones:
        e["acciones"] = sorted(por_clave.get(e.get("clave"), []))
    acciones_periodo = memoria.consulta(
        "SELECT a.accion, COUNT(*) AS veces, COALESCE(t.etiqueta, 'sin etiqueta') AS etiqueta "
        "FROM acciones a LEFT JOIN etiquetas t ON t.cuenta = a.cuenta "
        "WHERE a.momento_ms >= ? GROUP BY a.accion, 3", (desde,))
    ultimas_acciones = memoria.consulta(
        "SELECT a.momento_ms, a.accion, a.cuenta, a.texto, a.pestana, a.duracion_ms, t.etiqueta "
        "FROM acciones a LEFT JOIN etiquetas t ON t.cuenta = a.cuenta "
        "WHERE a.momento_ms >= ? ORDER BY a.momento_ms DESC LIMIT 40", (desde,))
    en_uso = [f["etiqueta"] for f in memoria.consulta("SELECT DISTINCT etiqueta FROM etiquetas ORDER BY etiqueta")]
    etiquetas = ETIQUETAS_BASE + [x for x in en_uso if x not in ETIQUETAS_BASE]
    pendientes = memoria.consulta(
        "SELECT COUNT(*) AS n FROM eventos WHERE captura = 1 AND descripcion IS NULL")[0]["n"]
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
            "por_etiqueta": por_etiqueta, "por_pestana": por_pestana, "etiquetas": etiquetas,
            "descripciones_pendientes": pendientes, "acciones_periodo": acciones_periodo,
            "ultimas_acciones": ultimas_acciones, "pesos": PESOS_ACCIONES,
            "ultima_sincro": ultima[0] if ultima else None, "eventos_total": total}


# ---------------------------------------------------------------- veda

def datos_veda(memoria: Memoria, veda: "veda_mod.Veda", dias: int = 7) -> dict:
    """El menú de no deseados con el peso real de cada subcategoría y cómo vienen funcionando."""
    desde = int((time.time() - dias * 86400) * 1000)
    datos = veda.leer()
    eventos = memoria.consulta(
        "SELECT texto, cuenta, anuncio, permanencia_ms FROM eventos WHERE inicio_ms >= ?", (desde,))
    medidas = veda_mod.medir(eventos, veda_mod.subcategorias_planas(datos))
    efecto = {f["cuerda"]: f for f in memoria.consulta(
        "SELECT cuerda, SUM(intervencion = 'aviso') AS avisos, SUM(intervencion = 'salto') AS saltos,"
        " SUM(respuesta = 'paso') AS pasaste, SUM(respuesta = 'quedo') AS quedaste "
        "FROM eventos WHERE inicio_ms >= ? AND cuerda IS NOT NULL GROUP BY cuerda", (desde,))}
    total_ms = sum(e["permanencia_ms"] for e in eventos)
    for cat in datos["categorias"]:
        for sub in cat["subcategorias"]:
            sub["peso"] = medidas["subcategorias"].get(sub["id"], {"posts": 0, "ms": 0, "detenciones": 0})
            sub["efecto"] = efecto.get(sub["id"])
        cat["peso_ms"] = medidas["categorias"].get(cat["id"], {"ms": 0})["ms"]
    datos["sin_categoria"] = medidas["sin_categoria"]
    datos["total"] = {"posts": len(eventos), "ms": total_ms}
    datos["dias"] = dias
    datos["version"] = veda.version()
    return datos


# ---------------------------------------------------------------- miniaturas y descripción

def ruta_captura(dir_capturas: Path, id_: str) -> Path | None:
    if not ID_VALIDO.match(id_ or ""):
        return None
    return dir_capturas / f"{id_}.jpg"


def guardar_captura(memoria: Memoria, dir_capturas: Path, id_: str, datos: bytes) -> bool:
    """Guarda una miniatura JPEG y la vincula a su evento. Rechaza lo que no sea JPEG."""
    ruta = ruta_captura(dir_capturas, id_)
    if ruta is None or not datos.startswith(b"\xff\xd8"):
        return False
    dir_capturas.mkdir(parents=True, exist_ok=True)
    temporal = ruta.with_suffix(".tmp")
    temporal.write_bytes(datos)
    temporal.replace(ruta)
    memoria.ejecutar("UPDATE eventos SET captura = 1 WHERE id = ?", (id_,))
    return True


def ollama(url: str, ruta: str, cuerpo: dict | None = None, espera: int = 10) -> dict:
    datos = json.dumps(cuerpo).encode("utf-8") if cuerpo is not None else None
    pedido = urllib.request.Request(url.rstrip("/") + ruta, data=datos,
                                    headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(pedido, timeout=espera) as r:
        return json.loads(r.read().decode("utf-8"))


def modelo_disponible(vision: dict) -> bool:
    try:
        modelos = ollama(vision["url"], "/api/tags").get("models", [])
    except (OSError, ValueError, urllib.error.URLError):
        return False
    buscado = vision["modelo"]
    return any(m.get("name") == buscado or m.get("name", "").split(":")[0] == buscado for m in modelos)


def describir_pendientes(memoria: Memoria, dir_capturas: Path, vision: dict, tanda: int = 5) -> int:
    """Describe con el modelo de visión las miniaturas que todavía no tienen descripción."""
    pendientes = memoria.consulta(
        "SELECT id FROM eventos WHERE captura = 1 AND descripcion IS NULL "
        "ORDER BY permanencia_ms DESC LIMIT ?", (tanda,))
    hechas = 0
    for fila in pendientes:
        ruta = ruta_captura(dir_capturas, fila["id"])
        if ruta is None or not ruta.exists():
            memoria.ejecutar("UPDATE eventos SET captura = 0 WHERE id = ?", (fila["id"],))
            continue
        imagen = base64.b64encode(ruta.read_bytes()).decode("ascii")
        respuesta = ollama(vision["url"], "/api/generate", {
            "model": vision["modelo"], "prompt": vision["prompt"], "images": [imagen],
            "stream": False, "options": {"num_predict": 90, "temperature": 0.2},
        }, espera=900)
        texto = " ".join((respuesta.get("response") or "").split())[:500]
        memoria.ejecutar("UPDATE eventos SET descripcion = ? WHERE id = ?", (texto or "(sin descripción)", fila["id"]))
        hechas += 1
    return hechas


def podar_capturas(memoria: Memoria, dir_capturas: Path, dias: int) -> int:
    """Borra las miniaturas más viejas que el plazo configurado; los eventos y descripciones quedan."""
    if not dir_capturas.exists():
        return 0
    limite = time.time() - dias * 86400
    borradas = 0
    for f in dir_capturas.glob("*.jpg"):
        if f.stat().st_mtime < limite:
            f.unlink(missing_ok=True)
            memoria.ejecutar("UPDATE eventos SET captura = 0 WHERE id = ?", (f.stem,))
            borradas += 1
    return borradas


def bucle_vision(memoria: Memoria, dir_capturas: Path, config: dict, parar: threading.Event) -> None:
    """Hilo de fondo: describe miniaturas de a poco y poda las viejas. Si no hay Ollama, solo poda."""
    vision = {**VISION_POR_DEFECTO, **config.get("vision", {})}
    ultima_poda = 0.0
    while not parar.is_set():
        try:
            if time.time() - ultima_poda > 6 * 3600:
                podar_capturas(memoria, dir_capturas, int(config.get("capturas_dias", 60)))
                ultima_poda = time.time()
            if vision.get("activa") and modelo_disponible(vision):
                while not parar.is_set() and describir_pendientes(memoria, dir_capturas, vision):
                    pass
        except Exception as e:  # el hilo nunca debe morir por un error puntual
            sys.stderr.write(f"visión: {e}\n")
        parar.wait(int(vision.get("pausa_seg", 120)))


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
    dir_capturas: Path = DIR_DATOS / "capturas"
    veda: "veda_mod.Veda" = None
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

    def _leer_cuerpo(self, maximo: int) -> bytes | None:
        try:
            largo = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if largo <= 0 or largo > maximo:
            return None
        return self.rfile.read(largo)

    # --- rutas
    def do_GET(self):
        url = urlparse(self.path)
        ruta = url.path
        if ruta == "/api/estado":
            if not self._autorizado():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
            total = self.memoria.consulta("SELECT COUNT(*) AS n FROM eventos")[0]["n"]
            return self._json(HTTPStatus.OK, {"version": VERSION, "eventos_total": total,
                                              "cuerdas_version": self.veda.version()})
        if ruta == "/api/cuerdas":
            if not self._autorizado():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
            cuerdas = self.veda.para_mastil()
            # «toml» queda vacío para las versiones viejas de Mástil, que lo esperaban.
            return self._json(HTTPStatus.OK, {"version": cuerdas["version"], "toml": "", "cuerdas": cuerdas})
        if ruta == "/panel/veda":
            if not self._panel_permitido():
                return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
            try:
                dias = max(1, min(365, int(parse_qs(url.query).get("dias", ["7"])[0])))
            except ValueError:
                dias = 7
            return self._json(HTTPStatus.OK, datos_veda(self.memoria, self.veda, dias))
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
                limite = max(10, min(500, int(q.get("limite", ["60"])[0])))
            except ValueError:
                dias, umbral, limite = 30, 3000, 60
            return self._json(HTTPStatus.OK, datos_espejo(self.memoria, dias, umbral, limite=limite))
        if ruta.startswith("/panel/captura/") and ruta.endswith(".jpg"):
            if not self._panel_permitido():
                return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
            archivo = ruta_captura(self.dir_capturas, ruta[len("/panel/captura/"):-4])
            if archivo is None or not archivo.exists():
                return self._json(HTTPStatus.NOT_FOUND, {"error": "sin miniatura"})
            cuerpo = archivo.read_bytes()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(cuerpo)))
            self.send_header("Cache-Control", "private, max-age=86400")
            self.end_headers()
            self.wfile.write(cuerpo)
            return None
        if ruta == "/panel/emparejar":
            if not self._panel_permitido():
                return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
            enlace = enlace_emparejar(self.config)
            return self._json(HTTPStatus.OK, {"enlace": enlace, "qr_svg": qr_svg(enlace)})
        return self._json(HTTPStatus.NOT_FOUND, {"error": "no existe"})

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/panel/etiqueta":
            return self._post_etiqueta()
        if url.path.startswith("/panel/veda/"):
            return self._post_veda(url.path[len("/panel/veda/"):])
        if url.path == "/api/captura":
            if not self._autorizado():
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
            datos = self._leer_cuerpo(MAX_CAPTURA)
            if datos is None:
                return self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "tamaño inválido"})
            id_ = parse_qs(url.query).get("id", [""])[0]
            if not guardar_captura(self.memoria, self.dir_capturas, id_, datos):
                return self._json(HTTPStatus.BAD_REQUEST, {"error": "miniatura inválida"})
            return self._json(HTTPStatus.OK, {"guardada": id_})
        if url.path != "/api/eventos":
            return self._json(HTTPStatus.NOT_FOUND, {"error": "no existe"})
        if not self._autorizado():
            return self._json(HTTPStatus.UNAUTHORIZED, {"error": "clave inválida"})
        cuerpo = self._leer_cuerpo(MAX_CUERPO)
        if cuerpo is None:
            return self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "tamaño inválido"})
        try:
            datos = json.loads(cuerpo.decode("utf-8"))
            eventos = datos["eventos"]
            if not isinstance(eventos, list):
                raise ValueError
        except (ValueError, KeyError, TypeError, UnicodeDecodeError):
            return self._json(HTTPStatus.BAD_REQUEST, {"error": "formato inválido"})
        dispositivo = str(datos.get("dispositivo", ""))[:64]
        recibidos, nuevos = self.memoria.guardar_lote(dispositivo, eventos)
        return self._json(HTTPStatus.OK, {"recibidos": recibidos, "nuevos": nuevos})

    def _post_veda(self, accion: str):
        """Cambios del menú de no deseados, solo desde el panel local."""
        if not self._panel_permitido():
            return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
        cuerpo = self._leer_cuerpo(200_000)
        try:
            datos = json.loads(cuerpo.decode("utf-8")) if cuerpo else {}
            if accion == "subcategoria":
                r = self.veda.cambiar(str(datos["categoria"]), datos["subcategoria"], motivo=str(datos.get("motivo", "")))
            elif accion == "eliminar":
                r = self.veda.cambiar(str(datos["categoria"]), None, sub_id=str(datos["id"]),
                                      motivo=str(datos.get("motivo", "")))
            elif accion == "pendiente":
                r = self.veda.resolver_pendiente(str(datos["id"]), datos.get("accion") == "confirmar")
            elif accion == "avisos":
                r = self.veda.cambiar_avisos(list(datos.get("formas", [])), float(datos.get("intensidad", 0.5)))
            elif accion == "simular":
                desde = int((time.time() - int(datos.get("dias", 7)) * 86400) * 1000)
                eventos = self.memoria.consulta(
                    "SELECT texto, cuenta, anuncio, permanencia_ms FROM eventos WHERE inicio_ms >= ?", (desde,))
                r = veda_mod.simular(eventos, datos["subcategoria"])
            else:
                return self._json(HTTPStatus.NOT_FOUND, {"error": "no existe"})
        except (ValueError, KeyError, TypeError, UnicodeDecodeError) as e:
            return self._json(HTTPStatus.BAD_REQUEST, {"error": str(e) or "formato inválido"})
        return self._json(HTTPStatus.OK, r)

    def _post_etiqueta(self):
        """Etiqueta (o desetiqueta, con etiqueta vacía) una cuenta. Solo desde el panel local."""
        if not self._panel_permitido():
            return self._json(HTTPStatus.FORBIDDEN, {"error": "solo local"})
        cuerpo = self._leer_cuerpo(10_000)
        try:
            datos = json.loads(cuerpo.decode("utf-8")) if cuerpo else {}
            cuenta = str(datos["cuenta"]).strip()[:64]
            etiqueta = " ".join(str(datos.get("etiqueta", "")).split()).lower()[:40]
        except (ValueError, KeyError, TypeError, UnicodeDecodeError):
            return self._json(HTTPStatus.BAD_REQUEST, {"error": "formato inválido"})
        if not cuenta.startswith("@"):
            return self._json(HTTPStatus.BAD_REQUEST, {"error": "cuenta inválida"})
        if etiqueta:
            self.memoria.ejecutar("INSERT INTO etiquetas VALUES (?,?,?) ON CONFLICT(cuenta) DO UPDATE SET "
                                  "etiqueta = excluded.etiqueta, momento_ms = excluded.momento_ms",
                                  (cuenta, etiqueta, int(time.time() * 1000)))
        else:
            self.memoria.ejecutar("DELETE FROM etiquetas WHERE cuenta = ?", (cuenta,))
        return self._json(HTTPStatus.OK, {"cuenta": cuenta, "etiqueta": etiqueta or None})


def crear_servidor(config: dict, dir_config: Path, dir_datos: Path, host: str = "0.0.0.0",
                   puerto: int = None) -> ThreadingHTTPServer:
    panel = (Path(__file__).with_name("panel.html")).read_text(encoding="utf-8")
    atributos = {"config": config, "memoria": Memoria(dir_datos / "euriloco.db"),
                 "dir_config": dir_config, "dir_capturas": dir_datos / "capturas", "panel_html": panel,
                 "veda": veda_mod.Veda(dir_config, Path(__file__).with_name("veda_inicial.json"))}
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
    manejador = servidor.RequestHandlerClass
    parar = threading.Event()
    threading.Thread(target=bucle_vision, args=(manejador.memoria, manejador.dir_capturas, config, parar),
                     daemon=True, name="vision").start()
    print(f"Euríloco {VERSION} escuchando en el puerto {config['puerto']}.")
    print(f"Panel: http://localhost:{config['puerto']}/")
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        pass
    parar.set()
    return 0


if __name__ == "__main__":
    sys.exit(main())
