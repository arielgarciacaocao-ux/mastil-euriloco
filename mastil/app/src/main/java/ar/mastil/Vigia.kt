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
import android.content.SharedPreferences
import ar.mastil.Config.avisosDados
import ar.mastil.Config.eventosAnotados
import ar.mastil.Config.saltosHechos
import ar.mastil.Config.ultimaIntervencion
import ar.mastil.Config.ultimaIntervencionMomento
import ar.mastil.Config.ultimaPantalla
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

    // Detección por estado (X está hecho con Compose y no avisa los toques): se recuerda cómo
    // estaban los botones de cada post y en qué pantalla estabas, y se anotan los cambios.
    private val estados = LinkedHashMap<String, Int>()
    private var anteriores: List<Post> = emptyList()
    private var visiblesInicio: List<Post> = emptyList()
    private var pantalla = "inicio"
    private var ultimaRevisionPantalla = 0L

    // La veda: las cuerdas vigentes y lo que se hizo con el post en curso.
    private var juego = Cuerdas.VACIO
    private var juegoModificado = 0L
    private var avisador: Avisador? = null
    private var actualCuerda: String? = null
    private var intervencion: String? = null
    private var avisoMomento = 0L

    // El post que está en el centro, aunque por un instante el centro caiga entre dos posts.
    // Un aviso o un salto programado se ejecuta si ese post sigue ahí, sin cancelarse por el parpadeo.
    private var ultimaClave: String? = null
    private val yaActuados = LinkedHashMap<String, Long>()

    /** Escucha el botón «Probar aviso» de la pantalla de Mástil. */
    private val oyente = SharedPreferences.OnSharedPreferenceChangeListener { _, clave ->
        if (clave == Config.PRUEBA_AVISO) principal.post {
            val v = cuerdasVigentes()
            val formas = if (v.subcategorias.isEmpty()) listOf("visual", "vibracion", "ambos") else v.formas
            avisador?.avisar(formas, v.intensidad)
        }
    }

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
        avisador = Avisador(this)
        Config.prefs(this).registerOnSharedPreferenceChangeListener(oyente)
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
        val leido = try {
            AdaptadorX.postEnfocado(raiz)
        } catch (e: Exception) {
            null
        }
        // El aviso de «No me interesa» ocupa una fila, pero no es un post.
        val post = leido?.takeIf { !it.descartado }
        if (post != null) ultimaClave = post.clave
        try {
            compararEstados(AdaptadorX.visibles)
            revisarPantalla(raiz, post)
        } catch (e: Exception) {
            // Las acciones son un extra: si algo falla acá, la permanencia se sigue midiendo.
        }
        anotarDiagnostico(post)
        // De vuelta en el inicio: si habías entrado a un post, ahí termina esa visita.
        if (detalle != null && post?.pestana in INICIO) cerrarDetalle()
        if (post?.clave != actual?.clave) {
            cerrar()
            if (post != null) {
                actual = post
                actualId = UUID.randomUUID().toString()
                desdeReloj = SystemClock.elapsedRealtime()
                desdeHora = System.currentTimeMillis()
                programarCaptura(actualId)
                try {
                    aplicarCuerdas(post, actualId)
                } catch (e: Exception) {
                    // Una cuerda que falla no debe frenar la lectura.
                }
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

    // ------------------------------------------------------------ veda

    /** Relee las cuerdas si Euríloco mandó una versión nueva. */
    private fun cuerdasVigentes(): Cuerdas.Juego {
        val f = File(filesDir, "cuerdas.json")
        val modificado = if (f.exists()) f.lastModified() else 0L
        if (modificado != juegoModificado) {
            juegoModificado = modificado
            juego = try {
                if (modificado == 0L) Cuerdas.VACIO else Cuerdas.desdeJson(f.readText())
            } catch (e: Exception) {
                Cuerdas.VACIO
            }
        }
        return juego
    }

    /**
     * Un post nuevo llegó al centro: si una cuerda lo reconoce, se programa su acción.
     * Se espera un instante para no reaccionar a lo que pasa de largo en pleno scroll.
     * Actúa en todo X (inicio, respuestas, hilos, listas, perfiles) salvo mientras escribís.
     */
    private fun aplicarCuerdas(post: Post, id: String) {
        if (ultimaPantalla != (post.pestana ?: "")) ultimaPantalla = post.pestana ?: ""
        if (pantalla == "responder") return
        val vigente = cuerdasVigentes()
        if (vigente.subcategorias.isEmpty()) return
        val (cuerda, accion) = Cuerdas.resolver(vigente, post.texto, post.cuenta, post.anuncio)
        actualCuerda = cuerda
        if (accion != "salto" && accion != "aviso") return
        // Si el mismo post vuelve al centro enseguida (por ejemplo, después de un parpadeo de
        // la lectura), no se repite la acción.
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - (yaActuados[post.clave] ?: 0L) < 30_000) return
        val clave = post.clave
        when (accion) {
            "salto" -> principal.postDelayed({
                if (ultimaClave == clave && SystemClock.elapsedRealtime() - (yaActuados[clave] ?: 0L) > 30_000) {
                    yaActuados[clave] = SystemClock.elapsedRealtime()
                    intervencion = "salto"
                    actualCuerda = cuerda
                    anotarIntervencion("salto", cuerda, post)
                    saltosHechos += 1
                    saltar(actual ?: post)
                }
            }, SALTO_TRAS_MS)
            "aviso" -> principal.postDelayed({
                if (ultimaClave == clave && SystemClock.elapsedRealtime() - (yaActuados[clave] ?: 0L) > 30_000) {
                    yaActuados[clave] = SystemClock.elapsedRealtime()
                    intervencion = "aviso"
                    actualCuerda = cuerda
                    anotarIntervencion("aviso", cuerda, post)
                    avisosDados += 1
                    avisoMomento = SystemClock.elapsedRealtime()
                    avisador?.avisar(vigente.formas, vigente.intensidad)
                    // Segundo aviso, de la misma intensidad, si seguís en el mismo post.
                    principal.postDelayed({
                        if (ultimaClave == clave) avisador?.avisar(vigente.formas, vigente.intensidad)
                    }, SEGUNDO_AVISO_MS)
                }
            }, AVISO_TRAS_MS)
        }
        while (yaActuados.size > 200) yaActuados.remove(yaActuados.keys.first())
    }

    private fun anotarIntervencion(accion: String, cuerda: String?, post: Post) {
        ultimaIntervencion = "$accion por «${cuerda ?: "?"}»" + (post.cuenta?.let { " en $it" } ?: "")
        ultimaIntervencionMomento = System.currentTimeMillis()
    }

    /** Después de un aviso: ¿pasaste de largo enseguida o te quedaste? */
    private fun respuestaAlAviso(): String? {
        if (intervencion != "aviso") return null
        val despues = SystemClock.elapsedRealtime() - avisoMomento
        return if (despues < QUEDARSE_MS) "paso" else "quedo"
    }

    /** Avanza el feed lo justo para que el post vedado salga del centro: un deslizamiento lento. */
    private fun saltar(post: Post) {
        val marco = post.marco ?: return
        val pantalla = resources.displayMetrics
        val alto = pantalla.heightPixels.toFloat()
        val x = pantalla.widthPixels * 0.5f
        val distancia = (marco.bottom - alto / 2 + 80).coerceIn(alto * 0.15f, alto * 0.6f)
        val desde = alto * 0.8f
        val camino = android.graphics.Path().apply {
            moveTo(x, desde)
            lineTo(x, desde - distancia)
        }
        val gesto = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(camino, 0, 450))
            .build()
        dispatchGesture(gesto, null, null)
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
        // Me gusta, repost y guardar se leen por el cambio de estado del botón, que es seguro;
        // el aviso del toque puede llegar con el nombre ya cambiado y confundir la dirección.
        if (accion in POR_ESTADO) return
        if (accion != null) {
            registrarAccion(accion, objetivo, null)
            return
        }
    }

    /**
     * Compara los botones de cada post visible con cómo estaban antes: si «Me gusta» pasó a
     * «Deshacer Me gusta», diste me gusta. Funciona aunque X no avise los toques.
     */
    private fun compararEstados(visibles: List<Post>) {
        val actuales = visibles.filter { !it.descartado }
        for (p in actuales) {
            if (!p.barra) continue // botones fuera de pantalla: el estado no se puede leer
            val bits = (if (p.gustado) 1 else 0) or (if (p.reposteado) 2 else 0) or (if (p.guardado) 4 else 0)
            val antes = estados[p.clave]
            if (antes != null && antes != bits) {
                cambio(antes, bits, 1, "me gusta", "quitar me gusta", p)
                cambio(antes, bits, 2, "repostear", "deshacer repost", p)
                cambio(antes, bits, 4, "guardar", "quitar guardado", p)
            }
            estados.remove(p.clave)
            estados[p.clave] = bits
        }
        while (estados.size > 500) estados.remove(estados.keys.first())
        // Una fila nueva con el aviso de «No me interesa»: el post que estaba en ese lugar se descartó.
        val claves = actuales.map { it.clave }.toSet()
        for (d in visibles.filter { it.descartado }) {
            val lugar = d.marco ?: continue
            val descartado = anteriores
                .filter { !it.descartado && it.clave !in claves && it.marco != null }
                .minByOrNull { Math.abs(it.marco!!.top - lugar.top) }
            if (descartado != null && Math.abs(descartado.marco!!.top - lugar.top) < 400) {
                registrarAccion("no me interesa", descartado, null)
            }
        }
        anteriores = visibles
    }

    private fun cambio(antes: Int, ahora: Int, bit: Int, si: String, no: String, p: Post) {
        val era = antes and bit != 0
        val es = ahora and bit != 0
        if (!era && es) registrarAccion(si, p, null)
        if (era && !es) registrarAccion(no, p, null)
    }

    /**
     * En qué pantalla de X estás: el inicio, el detalle de un post, un perfil o el editor de una
     * respuesta. Entrar a un post o a un perfil son señales fuertes; se detectan por el cambio.
     */
    private fun revisarPantalla(raiz: android.view.accessibility.AccessibilityNodeInfo, post: Post?) {
        val ahora = SystemClock.elapsedRealtime()
        val pestana = post?.pestana ?: AdaptadorX.pestanaActual(raiz)
        val enInicio = pestana in INICIO
        if (enInicio) {
            visiblesInicio = AdaptadorX.visibles.filter { !it.descartado }
            if (pantalla != "inicio") {
                cerrarDetalle()
                pantalla = "inicio"
            }
        }
        // Leer los textos de toda la pantalla cuesta: como mucho una vez por segundo.
        if (ahora - ultimaRevisionPantalla < 1000) return
        ultimaRevisionPantalla = ahora
        val textos = AdaptadorX.textosPantalla(raiz, 600)
        AvisosDeX.buscar(textos)?.let { (accion, cuenta) ->
            registrarAccion(accion, visiblesInicio.firstOrNull { it.cuenta == cuenta } ?: soloCuenta(cuenta), null)
        }
        if (enInicio) return
        when (Acciones.clasificarPantalla(textos)) {
            Acciones.ENTRAR -> {
                val principal = elegirDetalle()
                if (detalle == null || principal?.clave != detalle?.clave) {
                    cerrarDetalle()
                    detalle = principal
                    detalleDesde = ahora
                }
                pantalla = "post"
            }
            Acciones.PERFIL -> {
                if (pantalla != "perfil") {
                    cerrarDetalle()
                    val cuenta = textos.firstNotNullOfOrNull { HANDLE.matchEntire(it.trim())?.value }
                    if (cuenta != null) {
                        registrarAccion(Acciones.PERFIL,
                            visiblesInicio.firstOrNull { it.cuenta == cuenta } ?: soloCuenta(cuenta), null)
                    }
                }
                pantalla = "perfil"
            }
            Acciones.RESPONDER -> {
                if (pantalla != "responder") {
                    val cuenta = textos.firstNotNullOfOrNull { t ->
                        if (t.lowercase().startsWith("respondiendo a") || t.lowercase().startsWith("replying to"))
                            HANDLE.find(t)?.value else null
                    }
                    val objetivo = detalle ?: cuenta?.let { c -> visiblesInicio.firstOrNull { it.cuenta == c } }
                        ?: cuenta?.let { soloCuenta(it) }
                    registrarAccion(Acciones.RESPONDER, objetivo, null)
                }
                pantalla = "responder"
            }
            else -> if (pantalla != "post" && pantalla != "responder") pantalla = "otra"
        }
    }

    /** El post al que entraste: el que también estaba en el inicio, o el primero del detalle. */
    private fun elegirDetalle(): Post? {
        val enDetalle = AdaptadorX.visibles.filter { !it.descartado }
        val claves = visiblesInicio.map { it.clave }.toSet()
        return enDetalle.firstOrNull { it.clave in claves } ?: enDetalle.firstOrNull()
    }

    private fun soloCuenta(cuenta: String) =
        Post(clave = "cuenta|$cuenta", cuenta = cuenta, texto = "", tieneImagen = false)

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
            .put("cuerda", actualCuerda ?: JSONObject.NULL)
            .put("intervencion", intervencion ?: JSONObject.NULL)
            .put("respuesta", respuestaAlAviso() ?: JSONObject.NULL)
        actualCuerda = null
        intervencion = null
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
        try {
            Config.prefs(this).unregisterOnSharedPreferenceChangeListener(oyente)
        } catch (e: Exception) {
        }
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
        private val HANDLE = Regex("@[A-Za-z0-9_]{1,15}")
        private val POR_ESTADO = setOf("me gusta", "quitar me gusta", "repostear", "deshacer repost",
            "guardar", "quitar guardado")
        /** Menos que esto es pasar de largo durante el scroll: no se anota. */
        const val MIN_PERMANENCIA_MS = 300L
        /** Más que esto, probablemente el teléfono quedó quieto sobre la mesa. */
        const val MAX_PERMANENCIA_MS = 10L * 60 * 1000
        private val INICIO = setOf("para ti", "siguiendo", "inicio")
        /** Esperas antes de actuar, para no reaccionar a lo que pasa de largo en pleno scroll. */
        const val SALTO_TRAS_MS = 450L
        const val AVISO_TRAS_MS = 500L
        const val SEGUNDO_AVISO_MS = 4000L
        /** Si después del aviso seguís más que esto en el post, te quedaste. */
        const val QUEDARSE_MS = 2500L
        /** A partir de cuánto tiempo en un mismo post se saca su miniatura. */
        const val CAPTURA_TRAS_MS = 2500L
    }
}
