"""La veda: el menú de no deseados, organizado en categorías y subcategorías.

Cada subcategoría es una cuerda: reconoce posts por palabras, por cuenta o por ser anuncio,
y tiene una acción:
  - nada:     solo se mide (sirve para ver su peso antes de decidir);
  - aviso:    Mástil avisa con un destello o una vibración suave;
  - salto:    Mástil pasa de largo solo;
  - permitir: excepción; gana sobre cualquier otra (el desnudo artístico frente a lo sexual).

Regla del mástil: apretar es inmediato; aflojar pide un motivo escrito y espera 24 horas,
y después hay que confirmarlo (si no se confirma en 72 horas, se cancela solo).

El clasificador de este módulo es el mismo que usa Mástil en el teléfono (Cuerdas.kt):
mismas reglas de normalización y de límites de palabra, probadas con los mismos casos.
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
import secrets
import threading
import time
import unicodedata
from pathlib import Path

ACCIONES = ("nada", "aviso", "salto", "permitir")
FUERZA = {"nada": 0, "aviso": 1, "salto": 2}
ESPERA_MS = 24 * 3600 * 1000
VENCE_MS = 72 * 3600 * 1000
MOTIVO_MINIMO = 10


# ---------------------------------------------------------------- clasificador

def normalizar(texto: str | None) -> str:
    """Minúsculas y sin tildes: «Peronismo» y «peronísmo» valen lo mismo."""
    descompuesto = unicodedata.normalize("NFD", texto or "")
    return "".join(c for c in descompuesto if unicodedata.category(c) != "Mn").lower()


def _es_alnum(c: str) -> bool:
    return c.isalnum()


def coincide(texto_normalizado: str, palabra: str) -> bool:
    """
    Busca una palabra o frase respetando los bordes: «bet» no encuentra «alfabeto».
    Con * al final busca el comienzo: «sionis*» encuentra «sionismo».
    Emojis y símbolos se buscan tal cual.
    """
    p = normalizar(palabra.strip())
    prefijo = p.endswith("*")
    if prefijo:
        p = p[:-1]
    if not p:
        return False
    desde = 0
    while True:
        i = texto_normalizado.find(p, desde)
        if i < 0:
            return False
        fin = i + len(p)
        antes_ok = not _es_alnum(p[0]) or i == 0 or not _es_alnum(texto_normalizado[i - 1])
        despues_ok = (prefijo or not _es_alnum(p[-1]) or fin >= len(texto_normalizado)
                      or not _es_alnum(texto_normalizado[fin]))
        if antes_ok and despues_ok:
            return True
        desde = i + 1


def aplica(sub: dict, texto_normalizado: str, cuenta: str | None, anuncio: bool) -> bool:
    """¿Esta subcategoría reconoce este post?"""
    if sub.get("todo_anuncio"):
        return bool(anuncio)
    if sub.get("solo_anuncios") and not anuncio:
        return False
    cuentas = {c.lower() for c in sub.get("cuentas", [])}
    if cuenta and cuenta.lower() in cuentas:
        return True
    return any(coincide(texto_normalizado, p) for p in sub.get("palabras", []))


def resolver(subcategorias: list[dict], texto: str | None, cuenta: str | None, anuncio: bool) -> tuple[str | None, str]:
    """
    Decide qué hacer con un post: devuelve (id de la subcategoría que manda, acción).
    Un «permitir» que coincide gana siempre; si no, la acción más fuerte (salto > aviso > nada).
    """
    t = normalizar(texto)
    mejor_id, mejor = None, "nada"
    for sub in subcategorias:
        accion = sub.get("accion", "nada")
        if accion == "nada" or not aplica(sub, t, cuenta, anuncio):
            continue
        if accion == "permitir":
            return sub["id"], "permitir"
        if FUERZA[accion] > FUERZA[mejor]:
            mejor_id, mejor = sub["id"], accion
    return mejor_id, mejor


def subcategorias_planas(datos: dict) -> list[dict]:
    salida = []
    for cat in datos["categorias"]:
        for sub in cat["subcategorias"]:
            salida.append({**sub, "categoria": cat["id"]})
    return salida


# ---------------------------------------------------------------- almacenamiento y reglas

def _limpiar_lista(valores, largo: int = 80, maximo: int = 300) -> list[str]:
    salida = []
    for v in (valores or [])[:maximo]:
        v = " ".join(str(v).split())[:largo]
        if v and v not in salida:
            salida.append(v)
    return salida


def validar_subcategoria(sub: dict) -> dict:
    """Normaliza lo que manda el panel. Lanza ValueError si algo no tiene sentido."""
    nombre = " ".join(str(sub.get("nombre", "")).split())[:60]
    if not nombre:
        raise ValueError("la subcategoría necesita un nombre")
    accion = sub.get("accion", "nada")
    if accion not in ACCIONES:
        raise ValueError("acción desconocida")
    id_ = str(sub.get("id") or "").strip()[:40]
    if not re.fullmatch(r"[a-z0-9-]{2,40}", id_):
        id_ = "sub-" + secrets.token_hex(4)
    cuentas = [c if c.startswith("@") else "@" + c for c in _limpiar_lista(sub.get("cuentas"), 16)]
    return {"id": id_, "nombre": nombre, "accion": accion,
            "palabras": _limpiar_lista(sub.get("palabras")), "cuentas": cuentas,
            "solo_anuncios": bool(sub.get("solo_anuncios")), "todo_anuncio": bool(sub.get("todo_anuncio"))}


def es_aflojar(antes: dict | None, despues: dict | None) -> bool:
    """
    ¿El cambio suelta una cuerda? Soltar: bajar la acción, quitar palabras o cuentas a una
    veda, agregar un permiso o ampliarlo, tocar el alcance de los anuncios, borrar una veda.
    """
    vacia = {"accion": "nada", "palabras": [], "cuentas": [], "solo_anuncios": False, "todo_anuncio": False}
    a = {**vacia, **(antes or {})}
    d = {**vacia, **(despues or {})}
    if despues is None:  # borrar
        return a["accion"] in ("aviso", "salto")
    if (a["solo_anuncios"], a["todo_anuncio"]) != (d["solo_anuncios"], d["todo_anuncio"]) and a["accion"] != "nada":
        return True
    pa, pd = set(a["palabras"]) | set(a["cuentas"]), set(d["palabras"]) | set(d["cuentas"])
    if d["accion"] == "permitir":
        # Un permiso nuevo, o más palabras en un permiso, deja pasar más cosas.
        return a["accion"] != "permitir" or bool(pd - pa)
    if a["accion"] == "permitir":
        return False  # sacar un permiso aprieta
    if FUERZA[d["accion"]] < FUERZA[a["accion"]]:
        return True
    return d["accion"] != "nada" and bool(pa - pd)


class Veda:
    """El menú de no deseados de una persona, guardado en ~/.config/euriloco/veda.json."""

    def __init__(self, dir_config: Path, semilla: Path):
        self.ruta = dir_config / "veda.json"
        self.historial = dir_config / "veda-historial.jsonl"
        self.candado = threading.Lock()
        if self.ruta.exists():
            self.datos = json.loads(self.ruta.read_text(encoding="utf-8"))
        else:
            self.datos = json.loads(semilla.read_text(encoding="utf-8"))
            self.datos.pop("_nota", None)
            for cat in self.datos["categorias"]:
                cat["subcategorias"] = [validar_subcategoria(s) for s in cat["subcategorias"]]
            self.datos.setdefault("pendientes", [])
            self._guardar("versión inicial")

    # --- utilidades
    def _guardar(self, motivo: str) -> None:
        temporal = self.ruta.with_suffix(".tmp")
        temporal.write_text(json.dumps(self.datos, ensure_ascii=False, indent=1), encoding="utf-8")
        temporal.replace(self.ruta)
        with self.historial.open("a", encoding="utf-8") as h:
            h.write(json.dumps({"momento_ms": int(time.time() * 1000), "motivo": motivo,
                                "version": self.version()}, ensure_ascii=False) + "\n")

    def version(self) -> str:
        sin_pendientes = {k: v for k, v in self.datos.items() if k != "pendientes"}
        return hashlib.sha256(json.dumps(sin_pendientes, sort_keys=True).encode()).hexdigest()[:12]

    def _categoria(self, cat_id: str) -> dict:
        for cat in self.datos["categorias"]:
            if cat["id"] == cat_id:
                return cat
        raise ValueError("categoría desconocida")

    def _buscar(self, sub_id: str) -> tuple[dict, int] | tuple[None, None]:
        for cat in self.datos["categorias"]:
            for i, s in enumerate(cat["subcategorias"]):
                if s["id"] == sub_id:
                    return cat, i
        return None, None

    def _vencer(self, ahora: int) -> None:
        antes = len(self.datos["pendientes"])
        self.datos["pendientes"] = [p for p in self.datos["pendientes"] if p["vence_ms"] > ahora]
        if len(self.datos["pendientes"]) != antes:
            self._guardar("pendientes vencidos sin confirmar")

    def _aplicar(self, cat_id: str, sub_id: str, nueva: dict | None) -> None:
        cat, i = self._buscar(sub_id)
        if nueva is None:
            if cat is not None:
                cat["subcategorias"].pop(i)
            return
        if cat is not None and cat["id"] == cat_id:
            cat["subcategorias"][i] = nueva
        else:
            if cat is not None:
                cat["subcategorias"].pop(i)
            self._categoria(cat_id)["subcategorias"].append(nueva)

    # --- operaciones
    def leer(self) -> dict:
        with self.candado:
            self._vencer(int(time.time() * 1000))
            return copy.deepcopy(self.datos)

    def cambiar(self, cat_id: str, sub: dict | None, sub_id: str | None = None, motivo: str = "") -> dict:
        """Aplica un cambio si aprieta; si afloja, lo deja pendiente con su motivo."""
        with self.candado:
            ahora = int(time.time() * 1000)
            self._categoria(cat_id)
            nueva = validar_subcategoria(sub) if sub is not None else None
            objetivo = nueva["id"] if nueva else sub_id
            cat_actual, i = self._buscar(objetivo)
            antes = cat_actual["subcategorias"][i] if cat_actual is not None else None
            if nueva is None and antes is None:
                raise ValueError("no existe esa subcategoría")
            if not es_aflojar(antes, nueva):
                self._sin_pendiente(objetivo)
                self._aplicar(cat_id, objetivo, nueva)
                self._guardar(f"apretar: {objetivo}")
                return {"estado": "aplicado", "version": self.version()}
            motivo = " ".join(motivo.split())
            if len(motivo) < MOTIVO_MINIMO:
                return {"estado": "falta_motivo", "minimo": MOTIVO_MINIMO}
            pendiente = {"id": "p-" + secrets.token_hex(4), "categoria": cat_id, "subcategoria": objetivo,
                         "nueva": nueva, "antes": antes, "motivo": motivo[:300],
                         "creado_ms": ahora, "efecto_ms": ahora + ESPERA_MS, "vence_ms": ahora + VENCE_MS}
            self._sin_pendiente(objetivo)  # un pedido nuevo reemplaza al anterior
            self.datos["pendientes"].append(pendiente)
            self._guardar(f"pedido de aflojar: {objetivo}")
            return {"estado": "pendiente", "pendiente": pendiente}

    def _sin_pendiente(self, sub_id: str) -> None:
        self.datos["pendientes"] = [p for p in self.datos["pendientes"] if p["subcategoria"] != sub_id]

    def resolver_pendiente(self, pendiente_id: str, confirmar: bool) -> dict:
        with self.candado:
            ahora = int(time.time() * 1000)
            self._vencer(ahora)
            for p in self.datos["pendientes"]:
                if p["id"] != pendiente_id:
                    continue
                if not confirmar:
                    self.datos["pendientes"].remove(p)
                    self._guardar(f"pedido cancelado: {p['subcategoria']}")
                    return {"estado": "cancelado"}
                if ahora < p["efecto_ms"]:
                    return {"estado": "esperando", "faltan_ms": p["efecto_ms"] - ahora}
                self._aplicar(p["categoria"], p["subcategoria"], p["nueva"])
                self.datos["pendientes"].remove(p)
                self._guardar(f"aflojado tras la espera: {p['subcategoria']} ({p['motivo']})")
                return {"estado": "aplicado", "version": self.version()}
            raise ValueError("pedido inexistente o vencido")

    def cambiar_avisos(self, formas: list, intensidad: float) -> dict:
        formas = [f for f in formas if f in ("visual", "vibracion", "ambos")] or ["visual"]
        with self.candado:
            self.datos["avisos"] = {"formas": formas, "intensidad": max(0.1, min(1.0, float(intensidad)))}
            self._guardar("formas de aviso")
            return {"estado": "aplicado", "avisos": self.datos["avisos"]}

    def para_mastil(self) -> dict:
        """Lo que viaja al teléfono: solo las subcategorías con acción, en el orden del menú."""
        datos = self.leer()
        activas = [{k: s[k] for k in ("id", "accion", "palabras", "cuentas", "solo_anuncios", "todo_anuncio")}
                   for s in subcategorias_planas(datos) if s["accion"] != "nada"]
        return {"version": self.version(), "subcategorias": activas,
                "avisos": datos.get("avisos", {"formas": ["visual"], "intensidad": 0.5})}


# ---------------------------------------------------------------- peso y simulación

def medir(eventos: list[dict], subs: list[dict]) -> dict:
    """
    Para cada subcategoría: cuántos posts reconoce, cuánto tiempo y cuántas detenciones largas,
    contando cada una por separado (sin importar su acción), más los posts que ninguna reconoce.
    """
    medidas = {s["id"]: {"posts": 0, "ms": 0, "detenciones": 0} for s in subs}
    # Por categoría se cuenta cada post una sola vez, aunque caiga en dos subcategorías.
    categorias = {s.get("categoria"): {"posts": 0, "ms": 0} for s in subs}
    sin = {"posts": 0, "ms": 0}
    for e in eventos:
        t = normalizar(e.get("texto"))
        tocadas = set()
        for s in subs:
            if aplica(s, t, e.get("cuenta"), bool(e.get("anuncio"))):
                m = medidas[s["id"]]
                m["posts"] += 1
                m["ms"] += e["permanencia_ms"]
                m["detenciones"] += e["permanencia_ms"] >= 3000
                tocadas.add(s.get("categoria"))
        for c in tocadas:
            categorias[c]["posts"] += 1
            categorias[c]["ms"] += e["permanencia_ms"]
        if not tocadas:
            sin["posts"] += 1
            sin["ms"] += e["permanencia_ms"]
    return {"subcategorias": medidas, "categorias": categorias, "sin_categoria": sin}


def simular(eventos: list[dict], sub: dict, ejemplos: int = 6) -> dict:
    """Qué habría hecho esta subcategoría sobre tu historial, con ejemplos para revisar."""
    s = validar_subcategoria(sub)
    hits = [e for e in eventos if aplica(s, normalizar(e.get("texto")), e.get("cuenta"), bool(e.get("anuncio")))]
    hits.sort(key=lambda e: -e["permanencia_ms"])
    return {"posts": len(hits), "ms": sum(e["permanencia_ms"] for e in hits),
            "detenciones": sum(e["permanencia_ms"] >= 3000 for e in hits),
            "ejemplos": [{"cuenta": e.get("cuenta"), "texto": (e.get("texto") or "")[:160],
                          "permanencia_ms": e["permanencia_ms"]} for e in hits[:ejemplos]]}
