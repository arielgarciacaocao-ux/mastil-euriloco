package ar.mastil

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Registro local de eventos: un archivo con un evento JSON por línea.
 * Guarda hasta una semana; lo que ya llegó a Euríloco se borra del teléfono.
 */
object Registro {
    const val RETENCION_MS = 7L * 24 * 3600 * 1000

    private fun archivo(c: Context) = File(c.filesDir, "eventos.jsonl")

    @Synchronized
    fun agregar(c: Context, evento: JSONObject) {
        archivo(c).appendText(evento.toString() + "\n")
    }

    /** Eventos todavía no sincronizados y dentro de la semana. */
    @Synchronized
    fun pendientes(c: Context): List<JSONObject> {
        val f = archivo(c)
        if (!f.exists()) return emptyList()
        val limite = System.currentTimeMillis() - RETENCION_MS
        return f.readLines().mapNotNull { linea ->
            try {
                JSONObject(linea).takeIf { momento(it) >= limite }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Quita los eventos ya enviados y los que pasaron la semana. */
    @Synchronized
    fun quitar(c: Context, ids: Set<String>) {
        val quedan = pendientes(c).filter { it.optString("id") !in ids }
        val f = archivo(c)
        val tmp = File(c.filesDir, "eventos.tmp")
        tmp.writeText(quedan.joinToString("") { it.toString() + "\n" })
        tmp.renameTo(f)
    }

    fun podar(c: Context) = quitar(c, emptySet())

    fun cantidad(c: Context): Int = pendientes(c).size

    /** Momento del evento pendiente más viejo, o 0 si no hay. */
    fun masViejo(c: Context): Long = pendientes(c).minOfOrNull { momento(it) } ?: 0L

    /** Cuándo ocurrió: los eventos de permanencia tienen «fin»; las acciones, «momento». */
    private fun momento(j: JSONObject): Long = if (j.has("fin")) j.optLong("fin") else j.optLong("momento")
}
