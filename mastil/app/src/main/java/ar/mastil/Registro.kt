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
                JSONObject(linea).takeIf { it.optLong("fin") >= limite }
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
    fun masViejo(c: Context): Long = pendientes(c).minOfOrNull { it.optLong("fin") } ?: 0L
}
