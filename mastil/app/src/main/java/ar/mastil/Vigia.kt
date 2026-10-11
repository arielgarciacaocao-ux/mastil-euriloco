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
import ar.mastil.Config.eventosAnotados
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
        if (paquete != AdaptadorX.PAQUETE) cerrar()
        else if (SystemClock.elapsedRealtime() - desdeReloj > MAX_PERMANENCIA_MS) cerrar()
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
