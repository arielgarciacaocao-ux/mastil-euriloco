package ar.mastil

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

/** Un post tal como Mástil lo lee en pantalla. */
data class Post(
    val clave: String,
    val cuenta: String?,
    val texto: String,
    val tieneImagen: Boolean,
    val anuncio: Boolean = false,
    val medio: String? = null,
    val vistas: Long? = null,
    val verificada: Boolean = false,
    val traducido: Boolean = false,
    val pestana: String? = null,
    /** Dónde está el post en la pantalla, para la miniatura. */
    val marco: Rect? = null,
    val gustado: Boolean = false,
    val reposteado: Boolean = false,
    val guardado: Boolean = false,
    val descartado: Boolean = false,
    val barra: Boolean = false,
)

/**
 * Adaptador de X: sabe encontrar en la pantalla el post que está captando la atención.
 *
 * Criterio de la etapa 1: el feed es la lista desplazable más grande; el post enfocado
 * es la fila de esa lista que cruza la línea media de la pantalla. Es un criterio
 * genérico a propósito: X cambia los nombres internos de su interfaz seguido.
 * Si deja de leer bien, el volcado de pantalla sirve para ajustarlo.
 */
object AdaptadorX {
    const val PAQUETE = "com.twitter.android"

    private const val MAX_NODOS_PANTALLA = 4000
    private const val MAX_NODOS_POST = 600
    private const val MAX_TEXTO = 1000

    /** Qué pasó en la última lectura, en palabras: se muestra en la pantalla de Mástil. */
    @Volatile
    var diagnostico: String = ""
        private set

    /** Todos los posts visibles en la última lectura: sirven para ver cambios de estado en cualquiera. */
    @Volatile
    var visibles: List<Post> = emptyList()
        private set

    fun postEnfocado(raiz: AccessibilityNodeInfo): Post? {
        val lista = buscarLista(raiz)
        if (lista == null) {
            visibles = emptyList()
            diagnostico = "X está abierto, pero no encuentro ninguna lista desplazable en pantalla."
            return null
        }
        val marco = Rect()
        lista.getBoundsInScreen(marco)
        val medio = marco.centerY()
        val clase = lista.className?.toString()?.substringAfterLast('.').orEmpty()
        val pestana = pestanaActual(raiz)
        val leidos = mutableListOf<Post>()
        var enfocado: Post? = null
        var filaCentral = false
        for (i in 0 until lista.childCount) {
            val fila = lista.getChild(i) ?: continue
            val r = Rect()
            fila.getBoundsInScreen(r)
            if (r.bottom < marco.top || r.top > marco.bottom) continue
            val post = armarPost(fila, r)?.copy(pestana = pestana)
            if (post != null) leidos.add(post)
            if (medio >= r.top && medio <= r.bottom) {
                filaCentral = true
                enfocado = post
            }
        }
        visibles = leidos
        diagnostico = when {
            enfocado != null -> "Leyendo bien (lista $clase, ${lista.childCount} filas)."
            filaCentral -> "Encuentro el feed ($clase), pero la fila del centro no tiene texto legible."
            else -> "Encuentro el feed ($clase, ${lista.childCount} filas), pero ninguna cruza el centro de la pantalla."
        }
        return enfocado
    }

    /** El post que contiene un punto de la pantalla: el que recibió un toque. */
    fun postEn(raiz: AccessibilityNodeInfo, x: Int, y: Int): Post? {
        val lista = buscarLista(raiz) ?: return null
        for (i in 0 until lista.childCount) {
            val fila = lista.getChild(i) ?: continue
            val r = Rect()
            fila.getBoundsInScreen(r)
            if (y >= r.top && y <= r.bottom && x >= r.left && x <= r.right) return armarPost(fila, r)
        }
        return null
    }

