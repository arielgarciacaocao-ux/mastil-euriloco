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

    private val HANDLE = Regex("@[A-Za-z0-9_]{1,15}")
    private const val MAX_NODOS = 400
    private const val MAX_TEXTO = 1000

    fun postEnfocado(raiz: AccessibilityNodeInfo): Post? {
        val lista = buscarLista(raiz) ?: return null
        val marco = Rect()
        lista.getBoundsInScreen(marco)
        val medio = marco.centerY()
        for (i in 0 until lista.childCount) {
            val fila = lista.getChild(i) ?: continue
            val r = Rect()
            fila.getBoundsInScreen(r)
            if (medio >= r.top && medio <= r.bottom) {
                return armarPost(fila, r)
            }
        }
        return null
    }

    /** La lista desplazable de mayor superficie: en X, el feed. */
    private fun buscarLista(raiz: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var mejor: AccessibilityNodeInfo? = null
        var mejorArea = 0L
        val cola = ArrayDeque<AccessibilityNodeInfo>()
        cola.add(raiz)
        var vistos = 0
        while (cola.isNotEmpty() && vistos < MAX_NODOS) {
            val n = cola.removeFirst()
            vistos++
            if (n.isScrollable && n.childCount > 1) {
                val clase = n.className?.toString().orEmpty()
                if (clase.contains("RecyclerView") || clase.contains("ListView")) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    val area = r.width().toLong() * r.height().toLong()
                    if (area > mejorArea) {
                        mejor = n
                        mejorArea = area
                    }
                }
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
        while (cola.isNotEmpty() && vistos < MAX_NODOS) {
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
        val texto = textos.distinct().joinToString(" · ").take(MAX_TEXTO)
        if (texto.length < 8) return null
        val cuenta = HANDLE.find(texto)?.value
        return Post(clave = huella((cuenta ?: "") + "|" + texto.take(160)), cuenta = cuenta,
            texto = texto, tieneImagen = imagen)
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
