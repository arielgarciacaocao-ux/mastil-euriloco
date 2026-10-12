package ar.mastil

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import kotlin.random.Random

/**
 * Los avisos de Mástil: un destello amarillo en el borde de la pantalla, una vibración suave
 * o los dos. Nunca tapan el contenido ni reciben toques. Para que no se vuelvan invisibles
 * con la costumbre, varían: la forma, el borde, la cantidad de destellos y el tono, sin
 * repetir dos veces seguidas lo mismo. La intensidad nunca sube entre un aviso y el siguiente.
 */
class Avisador(private val servicio: AccessibilityService) {

    private val bordes = listOf("izquierda", "derecha", "arriba", "abajo", "todos")
    private val tonos = listOf("#FFD54A", "#F2C94C", "#FFE082", "#F6C343")
    private var ultimaForma = ""
    private var ultimoBorde = ""

    /** Da un aviso con las formas permitidas y devuelve cuál usó. */
    fun avisar(formas: List<String>, intensidad: Double): String {
        val opciones = formas.ifEmpty { listOf("visual") }
        val forma = elegir(opciones, ultimaForma)
        ultimaForma = forma
        val destellos = if (Random.nextBoolean()) 1 else 2
        if (forma == "visual" || forma == "ambos") destellar(intensidad, destellos)
        if (forma == "vibracion" || forma == "ambos") vibrar(intensidad, destellos)
        return forma
    }

    private fun <T> elegir(opciones: List<T>, evitar: T): T {
        val posibles = if (opciones.size > 1) opciones.filter { it != evitar } else opciones
        return posibles[Random.nextInt(posibles.size)]
    }

    private fun destellar(intensidad: Double, veces: Int) {
        val wm = servicio.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val borde = elegir(bordes, ultimoBorde)
        ultimoBorde = borde
        val densidad = servicio.resources.displayMetrics.density
        val grosor = (10 + 14 * intensidad) * densidad
        val vista = Destello(servicio, borde, Color.parseColor(tonos[Random.nextInt(tonos.size)]), grosor.toFloat())
        vista.alpha = 0f
        val parametros = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(vista, parametros)
        } catch (e: Exception) {
            return
        }
        val tope = (0.35 + 0.5 * intensidad).toFloat().coerceAtMost(0.85f)
        val animacion = ValueAnimator.ofFloat(0f, tope, 0f)
        animacion.duration = 450L
        animacion.repeatCount = veces - 1
        animacion.addUpdateListener { vista.alpha = it.animatedValue as Float }
        animacion.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                try {
                    wm.removeView(vista)
                } catch (e: Exception) {
                }
            }
        })
        animacion.start()
    }

    private fun vibrar(intensidad: Double, veces: Int) {
        val vibrador: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (servicio.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            servicio.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrador.hasVibrator()) return
        val amplitud = (40 + 110 * intensidad).toInt().coerceIn(1, 255)
        val efecto = if (veces == 1) {
            VibrationEffect.createOneShot(35, amplitud)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 30, 90, 30), intArrayOf(0, amplitud, 0, amplitud), -1)
        }
        vibrador.vibrate(efecto)
    }

    /** Una franja de color en uno o todos los bordes; el centro queda libre. */
    private class Destello(c: Context, val borde: String, color: Int, val grosor: Float) : View(c) {
        private val pintura = Paint().apply { this.color = color; style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (borde == "izquierda" || borde == "todos") canvas.drawRect(0f, 0f, grosor, h, pintura)
            if (borde == "derecha" || borde == "todos") canvas.drawRect(w - grosor, 0f, w, h, pintura)
            if (borde == "arriba" || borde == "todos") canvas.drawRect(0f, 0f, w, grosor, pintura)
            if (borde == "abajo" || borde == "todos") canvas.drawRect(0f, h - grosor, w, h, pintura)
        }
    }
}
