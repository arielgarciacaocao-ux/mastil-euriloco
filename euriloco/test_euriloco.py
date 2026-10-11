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

    def test_texto_largo_se_recorta(self):
        e = self.evento(1, 1000)
        e["texto"] = "a" * 5000
        self.pedir("/api/eventos", {"eventos": [e]})
        fila = self.servidor.RequestHandlerClass.memoria.consulta("SELECT texto FROM eventos")[0]
        self.assertEqual(len(fila["texto"]), euriloco.MAX_TEXTO)


if __name__ == "__main__":
    unittest.main(verbosity=2)
