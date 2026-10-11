package ar.mastil

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.graphics.Rect
import ar.mastil.Config.eventosAnotados
import ar.mastil.Config.ultimaAccion
import ar.mastil.Config.ultimaAccionMomento
import ar.mastil.Config.ultimoContactoX
import ar.mastil.Config.ultimoDiagnostico
import ar.mastil.Config.ultimoPostLeido
import ar.mastil.Config.ultimoPostTexto
import ar.mastil.Config.volcadoPedido
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * El vigía: servicio de accesibilidad que mira X mientras scrolleás.
 *
 * Etapa 1: solo observa. Mide cuánto tiempo queda cada post en el centro de la
 * pantalla (la permanencia, la señal que el algoritmo usa) y lo anota en el registro.
 */
class Vigia : AccessibilityService() {

    private val principal = Handler(Looper.getMainLooper())
    private var fondo: ScheduledExecutorService? = null

    private var actual: Post? = null
    private var actualId = ""        // id del evento en curso: también nombra su miniatura
    private var desdeReloj = 0L      // SystemClock: para medir duración
    private var desdeHora = 0L       // hora real: para registrar cuándo fue
    private var ultimoAnalisis = 0L
    private var analisisPendiente = false
    private var ultimoDiagnosticoGuardado = 0L

    // Acciones: el post al que entraste (y desde cuándo), el último post tocado
    // (para los menús, que se abren en otra ventana) y un registro corto para no duplicar.
    private var detalle: Post? = null
    private var detalleDesde = 0L
    private var ultimoPostTocado: Post? = null
    private var ultimoToque = 0L
    private val recientes = HashMap<String, Long>()