    /** Textos visibles de la pantalla, para reconocer a qué pantalla se entró. */
    fun textosPantalla(raiz: AccessibilityNodeInfo, maximo: Int = 800): List<String> {
        val textos = mutableListOf<String>()
        val cola = ArrayDeque<AccessibilityNodeInfo>()
        cola.add(raiz)
        var vistos = 0
        while (cola.isNotEmpty() && vistos < maximo) {
            val n = cola.removeFirst()
            vistos++
            n.text?.toString()?.let { textos.add(it) }
            n.contentDescription?.toString()?.let { textos.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { cola.add(it) }
        }
        return textos
    }

    /**
     * La lista desplazable de mayor superficie: en X, el feed.
     * Acepta cualquier contenedor desplazable con varias filas (RecyclerView, ListView
     * o las listas de Jetpack Compose, que se presentan con otros nombres).
     */
    private fun buscarLista(raiz: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var mejor: AccessibilityNodeInfo? = null
        var mejorArea = 0L
        val cola = ArrayDeque<AccessibilityNodeInfo>()
        cola.add(raiz)
        var vistos = 0
        while (cola.isNotEmpty() && vistos < MAX_NODOS_PANTALLA) {
            val n = cola.removeFirst()
            vistos++
            if (n.isScrollable && n.childCount > 1) {
                val r = Rect()
                n.getBoundsInScreen(r)
                // Las listas verticales son más altas que anchas; los carruseles, no.
                if (r.height() > r.width() / 2) {
                    val area = r.width().toLong() * r.height().toLong()
                    if (area > mejorArea) {
                        mejor = n
                        mejorArea = area
                    }
                }
                continue // lo que está dentro del feed son las filas: no hace falta recorrerlas acá
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { cola.add(it) }
        }
        return mejor
    }

    private fun armarPost(fila: AccessibilityNodeInfo, marco: Rect): Post? {
        val textos = mutableListOf<String>()
        var imagen = false
        val cola = ArrayDeque<AccessibilityNodeInfo>()
        cola.add(fila)
        var vistos = 0
        while (cola.isNotEmpty() && vistos < MAX_NODOS_POST) {
            val n = cola.removeFirst()
            vistos++
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { textos.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { textos.add(it) }
            if (!imagen && n.className?.toString().orEmpty().contains("ImageView")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                // Una imagen que ocupa más de la mitad del ancho del post es contenido, no un ícono.
                if (r.width() * 2 > marco.width() && r.height() > 120) imagen = true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { cola.add(it) }
        }
        val limpio = Limpieza.limpiar(textos, MAX_TEXTO)
        if (limpio.propio) return null
        if (limpio.descartado) {
            // La fila es el aviso de «No me interesa»: se devuelve marcada para que el vigía lo note.
            return Post(clave = "descartado", cuenta = null, texto = "", tieneImagen = false,
                marco = Rect(marco), descartado = true)
        }
        if (limpio.texto.length < 8) return null
        return Post(clave = huella(limpio.firma), cuenta = limpio.cuenta, texto = limpio.texto,
            tieneImagen = imagen || limpio.medio != null, anuncio = limpio.anuncio, medio = limpio.medio,
            vistas = limpio.vistas, verificada = limpio.verificada, traducido = limpio.traducido,
            marco = Rect(marco), gustado = limpio.gustado, reposteado = limpio.reposteado,
            guardado = limpio.guardado, barra = limpio.barra)
    }

    private val PESTANAS = mapOf("para ti" to "para ti", "for you" to "para ti",
        "siguiendo" to "siguiendo", "following" to "siguiendo")
    private var pestanaCache: String? = null
    private var pestanaMomento = 0L

    /**
     * En qué pestaña del inicio está el feed: «Para ti» (lo elige el algoritmo) o «Siguiendo»
     * (cuentas que seguís). Se recalcula como mucho cada 2 segundos.
     */
    fun pestanaActual(raiz: AccessibilityNodeInfo): String? {
        val ahora = System.currentTimeMillis()
        if (ahora - pestanaMomento < 2000) return pestanaCache
        pestanaMomento = ahora
        var hallada: String? = null
        var vistaAlguna = false
        val cola = ArrayDeque<AccessibilityNodeInfo>()
        cola.add(raiz)
        var vistos = 0
        while (cola.isNotEmpty() && vistos < 1500 && hallada == null) {
            val n = cola.removeFirst()
            vistos++
            val etiqueta = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
            val pestana = PESTANAS[etiqueta]
            if (pestana != null) {
                vistaAlguna = true
                if (n.isSelected || n.parent?.isSelected == true) hallada = pestana
            }
            if (n.isScrollable && n.childCount > 3) {
                // No recorrer el feed entero (alto); la barra de pestañas (ancha) sí.
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.height() > r.width() / 2) continue
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { cola.add(it) }
        }
        // Con las pestañas a la vista estás en el inicio, aunque X no diga cuál está elegida
        // (al scrollear, la barra se encoge). Sin pestañas, es otra pantalla: perfil, búsqueda, un hilo.
        pestanaCache = hallada ?: if (vistaAlguna) "inicio" else "otra pantalla"
        return pestanaCache
    }

    private fun huella(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Volcado legible de toda la pantalla, para ajustar el adaptador cuando X cambie. */
    fun volcar(raiz: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun recorrer(n: AccessibilityNodeInfo, nivel: Int) {
            if (sb.length > 200_000 || nivel > 40) return
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append("  ".repeat(nivel))
                .append(n.className ?: "?")
            n.viewIdResourceName?.let { sb.append(" id=").append(it) }
            if (n.isScrollable) sb.append(" [desplazable]")
            sb.append(" ").append(r.toShortString())
            n.text?.let { sb.append(" texto=\"").append(it.toString().take(120)).append('"') }
            n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().take(120)).append('"') }
            sb.append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { recorrer(it, nivel + 1) }
        }
        recorrer(raiz, 0)
        return sb.toString()
    }
}
