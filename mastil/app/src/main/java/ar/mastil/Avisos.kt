package ar.mastil

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import ar.mastil.Config.ultimaSincro
import ar.mastil.Config.ultimoAvisoSincro

/** Notificaciones de Mástil. En la etapa 1, solo dos: sincronización pendiente y volcado listo. */
object Avisos {
    private const val CANAL = "mastil"
    private const val ID_SINCRO = 1
    private const val ID_VOLCADO = 2
    private const val DIA_MS = 24L * 3600 * 1000

    /** Avisar cuando pasaron tantos días sin sincronizar, antes de cumplir la semana. */
    const val DIAS_PARA_AVISAR = 5

    fun crearCanal(c: Context) {
        val canal = NotificationChannel(CANAL, "Mástil", NotificationManager.IMPORTANCE_DEFAULT)
        canal.description = "Recordatorios de sincronización con Euríloco"
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    /** Si hay eventos que llevan días sin llegar a Euríloco, avisa una vez por día. */
    fun revisarPendiente(c: Context) {
        val viejo = Registro.masViejo(c)
        if (viejo == 0L) return
        val ahora = System.currentTimeMillis()
        val referencia = maxOf(c.ultimaSincro, viejo)
        if (ahora - referencia < DIAS_PARA_AVISAR * DIA_MS) return
        if (ahora - c.ultimoAvisoSincro < 20 * 3600 * 1000L) return
        val restan = ((viejo + Registro.RETENCION_MS - ahora) / DIA_MS).coerceAtLeast(0)
        mostrar(c, ID_SINCRO, "Sincronización pendiente",
            "Mástil guarda una semana de registros y hace días que no ve a Euríloco. " +
                "Los más viejos se borran en $restan días. Conectate a la red de casa o abrí Mástil y tocá Sincronizar.")
        c.ultimoAvisoSincro = ahora
    }

    fun volcadoListo(c: Context) {
        mostrar(c, ID_VOLCADO, "Volcado de X guardado",
            "Abrí Mástil y tocá «Compartir volcado» para mandarlo.")
    }

    private fun mostrar(c: Context, id: Int, titulo: String, texto: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        crearCanal(c)
        val abrir = PendingIntent.getActivity(
            c, id, Intent(c, PrincipalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CANAL)
            .setSmallIcon(R.drawable.ic_mastil)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