    private val pantallaApagada = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = cerrar()
    }

    private val pulso = object : Runnable {
        override fun run() {
            revisarSalida()
            principal.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Avisos.crearCanal(this)
        val filtro = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pantallaApagada, filtro, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pantallaApagada, filtro)
        }
        principal.post(pulso)
        fondo = Executors.newSingleThreadScheduledExecutor().also { ex ->
            ex.scheduleWithFixedDelay({
                try {
                    Sincro.intentar(this)
                } catch (e: Exception) {
                    // Un fallo de red nunca debe detener al vigía.
                }
            }, 1, 15, TimeUnit.MINUTES)
        }
    }

    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (evento?.packageName?.toString() != AdaptadorX.PAQUETE) return
        if (evento.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            try {
                manejarToque(evento)
            } catch (e: Exception) {
                // Un toque mal leído no debe frenar la lectura del feed.
            }
            return
        }
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimoAnalisis < 250) {
            // Demasiados eventos juntos durante el scroll: un solo análisis diferido alcanza.
            if (!analisisPendiente) {
                analisisPendiente = true
                principal.postDelayed({
                    analisisPendiente = false
                    analizar()
                }, 250)
            }
            return
        }
        analizar()
    }

    private fun analizar() {
        ultimoAnalisis = SystemClock.elapsedRealtime()
        val raiz = rootInActiveWindow
        if (raiz == null) {
            guardarContacto("Llegan eventos de X, pero Android no me deja leer la ventana activa.")
            return
        }
        val paquete = raiz.packageName?.toString()
        if (paquete != AdaptadorX.PAQUETE) {
            guardarContacto("Llegan eventos de X, pero la ventana activa es otra ($paquete).")
            cerrar()
            return
        }
        if (volcadoPedido) {
            volcadoPedido = false
            File(filesDir, "volcado.txt").writeText(AdaptadorX.volcar(raiz))
            Avisos.volcadoListo(this)
        }
        val post = try {
            AdaptadorX.postEnfocado(raiz)
        } catch (e: Exception) {
            null
        }
        anotarDiagnostico(post)
        // De vuelta en el inicio: si habías entrado a un post, ahí termina esa visita.
        if (detalle != null && (post?.pestana == "para ti" || post?.pestana == "siguiendo")) cerrarDetalle()
        if (post?.clave != actual?.clave) {
            cerrar()
            if (post != null) {
                actual = post
                actualId = UUID.randomUUID().toString()
                desdeReloj = SystemClock.elapsedRealtime()
                desdeHora = System.currentTimeMillis()
                programarCaptura(actualId)
            }
        } else if (post != null) {
            actual = post // mismo post: se actualiza su posición y sus contadores
        }
    }

    /**
     * Si seguís en el mismo post después de un rato, se saca su miniatura.
     * Así solo se guardan imágenes de lo que de verdad te detuvo.
     */
    private fun programarCaptura(id: String) {
        if (!Capturas.disponible()) return
        principal.postDelayed({
            val post = actual
            if (post != null && actualId == id) post.marco?.let { Capturas.tomar(this, id, it) }
        }, CAPTURA_TRAS_MS)
    }

    private fun guardarContacto(motivo: String) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoDiagnosticoGuardado < 3000) return
        ultimoDiagnosticoGuardado = ahora
        ultimoContactoX = ahora
        ultimoDiagnostico = motivo
    }

    /** Guarda, como mucho cada 3 segundos, qué está leyendo: lo muestra la pantalla de Mástil. */
    private fun anotarDiagnostico(post: Post?) {
        val ahora = System.currentTimeMillis()
        if (post == null && ahora - ultimoDiagnosticoGuardado < 3000) return
        if (post != null && ahora - ultimoPostLeido < 3000) return
        ultimoDiagnosticoGuardado = ahora
        ultimoContactoX = ahora
        ultimoDiagnostico = AdaptadorX.diagnostico
        if (post != null) {
            ultimoPostLeido = ahora
            ultimoPostTexto = ((post.cuenta?.let { "$it: " } ?: "") + post.texto).take(140)
        }
    }

    /** Si X dejó de estar en primer plano, el post en curso termina. */
    private fun revisarSalida() {
        if (actual == null) return
        val paquete = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        if (paquete != AdaptadorX.PAQUETE) {
            cerrar()
            cerrarDetalle()
        } else if (SystemClock.elapsedRealtime() - desdeReloj > MAX_PERMANENCIA_MS) cerrar()
    }

    // ------------------------------------------------------------ acciones

    /** Un toque dentro de X: si fue un botón conocido, es una acción; si no, puede ser entrar a algo. */
    private fun manejarToque(ev: AccessibilityEvent) {
        val fuente = ev.source
        val etiqueta = listOf(
            ev.contentDescription?.toString(),
            fuente?.contentDescription?.toString(),
            ev.text?.joinToString(" "),
            fuente?.text?.toString(),
        ).firstOrNull { !it.isNullOrBlank() }
        val raiz = rootInActiveWindow
        var post: Post? = null
        if (fuente != null && raiz != null && raiz.packageName?.toString() == AdaptadorX.PAQUETE) {
            val r = Rect()
            fuente.getBoundsInScreen(r)
            post = AdaptadorX.postEn(raiz, r.centerX(), r.centerY())
        }
        val ahora = SystemClock.elapsedRealtime()
        if (post != null) {
            ultimoPostTocado = post
            ultimoToque = ahora
        }
        // Los menús («No me interesa», «Silenciar») se abren en otra ventana: se atribuyen
        // al último post tocado, si fue hace poco.
        val reciente = if (ahora - ultimoToque < 30_000) ultimoPostTocado else null
        val objetivo = post ?: reciente ?: actual
        val accion = Acciones.clasificar(etiqueta)
        if (accion != null) {
            registrarAccion(accion, objetivo, null)
            return
        }
        if (objetivo != null) principal.postDelayed({ verificarPantalla(objetivo) }, 1200)
    }

    /** Un rato después de un toque sin botón: ¿se abrió el detalle de un post o un perfil? */
    private fun verificarPantalla(objetivo: Post) {
        val raiz = rootInActiveWindow ?: return
        if (raiz.packageName?.toString() != AdaptadorX.PAQUETE) return
        when (Acciones.clasificarPantalla(AdaptadorX.textosPantalla(raiz))) {
            Acciones.PERFIL -> registrarAccion(Acciones.PERFIL, objetivo, null)
            Acciones.ENTRAR -> {
                cerrarDetalle()
                detalle = objetivo
                detalleDesde = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Anota la visita a un post con cuánto duró: entrar y quedarse es de las señales más fuertes. */
    private fun cerrarDetalle() {
        val post = detalle ?: return
        detalle = null
        val duracion = (SystemClock.elapsedRealtime() - detalleDesde).coerceAtMost(MAX_PERMANENCIA_MS)
        registrarAccion(Acciones.ENTRAR, post, duracion)
    }

    private fun registrarAccion(accion: String, post: Post?, duracion: Long?) {
        val ahora = SystemClock.elapsedRealtime()
        val llave = accion + "|" + (post?.clave ?: "")
        // El mismo botón dos veces seguidas (o el menú de repost) cuenta una sola vez.
        if (ahora - (recientes[llave] ?: 0L) < 5000) return
        recientes[llave] = ahora
        if (recientes.size > 200) recientes.clear()
        val registro = JSONObject()
            .put("tipo", "accion")
            .put("id", UUID.randomUUID().toString())
            .put("momento", System.currentTimeMillis())
            .put("accion", accion)
            .put("cuenta", post?.cuenta ?: JSONObject.NULL)
            .put("clave", post?.clave ?: JSONObject.NULL)
            .put("texto", post?.texto?.take(300) ?: JSONObject.NULL)
            .put("pestana", post?.pestana ?: JSONObject.NULL)
            .put("duracion_ms", duracion ?: JSONObject.NULL)
        try {
            Registro.agregar(this, registro)
            ultimaAccion = accion + (post?.cuenta?.let { " · $it" } ?: "")
            ultimaAccionMomento = System.currentTimeMillis()
        } catch (e: Exception) {
        }
    }

    /** Cierra el post en curso y, si duró lo suficiente, lo anota. */
    private fun cerrar() {
        val post = actual ?: return
        actual = null
        val duracion = (SystemClock.elapsedRealtime() - desdeReloj).coerceAtMost(MAX_PERMANENCIA_MS)
        if (duracion < MIN_PERMANENCIA_MS) return
        val evento = JSONObject()
            .put("id", actualId)
            .put("red", "x")
            .put("inicio", desdeHora)
            .put("fin", desdeHora + duracion)
            .put("permanencia_ms", duracion)
            .put("cuenta", post.cuenta ?: JSONObject.NULL)
            .put("texto", post.texto)
            .put("tiene_imagen", post.tieneImagen)
            .put("anuncio", post.anuncio)
            .put("medio", post.medio ?: JSONObject.NULL)
            .put("vistas", post.vistas ?: JSONObject.NULL)
            .put("verificada", post.verificada)
            .put("traducido", post.traducido)
            .put("pestana", post.pestana ?: JSONObject.NULL)
            .put("clave", post.clave)
        try {
            Registro.agregar(this, evento)
            eventosAnotados += 1
        } catch (e: Exception) {
            // Sin espacio o archivo ocupado: se pierde este evento, no el servicio.
        }
    }

    override fun onInterrupt() {
        cerrar()
    }

    override fun onDestroy() {
        cerrar()
        cerrarDetalle()
        principal.removeCallbacksAndMessages(null)
        fondo?.shutdownNow()
        try {
            unregisterReceiver(pantallaApagada)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        /** Menos que esto es pasar de largo durante el scroll: no se anota. */
        const val MIN_PERMANENCIA_MS = 300L
        /** Más que esto, probablemente el teléfono quedó quieto sobre la mesa. */
        const val MAX_PERMANENCIA_MS = 10L * 60 * 1000
        /** A partir de cuánto tiempo en un mismo post se saca su miniatura. */
        const val CAPTURA_TRAS_MS = 2500L
    }
}
