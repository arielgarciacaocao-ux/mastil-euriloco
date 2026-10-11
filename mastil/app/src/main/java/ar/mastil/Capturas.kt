package ar.mastil

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import java.io.File
import java.util.concurrent.Executors

/**
 * Miniaturas de los posts donde te detenés: lo que permite saber de qué trata un post
 * que es casi todo imagen. Solo se toman dentro de X, se guardan chicas (unos 30 KB)
 * y viajan únicamente a Euríloco; después de enviarlas se borran del teléfono.
 *
 * Requiere Android 11 o superior (captura desde el servicio de accesibilidad).
 */
object Capturas {
    private const val ANCHO_MAX = 480
    private const val CALIDAD_JPEG = 60
    private const val MAX_ARCHIVOS = 400
    private val hilo = Executors.newSingleThreadExecutor()

    fun disponible(): Boolean = Build.VERSION.SDK_INT >= 30

    fun carpeta(c: Context): File = File(c.filesDir, "capturas").also { it.mkdirs() }

    /** Saca la captura y guarda solo el recorte del post, con el nombre del evento. */
    fun tomar(servicio: AccessibilityService, idEvento: String, marco: Rect) {
        if (!disponible() || marco.width() <= 0 || marco.height() <= 0) return
        if (carpeta(servicio).list()?.size ?: 0 >= MAX_ARCHIVOS) return
        try {
            servicio.takeScreenshot(Display.DEFAULT_DISPLAY, hilo,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(resultado: AccessibilityService.ScreenshotResult) {
                        try {
                            guardar(servicio, idEvento, resultado, marco)
                        } catch (e: Exception) {
                            // Una captura fallida no detiene nada: el evento se registra igual.
                        } finally {
                            resultado.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(codigo: Int) {}
                })
        } catch (e: Exception) {
        }
    }

    private fun guardar(c: Context, id: String, r: AccessibilityService.ScreenshotResult, marco: Rect) {
        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace) ?: return
        val pantalla = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        val x = marco.left.coerceIn(0, pantalla.width - 1)
        val y = marco.top.coerceIn(0, pantalla.height - 1)
        val w = marco.width().coerceAtMost(pantalla.width - x)
        val h = marco.height().coerceAtMost(pantalla.height - y)
        if (w < 50 || h < 50) {
            pantalla.recycle()
            return
        }
        val recorte = Bitmap.createBitmap(pantalla, x, y, w, h)
        pantalla.recycle()
        val escala = minOf(1.0, ANCHO_MAX.toDouble() / w)
        val chica = if (escala < 1.0) {
            Bitmap.createScaledBitmap(recorte, (w * escala).toInt(), (h * escala).toInt(), true)
        } else recorte
        val destino = File(carpeta(c), "$id.jpg")
        val temporal = File(carpeta(c), "$id.tmp")
        temporal.outputStream().use { chica.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, it) }
        temporal.renameTo(destino)
        if (chica !== recorte) chica.recycle()
        recorte.recycle()
    }

    /** Capturas listas para enviar (las .tmp están a medio escribir). */
    fun pendientes(c: Context): List<File> =
        carpeta(c).listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.lastModified() }.orEmpty()

    /** Borra lo que pasó la semana: si no llegó a Euríloco en ese tiempo, ya no se manda. */
    fun podar(c: Context) {
        val limite = System.currentTimeMillis() - Registro.RETENCION_MS
        carpeta(c).listFiles()?.forEach { if (it.lastModified() < limite) it.delete() }
    }
}
