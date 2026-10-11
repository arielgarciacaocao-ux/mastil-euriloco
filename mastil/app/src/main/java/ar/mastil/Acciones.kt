package ar.mastil

/**
 * Traduce lo que tocaste dentro de X a una acción con nombre: las señales explícitas
 * que le das al algoritmo. No depende de Android: se prueba aparte.
 *
 * X pone en cada botón una descripción como «Me gusta», «28 mil Me gusta», «Ya no me gusta»
 * o «Responder». Se busca la frase dentro de la descripción, del caso más específico al
 * más general («ya no me gusta» antes que «me gusta»).
 */
object Acciones {
    private val REGLAS: List<Pair<String, List<String>>> = listOf(
        "quitar me gusta" to listOf("deshacer me gusta", "ya no me gusta", "unlike", "liked"),
        "me gusta" to listOf("me gusta", "like"),
        "deshacer repost" to listOf("deshacer repost", "deshacer repostear", "undo repost"),
        "repostear" to listOf("repostear", "repost"),
        "quitar guardado" to listOf("quitar de guardados", "eliminar de elementos guardados", "remove bookmark"),
        "guardar" to listOf("guardar elemento", "elementos guardados", "bookmark"),
        "no me interesa" to listOf("no me interesa", "no me interesa este post", "not interested"),
        "dejar de silenciar cuenta" to listOf("dejar de silenciar a @", "unmute @"),
        "silenciar" to listOf("silenciar a @", "silenciar @", "mute @"),
        "bloquear" to listOf("bloquear a @", "bloquear @", "block @"),
        "denunciar" to listOf("denunciar post", "denunciar", "report post", "report"),
        "dejar de seguir" to listOf("dejar de seguir", "unfollow"),
        "seguir" to listOf("seguir a @", "seguir", "follow"),
        "responder" to listOf("responder", "reply"),
        "compartir" to listOf("compartir", "share"),
        "activar sonido" to listOf("dejar de silenciar", "activar sonido", "unmute"),
        "ver traducción" to listOf("mostrar traducción", "traducir post", "translate post"),
    )

    /** Acciones que no son botones sino cambios de pantalla, detectados aparte. */
    const val ENTRAR = "entrar al post"
    const val PERFIL = "abrir perfil"
    const val RESPONDER = "responder"

    private val SIN_ACCION = listOf("explicar este post con grok", "opciones del post", "more", "más opciones")

    /** Las etiquetas de botón son cortas; un texto largo es una fila entera tocada, no un botón. */
    const val LARGO_MAX_BOTON = 80

    fun clasificar(etiqueta: String?): String? {
        val t = etiqueta?.lowercase()?.trim().orEmpty()
        if (t.isEmpty() || t.length > LARGO_MAX_BOTON || SIN_ACCION.any { t.startsWith(it) }) return null
        for ((accion, frases) in REGLAS) {
            if (frases.any { contiene(t, it) }) return accion
        }
        return null
    }

    /** Busca la frase como palabras completas: «like» no debe coincidir con «likely». */
    private fun contiene(texto: String, frase: String): Boolean {
        var desde = 0
        while (true) {
            val i = texto.indexOf(frase, desde)
            if (i < 0) return false
            val antes = if (i == 0) ' ' else texto[i - 1]
            val fin = i + frase.length
            val despues = if (fin >= texto.length) ' ' else texto[fin]
            // Si la frase termina en «@» (por ejemplo «silenciar a @»), lo que sigue es el nombre de la cuenta.
            val bordeFinal = !frase.last().isLetterOrDigit() || !despues.isLetterOrDigit()
            if (!antes.isLetterOrDigit() && bordeFinal) return true
            desde = i + 1
        }
    }

    /**
     * En qué pantalla de X estás, fuera del inicio: el perfil de una cuenta, el detalle de un
     * post o el editor de una respuesta. Se reconoce por textos que solo aparecen ahí.
     */
    fun clasificarPantalla(textos: List<String>): String? {
        val t = textos.map { it.lowercase().trim() }
        val respondiendo = t.any { it.startsWith("respondiendo a") || it.startsWith("replying to") }
        if (respondiendo) return RESPONDER
        val esPerfil = t.any { it == "seguidores" || it == "followers" || it.endsWith(" seguidores") || it.endsWith(" followers") }
        if (esPerfil) return PERFIL
        val esPost = t.any {
            it.startsWith("postea tu respuesta") || it.startsWith("publica tu respuesta") ||
                it.startsWith("post your reply") || it == "post" || it.startsWith("respuestas más relevantes")
        }
        return if (esPost) ENTRAR else null
    }
}

/** Avisos de X después de silenciar o bloquear: «Silenciaste a @cuenta». */
object AvisosDeX {
    private val SILENCIO = Regex("""(silenciaste a|muted)\s+(@[A-Za-z0-9_]{1,15})""", RegexOption.IGNORE_CASE)
    private val BLOQUEO = Regex("""(bloqueaste a|blocked)\s+(@[A-Za-z0-9_]{1,15})""", RegexOption.IGNORE_CASE)

    /** Devuelve (acción, cuenta) si alguno de los textos es un aviso de silencio o bloqueo. */
    fun buscar(textos: List<String>): Pair<String, String>? {
        for (t in textos) {
            SILENCIO.find(t)?.let { return "silenciar" to it.groupValues[2] }
            BLOQUEO.find(t)?.let { return "bloquear" to it.groupValues[2] }
        }
        return null
    }
}
