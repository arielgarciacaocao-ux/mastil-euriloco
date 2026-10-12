package ar.mastil

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Las cuerdas en el teléfono: las subcategorías del menú de no deseados que tienen acción.
 * Es el mismo clasificador que el de Euríloco (veda.py), con las mismas reglas de
 * normalización y de bordes de palabra, para que el panel simule lo que Mástil hace.
 */
object Cuerdas {

    data class Subcategoria(
        val id: String,
        val accion: String,            // aviso, salto, permitir
        val palabras: List<String>,
        val cuentas: Set<String>,
        val soloAnuncios: Boolean,
        val todoAnuncio: Boolean,
    )

    data class Juego(
        val version: String,
        val subcategorias: List<Subcategoria>,
        val formas: List<String>,      // visual, vibracion, ambos
        val intensidad: Double,
    )

    val VACIO = Juego("", emptyList(), listOf("visual"), 0.5)

    private val FUERZA = mapOf("nada" to 0, "aviso" to 1, "salto" to 2)
    private val MARCAS = Regex("\\p{Mn}+")

    fun normalizar(texto: String?): String =
        MARCAS.replace(Normalizer.normalize(texto ?: "", Normalizer.Form.NFD), "").lowercase()

    /** Igual que veda.coincide: bordes de palabra, * para buscar el comienzo, símbolos tal cual. */
    fun coincide(textoNormalizado: String, palabra: String): Boolean {
        var p = normalizar(palabra.trim())
        val prefijo = p.endsWith("*")
        if (prefijo) p = p.dropLast(1)
        if (p.isEmpty()) return false
        var desde = 0
        while (true) {
            val i = textoNormalizado.indexOf(p, desde)
            if (i < 0) return false
            val fin = i + p.length
            val antesOk = !p.first().isLetterOrDigit() || i == 0 || !textoNormalizado[i - 1].isLetterOrDigit()
            val despuesOk = prefijo || !p.last().isLetterOrDigit() || fin >= textoNormalizado.length ||
                !textoNormalizado[fin].isLetterOrDigit()
            if (antesOk && despuesOk) return true
            desde = i + 1
        }
    }

    fun aplica(s: Subcategoria, textoNormalizado: String, cuenta: String?, anuncio: Boolean): Boolean {
        if (s.todoAnuncio) return anuncio
        if (s.soloAnuncios && !anuncio) return false
        if (cuenta != null && cuenta.lowercase() in s.cuentas) return true
        return s.palabras.any { coincide(textoNormalizado, it) }
    }

    /** (id de la subcategoría que manda, acción). Un «permitir» que coincide gana siempre. */
    fun resolver(juego: Juego, texto: String?, cuenta: String?, anuncio: Boolean): Pair<String?, String> {
        val t = normalizar(texto)
        var mejorId: String? = null
        var mejor = "nada"
        for (s in juego.subcategorias) {
            if (s.accion == "nada" || !aplica(s, t, cuenta, anuncio)) continue
            if (s.accion == "permitir") return s.id to "permitir"
            if ((FUERZA[s.accion] ?: 0) > (FUERZA[mejor] ?: 0)) {
                mejorId = s.id
                mejor = s.accion
            }
        }
        return mejorId to mejor
    }

    fun desdeJson(texto: String): Juego {
        val j = JSONObject(texto)
        val subs = mutableListOf<Subcategoria>()
        val lista = j.optJSONArray("subcategorias") ?: JSONArray()
        for (i in 0 until lista.length()) {
            val s = lista.getJSONObject(i)
            subs.add(Subcategoria(
                id = s.optString("id"),
                accion = s.optString("accion", "nada"),
                palabras = textos(s.optJSONArray("palabras")),
                cuentas = textos(s.optJSONArray("cuentas")).map { it.lowercase() }.toSet(),
                soloAnuncios = s.optBoolean("solo_anuncios"),
                todoAnuncio = s.optBoolean("todo_anuncio"),
            ))
        }
        val avisos = j.optJSONObject("avisos")
        val formas = textos(avisos?.optJSONArray("formas")).ifEmpty { listOf("visual") }
        return Juego(j.optString("version"), subs, formas, avisos?.optDouble("intensidad", 0.5) ?: 0.5)
    }

    private fun textos(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
}
