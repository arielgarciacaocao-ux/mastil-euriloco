package ar.mastil

/**
 * Separa, de todo lo que X muestra en una fila, el contenido del post del ruido de la interfaz
 * (botones, contadores, marcas de traducción). No depende de Android: se prueba aparte.
 */
object Limpieza {

    data class Resultado(
        val texto: String,        // el post limpio: nombre, cuenta y contenido
        val cuenta: String?,      // @cuenta del autor
        val anuncio: Boolean,     // publicidad
        val medio: String?,       // "imagen", "video", "gif" o null
        val firma: String,        // base estable para reconocer el mismo post aunque cambien los contadores
    )

    private val HANDLE = Regex("@[A-Za-z0-9_]{1,15}")

    /** Etiquetas de la interfaz de X, en castellano e inglés. Se comparan sin mayúsculas. */
    private val RUIDO = setOf(
        "explicar este post con grok", "explain this post", "opciones del post", "more", "más opciones",
        "responder", "reply", "repostear", "repost", "impresiones", "views", "ver", "guardar elemento",
        "bookmark", "compartir", "share", "me gusta", "like", "citar", "quote", "dejar de silenciar",
        "silenciar", "unmute", "mute", "verificada", "verified", "mostrar original", "show original",
        "traducir post", "translate post", "ver más", "mostrar más", "show more", "cuenta parodia",
        "parody account", "hecho con ia", "made with ai", "seguir", "follow", "·", "",
    )
    private val ANUNCIO = setOf("ad", "anuncio", "promocionado", "promoted")
    private val MEDIOS = mapOf("imagen" to "imagen", "image" to "imagen", "video" to "video", "gif" to "gif")

    private val NUMERO = Regex("""^[\d.,]+\s*(k|m|mil|mill\.?)?$""", RegexOption.IGNORE_CASE)
    private val TIEMPO = Regex("""^\d+\s*(s|min|h|d|sem)$""", RegexOption.IGNORE_CASE)
    private val DOMINIO = Regex("""^(de|from)\s+[\w.-]+\.[a-z]{2,}$""", RegexOption.IGNORE_CASE)
    private val TRADUCIDO = Regex("""^(traducido del|translated from)\b.*""", RegexOption.IGNORE_CASE)

    fun limpiar(textos: List<String>, maxTexto: Int = 1000): Resultado {
        var anuncio = false
        var medio: String? = null
        val utiles = mutableListOf<String>()
        for (crudo in textos) {
            val t = crudo.trim()
            val clave = t.lowercase()
            when {
                clave in ANUNCIO -> anuncio = true
                clave in MEDIOS -> if (medio == null) medio = MEDIOS[clave]
                clave in RUIDO -> {}
                NUMERO.matches(t) || TIEMPO.matches(t) || DOMINIO.matches(t) || TRADUCIDO.matches(t) -> {}
                else -> if (t !in utiles) utiles.add(t)
            }
        }
        val texto = utiles.joinToString(" · ").take(maxTexto)
        val cuenta = utiles.firstNotNullOfOrNull { HANDLE.matchEntire(it)?.value } ?: HANDLE.find(texto)?.value
        // La firma usa el fragmento más largo (el cuerpo del post): sigue igual aunque el post
        // entre a medias en pantalla y falten el nombre o la cuenta. Si es corto (solo un nombre),
        // se le suma la cuenta para no confundir posts distintos.
        val cuerpo = utiles.filter { it != cuenta }.maxByOrNull { it.length }.orEmpty().take(100)
        val firma = if (cuerpo.length >= 25) cuerpo else (cuenta ?: "") + "|" + cuerpo
        return Resultado(texto, cuenta, anuncio, medio, firma)
    }
}
