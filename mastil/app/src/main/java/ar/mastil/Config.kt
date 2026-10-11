package ar.mastil

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.UUID

/** Preferencias de Mástil: con qué Euríloco está emparejado y el estado de la sincronización. */
object Config {
    private fun prefs(c: Context): SharedPreferences =
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

    var Context.cuerdasVersion: String
        get() = prefs(this).getString("cuerdas_version", "") ?: ""
        set(v) = prefs(this).edit().putString("cuerdas_version", v).apply()
}
