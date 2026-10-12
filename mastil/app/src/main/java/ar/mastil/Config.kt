package ar.mastil

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.UUID

/** Preferencias de Mástil: con qué Euríloco está emparejado y el estado de la sincronización. */
object Config {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("mastil", Context.MODE_PRIVATE)

    /** Direcciones de Euríloco ("ip:puerto"), en el orden en que se prueban. */
    fun hosts(c: Context): List<String> =
        (prefs(c).getString("hosts", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun clave(c: Context): String = prefs(c).getString("clave", "") ?: ""

    fun emparejado(c: Context): Boolean = hosts(c).isNotEmpty() && clave(c).isNotEmpty()

    /** Identificador anónimo de este teléfono, generado una vez. */
    fun dispositivo(c: Context): String {
        val p = prefs(c)
        p.getString("dispositivo", null)?.let { return it }
        val nuevo = UUID.randomUUID().toString()
        p.edit().putString("dispositivo", nuevo).apply()
        return nuevo
    }

    /**
     * Lee el enlace del panel: mastil://emparejar?h=192.168.0.10:8737,100.64.1.2:8737&t=CLAVE
     * Devuelve true si el enlace era válido.
     */
    fun emparejar(c: Context, enlace: Uri): Boolean {
        if (enlace.scheme != "mastil" || enlace.host != "emparejar") return false
        val hosts = enlace.getQueryParameter("h")?.trim().orEmpty()
        val clave = enlace.getQueryParameter("t")?.trim().orEmpty()
        if (hosts.isEmpty() || clave.isEmpty()) return false
        prefs(c).edit().putString("hosts", hosts).putString("clave", clave).apply()
        return true
    }

    var Context.ultimaSincro: Long
        get() = prefs(this).getLong("ultima_sincro", 0L)
        set(v) = prefs(this).edit().putLong("ultima_sincro", v).apply()

    var Context.ultimoAvisoSincro: Long
        get() = prefs(this).getLong("ultimo_aviso_sincro", 0L)
        set(v) = prefs(this).edit().putLong("ultimo_aviso_sincro", v).apply()

    var Context.volcadoPedido: Boolean
        get() = prefs(this).getBoolean("volcado_pedido", false)
        set(v) = prefs(this).edit().putBoolean("volcado_pedido", v).apply()

    // ---- diagnóstico: qué está viendo el vigía

    /** Última vez que llegó un evento de pantalla de X. */
    var Context.ultimoContactoX: Long
        get() = prefs(this).getLong("ultimo_contacto_x", 0L)
        set(v) = prefs(this).edit().putLong("ultimo_contacto_x", v).apply()

    /** Última vez que se reconoció un post en el centro de la pantalla. */
    var Context.ultimoPostLeido: Long
        get() = prefs(this).getLong("ultimo_post_leido", 0L)
        set(v) = prefs(this).edit().putLong("ultimo_post_leido", v).apply()

    var Context.ultimoPostTexto: String
        get() = prefs(this).getString("ultimo_post_texto", "") ?: ""
        set(v) = prefs(this).edit().putString("ultimo_post_texto", v).apply()

    /** Qué encontró el lector la última vez que X estaba en pantalla. */
    var Context.ultimoDiagnostico: String
        get() = prefs(this).getString("ultimo_diagnostico", "") ?: ""
        set(v) = prefs(this).edit().putString("ultimo_diagnostico", v).apply()

    var Context.ultimaAccion: String
        get() = prefs(this).getString("ultima_accion", "") ?: ""
        set(v) = prefs(this).edit().putString("ultima_accion", v).apply()

    var Context.ultimaAccionMomento: Long
        get() = prefs(this).getLong("ultima_accion_momento", 0L)
        set(v) = prefs(this).edit().putLong("ultima_accion_momento", v).apply()

    var Context.eventosAnotados: Long
        get() = prefs(this).getLong("eventos_anotados", 0L)
        set(v) = prefs(this).edit().putLong("eventos_anotados", v).apply()

    /** Última vez que Mástil actuó por una cuerda, en palabras, y cuándo. */
    var Context.ultimaIntervencion: String
        get() = prefs(this).getString("ultima_intervencion", "") ?: ""
        set(v) = prefs(this).edit().putString("ultima_intervencion", v).apply()

    var Context.ultimaIntervencionMomento: Long
        get() = prefs(this).getLong("ultima_intervencion_momento", 0L)
        set(v) = prefs(this).edit().putLong("ultima_intervencion_momento", v).apply()

    /** En qué pantalla de X cree Mástil que estás (inicio u otra). */
    var Context.ultimaPantalla: String
        get() = prefs(this).getString("ultima_pantalla", "") ?: ""
        set(v) = prefs(this).edit().putString("ultima_pantalla", v).apply()

    /** El botón «Probar aviso» escribe acá; el vigía lo escucha y da un aviso. */
    const val PRUEBA_AVISO = "prueba_aviso"

    var Context.cuerdasVersion: String
        get() = prefs(this).getString("cuerdas_version", "") ?: ""
        set(v) = prefs(this).edit().putString("cuerdas_version", v).apply()
}
