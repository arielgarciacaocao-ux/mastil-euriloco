"""Pruebas de Euríloco: levanta el servidor en un puerto libre y lo usa como lo haría Mástil."""

import json
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

import euriloco


class PruebaEuriloco(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.dir_config, self.dir_datos = base / "config", base / "datos"
        self.config = euriloco.cargar_config(self.dir_config)
        self.servidor = euriloco.crear_servidor(self.config, self.dir_config, self.dir_datos, "127.0.0.1", 0)
        self.base = f"http://127.0.0.1:{self.servidor.server_address[1]}"
        threading.Thread(target=self.servidor.serve_forever, daemon=True).start()

    def tearDown(self):
        self.servidor.shutdown()
        self.servidor.server_close()
        self.tmp.cleanup()

    def pedir(self, ruta, datos=None, clave="ok"):
        cab = {"Content-Type": "application/json"}
        if clave == "ok":
            cab["Authorization"] = "Bearer " + self.config["clave"]
        elif clave:
            cab["Authorization"] = "Bearer " + clave
        cuerpo = json.dumps(datos).encode() if datos is not None else None
        req = urllib.request.Request(self.base + ruta, data=cuerpo, headers=cab)
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def evento(self, i, permanencia, cuenta="@alguien", hace_seg=60):
        fin = int((time.time() - hace_seg) * 1000)
        return {"id": f"e{i}", "red": "x", "inicio": fin - permanencia, "fin": fin,
                "permanencia_ms": permanencia, "cuenta": cuenta, "texto": f"post <b>{i}</b>",
                "tiene_imagen": i % 2 == 0, "clave": f"k{i}"}

    def test_config_se_crea_una_vez(self):
        clave = self.config["clave"]
        self.assertEqual(euriloco.cargar_config(self.dir_config)["clave"], clave)
        self.assertTrue((self.dir_config / "cuerdas.toml").exists())

    def test_sin_clave_no_entra(self):
        self.assertEqual(self.pedir("/api/estado", clave=None)[0], 401)
        self.assertEqual(self.pedir("/api/estado", clave="falsa")[0], 401)
        self.assertEqual(self.pedir("/api/eventos", {"eventos": []}, clave="falsa")[0], 401)

    def test_eventos_se_guardan_sin_duplicar(self):
        lote = {"dispositivo": "telefono", "eventos": [self.evento(i, 500 * i) for i in range(1, 9)]}
        cod, r = self.pedir("/api/eventos", lote)
        self.assertEqual((cod, r["recibidos"], r["nuevos"]), (200, 8, 8))
        cod, r = self.pedir("/api/eventos", lote)  # reenvío tras un corte: no duplica
        self.assertEqual((r["recibidos"], r["nuevos"]), (8, 0))
        self.assertEqual(self.pedir("/api/estado")[1]["eventos_total"], 8)

    def test_eventos_invalidos_se_descartan(self):
        malos = [{"id": "a"}, {"id": "b", "inicio": 10, "fin": 5}, "texto", {"id": "c", "inicio": "x", "fin": 1}]
        cod, r = self.pedir("/api/eventos", {"eventos": malos + [self.evento(1, 1000)]})
        self.assertEqual((cod, r["nuevos"]), (200, 1))
        self.assertEqual(self.pedir("/api/eventos", {"nada": 1})[0], 400)

    def test_cuerdas(self):
        cod, r = self.pedir("/api/cuerdas")
        self.assertEqual(cod, 200)
        self.assertIn("Cuerdas de Mástil", r["toml"])
        self.assertEqual(r["version"], self.pedir("/api/estado")[1]["cuerdas_version"])

    def test_espejo(self):
        eventos = [self.evento(1, 400, "@rapido"), self.evento(2, 4000, "@anzuelo"),
                   self.evento(3, 12000, "@anzuelo"), self.evento(4, 2500, "@medio")]
        self.pedir("/api/eventos", {"eventos": eventos})
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7&umbral=3000") as r:
            d = json.loads(r.read())
        self.assertEqual(d["eventos_total"], 4)
        self.assertEqual(sum(f["detenciones"] for f in d["por_dia"]), 2)
        self.assertEqual(sum(f["detenciones_ms"] for f in d["por_dia"]), 16000)
        self.assertEqual(d["cuentas"][0]["cuenta"], "@anzuelo")
        self.assertEqual(len(d["detenciones"]), 2)
        self.assertEqual({t["tramo"]: t["posts"] for t in d["tramos"]}, {0: 1, 2: 1, 3: 1, 5: 1})

    def test_panel_y_emparejar(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("Euríloco", r.read().decode())
        with urllib.request.urlopen(self.base + "/panel/emparejar") as r:
            d = json.loads(r.read())
        self.assertTrue(d["enlace"].startswith("mastil://emparejar?h="))
        self.assertIn(self.config["clave"], d["enlace"])

    def test_anuncio_y_medio(self):
        e = self.evento(1, 4000, "@Starlink")
        e.update({"anuncio": True, "medio": "video"})
        self.pedir("/api/eventos", {"eventos": [e, self.evento(2, 3500)]})
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7&umbral=3000") as r:
            d = json.loads(r.read())
        self.assertEqual(d["anuncios"]["posts"], 1)
        marcados = [x for x in d["detenciones"] if x["anuncio"]]
        self.assertEqual((len(marcados), marcados[0]["medio"]), (1, "video"))

    def test_migra_base_vieja(self):
        import sqlite3
        ruta = Path(self.tmp.name) / "vieja.db"
        con = sqlite3.connect(ruta)
        viejo = euriloco.ESQUEMA.split("anuncio       INTEGER")[0].rstrip().rstrip(",") + "\n);"
        con.executescript(viejo)
        con.execute("INSERT INTO eventos (id, red, inicio_ms, fin_ms, permanencia_ms, recibido_ms) VALUES ('v', 'x', 1, 2, 1, 3)")
        con.commit(); con.close()
        m = euriloco.Memoria(ruta)
        fila = m.consulta("SELECT id, anuncio, medio, vistas, captura, descripcion FROM eventos")[0]
        self.assertEqual(fila, {"id": "v", "anuncio": 0, "medio": None, "vistas": None,
                                "captura": 0, "descripcion": None})

    # ---- 0.2: miniaturas, señales, etiquetas, visión

    JPEG = b"\xff\xd8\xff\xe0" + b"0" * 200

    def subir(self, id_, datos, clave="ok"):
        cab = {"Content-Type": "image/jpeg"}
        if clave == "ok":
            cab["Authorization"] = "Bearer " + self.config["clave"]
        req = urllib.request.Request(f"{self.base}/api/captura?id={id_}", data=datos, headers=cab)
        try:
            with urllib.request.urlopen(req) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code

    def id_evento(self, i):
        return f"0000000{i}-aaaa-bbbb-cccc-dddddddddddd"

    def test_miniatura_se_guarda_y_se_sirve(self):
        e = self.evento(1, 5000)
        e["id"] = self.id_evento(1)
        self.pedir("/api/eventos", {"eventos": [e]})
        self.assertEqual(self.subir(e["id"], self.JPEG), 200)
        with urllib.request.urlopen(f"{self.base}/panel/captura/{e['id']}.jpg") as r:
            self.assertEqual(r.read(), self.JPEG)
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7") as r:
            d = json.loads(r.read())
        self.assertEqual(d["detenciones"][0]["captura"], 1)
        self.assertEqual(d["descripciones_pendientes"], 1)

    def test_miniatura_invalida(self):
        self.assertEqual(self.subir(self.id_evento(1), self.JPEG, clave=None), 401)
        self.assertEqual(self.subir(self.id_evento(1), b"<html>no es imagen</html>"), 400)
        self.assertEqual(self.subir("../../etc/passwd", self.JPEG), 400)
        self.assertEqual(urllib.request.urlopen.__name__, "urlopen")
        with self.assertRaises(urllib.error.HTTPError):
            urllib.request.urlopen(f"{self.base}/panel/captura/..%2F..%2Fx.jpg")

    def test_senales_se_guardan(self):
        e = self.evento(1, 6000, "@viral")
        e.update({"vistas": 3500000, "verificada": True, "traducido": True, "pestana": "para ti"})
        self.pedir("/api/eventos", {"eventos": [e, self.evento(2, 4000, "@amigo")]})
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7") as r:
            d = json.loads(r.read())
        viral = [x for x in d["detenciones"] if x["cuenta"] == "@viral"][0]
        self.assertEqual((viral["vistas"], viral["verificada"], viral["traducido"], viral["pestana"]),
                         (3500000, 1, 1, "para ti"))
        self.assertEqual({p["pestana"] for p in d["por_pestana"]}, {"para ti", "sin dato"})

    def etiquetar(self, cuenta, etiqueta):
        req = urllib.request.Request(self.base + "/panel/etiqueta",
                                     data=json.dumps({"cuenta": cuenta, "etiqueta": etiqueta}).encode(),
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_etiquetas(self):
        self.pedir("/api/eventos", {"eventos": [self.evento(1, 9000, "@cebo"), self.evento(2, 4000, "@cebo"),
                                                self.evento(3, 5000, "@sabio")]})
        self.assertEqual(self.etiquetar("@cebo", "  Anzuelo ")[0], 200)
        self.assertEqual(self.etiquetar("@nuevo", "Mi Etiqueta")[1]["etiqueta"], "mi etiqueta")
        self.assertEqual(self.etiquetar("sin-arroba", "x")[0], 400)
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7") as r:
            d = json.loads(r.read())
        tiempos = {f["etiqueta"]: f["total_ms"] for f in d["por_etiqueta"]}
        self.assertEqual(tiempos, {"anzuelo": 13000, "sin etiqueta": 5000})
        self.assertIn("mi etiqueta", d["etiquetas"])
        self.assertEqual([c["etiqueta"] for c in d["cuentas"] if c["cuenta"] == "@cebo"], ["anzuelo"])
        self.etiquetar("@cebo", "")
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7") as r:
            d = json.loads(r.read())
        self.assertEqual({f["etiqueta"] for f in d["por_etiqueta"]}, {"sin etiqueta"})

    def test_vision_con_ollama_simulado(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        pedidos = []

        class FalsoOllama(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def responder(self, datos):
                cuerpo = json.dumps(datos).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(cuerpo)))
                self.end_headers()
                self.wfile.write(cuerpo)

            def do_GET(self):
                self.responder({"models": [{"name": "moondream:latest"}]})

            def do_POST(self):
                pedidos.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.responder({"response": "  Una persona   bailando en una cocina. "})

        falso = ThreadingHTTPServer(("127.0.0.1", 0), FalsoOllama)
        threading.Thread(target=falso.serve_forever, daemon=True).start()
        try:
            vision = dict(euriloco.VISION_POR_DEFECTO, url=f"http://127.0.0.1:{falso.server_address[1]}")
            self.assertTrue(euriloco.modelo_disponible(vision))
            e = self.evento(1, 5000)
            e["id"] = self.id_evento(1)
            self.pedir("/api/eventos", {"eventos": [e]})
            self.subir(e["id"], self.JPEG)
            memoria = self.servidor.RequestHandlerClass.memoria
            capturas = self.servidor.RequestHandlerClass.dir_capturas
            self.assertEqual(euriloco.describir_pendientes(memoria, capturas, vision), 1)
            self.assertEqual(euriloco.describir_pendientes(memoria, capturas, vision), 0)
            fila = memoria.consulta("SELECT descripcion FROM eventos")[0]
            self.assertEqual(fila["descripcion"], "Una persona bailando en una cocina.")
            self.assertEqual(pedidos[0]["model"], "moondream")
            self.assertEqual(len(pedidos[0]["images"]), 1)
        finally:
            falso.shutdown()
            falso.server_close()

    def test_sin_ollama_no_falla(self):
        vision = dict(euriloco.VISION_POR_DEFECTO, url="http://127.0.0.1:9")
        self.assertFalse(euriloco.modelo_disponible(vision))

    def test_poda_de_miniaturas(self):
        import os
        e = self.evento(1, 5000)
        e["id"] = self.id_evento(1)
        self.pedir("/api/eventos", {"eventos": [e]})
        self.subir(e["id"], self.JPEG)
        h = self.servidor.RequestHandlerClass
        archivo = h.dir_capturas / f"{e['id']}.jpg"
        viejo = time.time() - 90 * 86400
        os.utime(archivo, (viejo, viejo))
        self.assertEqual(euriloco.podar_capturas(h.memoria, h.dir_capturas, 60), 1)
        self.assertFalse(archivo.exists())
        self.assertEqual(h.memoria.consulta("SELECT captura FROM eventos")[0]["captura"], 0)

    # ---- 0.3: acciones

    def accion(self, i, accion, cuenta="@alguien", clave="k1", duracion=None):
        return {"tipo": "accion", "id": f"a{i}", "momento": int(time.time() * 1000) - 1000, "accion": accion,
                "cuenta": cuenta, "clave": clave, "texto": "post", "pestana": "para ti", "duracion_ms": duracion}

    def test_acciones_se_guardan_y_se_ven(self):
        ev = self.evento(1, 6000, "@cebo")  # clave k1
        lote = [ev, self.accion(1, "me gusta", "@cebo"), self.accion(2, "entrar al post", "@cebo", duracion=95000),
                self.accion(3, "no me interesa", "@otra", clave="k9"), self.accion(4, "hackear", "@x"),
                {"tipo": "accion", "id": "a5"}]
        cod, r = self.pedir("/api/eventos", {"eventos": lote})
        self.assertEqual((cod, r["nuevos"]), (200, 4))  # 1 evento + 3 acciones válidas
        cod, r = self.pedir("/api/eventos", {"eventos": lote})
        self.assertEqual(r["nuevos"], 0)
        self.etiquetar("@cebo", "anzuelo")
        with urllib.request.urlopen(self.base + "/panel/datos?dias=7") as r:
            d = json.loads(r.read())
        self.assertEqual(d["eventos_total"], 1)
        self.assertEqual(d["detenciones"][0]["acciones"], ["entrar al post", "me gusta"])
        periodo = {(f["accion"], f["etiqueta"]): f["veces"] for f in d["acciones_periodo"]}
        self.assertEqual(periodo, {("me gusta", "anzuelo"): 1, ("entrar al post", "anzuelo"): 1,
                                   ("no me interesa", "sin etiqueta"): 1})
        self.assertEqual(len(d["ultimas_acciones"]), 3)
        self.assertEqual(d["pesos"]["no me interesa"], -74.0)

    def test_texto_largo_se_recorta(self):
        e = self.evento(1, 1000)
        e["texto"] = "a" * 5000
        self.pedir("/api/eventos", {"eventos": [e]})
        fila = self.servidor.RequestHandlerClass.memoria.consulta("SELECT texto FROM eventos")[0]
        self.assertEqual(len(fila["texto"]), euriloco.MAX_TEXTO)


if __name__ == "__main__":
    unittest.main(verbosity=2)
