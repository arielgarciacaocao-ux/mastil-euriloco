package ar.mastil

/**
 * Separa, de todo lo que X muestra en una fila, el contenido del post del ruido de la interfaz
 * (botones, contadores, marcas de traducción), y rescata del ruido las señales que sirven:
 * alcance, verificación, traducción. No depende de Android: se prueba aparte.
 */
object Limpieza {

    data class Resultado(
        val texto: String,        // el post limpio: nombre, cuenta y contenido
        val cuenta: String?,      // @cuenta del autor
        val anuncio: Boolean,     // publicidad
        val medio: String?,       // "imagen", "video", "gif" o null
        val firma: String,        // base estable para reconocer el mismo post aunque cambien los contadores
        val vistas: Long?,        // el contador más alto de la fila: casi siempre, las vistas
        val verificada: Boolean,  // cuenta con tilde
        val traducido: Boolean,   // X tradujo el post de otro idioma
        // Estado de tus botones en este post: X los marca cambiando el nombre del botón
        // («Me gusta» pasa a «Deshacer Me gusta»). Comparar el antes y el después revela la acción.
        val gustado: Boolean = false,
        val reposteado: Boolean = false,
        val guardado: Boolean = false,
        /** La fila es el aviso que deja X después de «No me interesa» (el post desapareció). */
        val descartado: Boolean = false,
    )

    /** Frases del aviso que reemplaza un post marcado con «No me interesa». */
    private val DESCARTE = listOf("verás menos", "veras menos", "mejorar tu cronología", "mejorar tu timeline",
        "no te interesa este post", "see fewer", "improve your timeline", "this post is hidden", "post oculto")

    private val HANDLE = Regex("@[A-Za-z0-9_]{1,15}")

    /** Etiquetas de la interfaz de X, en castellano e inglés. Se comparan sin mayúsculas. */
    private val RUIDO = setOf(
        "explicar este post con grok", "explain this post", "opciones del post", "more", "más opciones",
        "responder", "reply", "repostear", "repost", "impresiones", "views", "ver", "guardar elemento",
        "bookmark", "compartir", "share", "me gusta", "like", "citar", "quote", "dejar de silenciar",
        "silenciar", "unmute", "mute", "mostrar original", "show original",
        "traducir post", "translate post", "ver más", "mostrar más", "show more", "cuenta parodia",
        "parody account", "hecho con ia", "made with ai", "seguir", "follow", "·", "",
    )
    private val VERIFICADA = setOf("verificada", "verified")
    private val ANUNCIO = setOf("ad", "anuncio", "promocionado", "promoted")
    private val MEDIOS = mapOf("imagen" to "imagen", "image" to "imagen", "video" to "video", "gif" to "gif")

    private val NUMERO = Regex("""^([\d.,]+)\s*(k|m|mil|mill\.?)?$""", RegexOption.IGNORE_CASE)
    private val TIEMPO = Regex("""^\d+\s*(s|min|h|d|sem)$""", RegexOption.IGNORE_CASE)
    private val DOMINIO = Regex("""^(de|from)\s+[\w.-]+\.[a-z]{2,}$""", RegexOption.IGNORE_CASE)
    private val TRADUCIDO = Regex("""^(traducido del|translated from)\b.*""", RegexOption.IGNORE_CASE)

    fun limpiar(textos: List<String>, maxTexto: Int = 1000): Resultado {
        var anuncio = false
        var medio: String? = null
        var verificada = false
        var traducido = false
        var vistas: Long? = null
        var gustado = false
        var reposteado = false
        var guardado = false
        var descartado = false
        val utiles = mutableListOf<String>()
        for (crudo in textos) {
            // X antepone «· » a la hora («· 1h»): se quita para reconocerla.
            val t = crudo.trim().trimStart('·', ' ').trim()
            val clave = t.lowercase()
            val numero = NUMERO.matchEntire(t)
            when {
                clave.startsWith("deshacer") || clave.startsWith("undo") -> {
                    if ("me gusta" in clave || "like" in clave) gustado = true
                    if ("repost" in clave || "retweet" in clave) reposteado = true
                }
                clave == "liked" || clave == "unlike" -> gustado = true
                (clave.contains("guardad") && clave != "guardar elemento") || clave == "remove bookmark" ||
                    clave == "bookmarked" -> guardado = true
                DESCARTE.any { it in clave } -> descartado = true
                clave in ANUNCIO -> anuncio = true
                clave in VERIFICADA -> verificada = true
                clave in MEDIOS -> if (medio == null) medio = MEDIOS[clave]
                clave in RUIDO -> {}
                numero != null -> numeroDe(numero)?.let { n -> if (n > (vistas ?: -1)) vistas = n }
                TRADUCIDO.matches(t) -> traducido = true
                TIEMPO.matches(t) || DOMINIO.matches(t) -> {}
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
        return Resultado(texto, cuenta, anuncio, medio, firma, vistas, verificada, traducido,
            gustado, reposteado, guardado, descartado)
    }

    /** "4.4K" → 4400, "3,5 M" → 3500000, "1.234" → 1234, "28 mil" → 28000. */
    fun numeroDe(m: MatchResult): Long? {
        val cifra = m.groupValues[1]
        val sufijo = m.groupValues[2].lowercase()
        val multiplicador = when {
            sufijo == "k" || sufijo == "mil" -> 1_000.0
            sufijo == "m" || sufijo.startsWith("mill") -> 1_000_000.0
            else -> 1.0
        }
        val valor = if (multiplicador == 1.0) {
            // Sin sufijo, punto y coma son separadores de miles.
            cifra.replace(".", "").replace(",", "").toDoubleOrNull()
        } else {
            // Con sufijo, el separador es decimal: "4.4K" o "3,5 M".
            cifra.replace(",", ".").toDoubleOrNull()
        }
        return valor?.let { (it * multiplicador).toLong() }
    }
}
