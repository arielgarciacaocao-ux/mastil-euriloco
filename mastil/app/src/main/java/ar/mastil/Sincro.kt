package ar.mastil

import android.content.Context
import ar.mastil.Config.cuerdasVersion
import ar.mastil.Config.ultimaSincro
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Canal directo con Euríloco: manda los eventos y trae las cuerdas. */
object Sincro {
    private val enCurso = AtomicBoolean(false)
    private const val LOTE = 1000

    /** Intenta sincronizar. Llamar siempre desde un hilo de fondo. Devuelve un mensaje para mostrar. */
    fun intentar(c: Context): String {
        if (!enCurso.compareAndSet(false, true)) return "Ya hay una sincronización en curso."
        try {
            Registro.podar(c)
            if (!Config.emparejado(c)) return "Mástil todavía no está emparejado con Euríloco."
            val host = elegirHost(c)
            if (host == null) {
                Avisos.revisarPendiente(c)
                return "No se encontró a Euríloco. ¿El teléfono está en la red de casa o con Tailscale activo?"
            }
            var enviados = 0
            var pendientes = Registro.pendientes(c)
            while (pendientes.isNotEmpty()) {
                val lote = pendientes.take(LOTE)
                val cuerpo = JSONObject()
                    .put("dispositivo", Config.dispositivo(c))
                    .put("eventos", JSONArray(lote))
                pedir(c, host, "POST", "/api/eventos", cuerpo.toString())
                    ?: return "Se cortó la conexión a mitad de camino; se reintenta más tarde."
                Registro.quitar(c, lote.map { it.optString("id") }.toSet())
                enviados += lote.size
                pendientes = Registro.pendientes(c)
            }
            // Miniaturas: después de los eventos, para que Euríloco ya conozca cada post.
            Capturas.podar(c)
            var imagenes = 0
            for (f in Capturas.pendientes(c)) {
                val id = f.name.removeSuffix(".jpg")
                pedirBytes(c, host, "/api/captura?id=$id", f.readBytes())
                    ?: return "Se cortó la conexión mientras mandaba miniaturas; se reintenta más tarde."
                f.delete()
                imagenes++
            }
            // Las cuerdas todavía no actúan en la etapa 1, pero ya se guardan.
            pedir(c, host, "GET", "/api/cuerdas", null)?.let { r ->
                val json = JSONObject(r)
                File(c.filesDir, "cuerdas.toml").writeText(json.optString("toml"))
                c.cuerdasVersion = json.optString("version")
            }
            c.ultimaSincro = System.currentTimeMillis()
            return if (enviados == 0 && imagenes == 0) "Sincronizado: no había eventos nuevos."
            else "Sincronizado: $enviados eventos y $imagenes miniaturas enviados a Euríloco."
        } finally {
            enCurso.set(false)
        }
    }

    /** Primera dirección que responde con la clave correcta. */
    private fun elegirHost(c: Context): String? =
        Config.hosts(c).firstOrNull { pedir(c, it, "GET", "/api/estado", null) != null }

    private fun pedirBytes(c: Context, host: String, ruta: String, datos: ByteArray): String? {
        var con: HttpURLConnection? = null
        return try {
            con = URI.create("http://$host$ruta").toURL().openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.connectTimeout = 4000
            con.readTimeout = 15000
            con.setRequestProperty("Authorization", "Bearer " + Config.clave(c))
            con.setRequestProperty("Content-Type", "image/jpeg")
            con.doOutput = true
            con.setFixedLengthStreamingMode(datos.size)
            con.outputStream.use { it.write(datos) }
            if (con.responseCode != 200) null
            else con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            con?.disconnect()
        }
    }

    private fun pedir(c: Context, host: String, metodo: String, ruta: String, cuerpo: String?): String? {
        var con: HttpURLConnection? = null
        return try {
            con = URI.create("http://$host$ruta").toURL().openConnection() as HttpURLConnection
            con.requestMethod = metodo
            con.connectTimeout = 4000
            con.readTimeout = 15000
            con.setRequestProperty("Authorization", "Bearer " + Config.clave(c))
            if (cuerpo != null) {
                val bytes = cuerpo.toByteArray(Charsets.UTF_8)
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.setFixedLengthStreamingMode(bytes.size)
                con.outputStream.use { it.write(bytes) }
            }
            if (con.responseCode != 200) null
            else con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            con?.disconnect()
        }
    }
}
