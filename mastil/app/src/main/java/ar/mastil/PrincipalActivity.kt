package ar.mastil

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ar.mastil.Config.avisosDados
import ar.mastil.Config.eventosAnotados
import ar.mastil.Config.saltosHechos
import ar.mastil.Config.ultimaIntervencion
import ar.mastil.Config.ultimaIntervencionMomento
import ar.mastil.Config.ultimaPantalla
import ar.mastil.Config.ultimaAccion
import ar.mastil.Config.ultimaAccionMomento
import ar.mastil.Config.ultimaSincro
import ar.mastil.Config.ultimoContactoX
import ar.mastil.Config.ultimoDiagnostico
import ar.mastil.Config.ultimoPostLeido
import ar.mastil.Config.ultimoPostTexto
import ar.mastil.Config.volcadoPedido
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Pantalla de Mástil: estado, emparejamiento, sincronización y volcado. */
class PrincipalActivity : Activity() {

    private lateinit var estado: TextView
    private lateinit var resultado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Avisos.crearCanal(this)
        setContentView(armarPantalla())
        procesarEnlace(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        procesarEnlace(intent)
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun procesarEnlace(i: Intent?) {
        val datos = i?.data ?: return
        emparejarCon(datos)
    }

    private fun emparejarCon(enlace: Uri) {
        if (Config.emparejar(this, enlace)) {
            avisar("Emparejado con Euríloco. Probando la conexión…")
            sincronizar()
        } else {
            avisar("Ese enlace no es de Euríloco. Copialo completo desde el panel.")
        }
        refrescar()
    }

    private fun vigiaActivo(): Boolean {
        val activos = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val propio = ComponentName(this, Vigia::class.java)
        return activos.split(':').any { ComponentName.unflattenFromString(it) == propio }
    }

    private fun refrescar() {
        val fecha = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val sincro = if (ultimaSincro == 0L) "nunca" else fecha.format(Date(ultimaSincro))
        estado.text = buildString {
            append(if (vigiaActivo()) "● Mástil está mirando X.\n" else "○ Mástil está apagado: activalo en Accesibilidad.\n")
            append(
                if (Config.emparejado(this@PrincipalActivity)) "● Emparejado con Euríloco (${Config.hosts(this@PrincipalActivity).joinToString(", ")}).\n"
                else "○ Todavía sin emparejar con Euríloco.\n"
            )
            append("Eventos guardados en el teléfono: ${Registro.cantidad(this@PrincipalActivity)}\n")
            append("Eventos anotados desde que se instaló: $eventosAnotados\n")
            append("Última sincronización: $sincro\n")
            val cuerdas = try {
                val f = File(filesDir, "cuerdas.json")
                if (f.exists()) Cuerdas.desdeJson(f.readText()).subcategorias else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            append("Cuerdas activas: ${cuerdas.count { it.accion == "aviso" }} con aviso, " +
                "${cuerdas.count { it.accion == "salto" }} con salto, ${cuerdas.count { it.accion == "permitir" }} permisos\n")
            val conAviso = cuerdas.filter { it.accion == "aviso" }.joinToString(", ") { it.id }
            append("Con aviso: ${conAviso.ifEmpty { "ninguna" }}\n")
            append("Avisos dados: $avisosDados · saltos hechos: $saltosHechos\n\n")
            append("Diagnóstico de lectura\n")
            if (ultimoContactoX == 0L) {
                append("○ Mástil todavía no recibió nada de la app de X. Abrí X (la app, no el navegador) y scrolleá un poco.")
            } else {
                append("Último contacto con X: ${hace(ultimoContactoX)}\n")
                append("Último post leído: ${if (ultimoPostLeido == 0L) "ninguno todavía" else hace(ultimoPostLeido)}\n")
                if (ultimoPostTexto.isNotEmpty()) append("«$ultimoPostTexto»\n")
                append("Lector: $ultimoDiagnostico\n")
                append("Última acción tuya: ${if (ultimaAccionMomento == 0L) "ninguna registrada todavía" else "$ultimaAccion (${hace(ultimaAccionMomento)})"}\n")
                append("Pantalla de X según Mástil: ${ultimaPantalla.ifEmpty { "sin dato" }}\n")
                append("Última intervención de la veda: ${if (ultimaIntervencionMomento == 0L) "ninguna todavía" else "$ultimaIntervencion (${hace(ultimaIntervencionMomento)})"}")
            }
        }
    }

    private fun hace(momento: Long): String {
        val seg = (System.currentTimeMillis() - momento) / 1000
        return when {
            seg < 60 -> "hace $seg s"
            seg < 3600 -> "hace ${seg / 60} min"
            seg < 86400 -> "hace ${seg / 3600} h"
            else -> "hace ${seg / 86400} días"
        }
    }

    private fun sincronizar() {
        resultado.text = "Sincronizando…"
        Thread {
            val mensaje = try {
                Sincro.intentar(this)
            } catch (e: Exception) {
                "Error inesperado: ${e.message}"
            }
            runOnUiThread {
                resultado.text = mensaje
                refrescar()
            }
        }.start()
    }

    private fun compartirVolcado() {
        val f = File(filesDir, "volcado.txt")
        if (!f.exists()) {
            avisar("Todavía no hay volcado. Tocá «Pedir volcado», abrí X y volvé.")
            return
        }
        val envio = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Volcado de pantalla de X (Mástil)")
            .putExtra(Intent.EXTRA_TEXT, f.readText().take(150_000))
        startActivity(Intent.createChooser(envio, "Compartir volcado"))
    }

    private fun avisar(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------- interfaz

    private fun armarPantalla(): View {
        val d = resources.displayMetrics.density
        val margen = (16 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margen, margen, margen, margen * 2)
        }

        fun titulo(t: String) = TextView(this).apply {
            text = t
            textSize = 18f
            setPadding(0, margen, 0, margen / 4)
            paint.isFakeBoldText = true
        }.also { col.addView(it) }

        fun parrafo(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, margen / 4, 0, margen / 4)
        }.also { col.addView(it) }

        fun boton(t: String, accion: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { accion() }
        }.also { col.addView(it) }

        TextView(this).apply {
            text = "Mástil"
            textSize = 28f
            paint.isFakeBoldText = true
        }.also { col.addView(it) }
        parrafo("Espejo y veda. Mástil observa cuánto te detenés en cada post de X y se lo cuenta a Euríloco. Si una cuerda del menú de no deseados reconoce un post, te avisa o pasa de largo, según lo que elegiste en el panel.")

        estado = parrafo("")
        boton("Actualizar estado") { refrescar() }
        boton("Probar aviso") {
            Config.prefs(this).edit().putLong(Config.PRUEBA_AVISO, System.currentTimeMillis()).apply()
            avisar("Si Mástil está activo en Accesibilidad, tendrías que ver un destello o sentir una vibración.")
        }

        titulo("1. Activar")
        parrafo("Mástil usa el servicio de accesibilidad para leer la pantalla de X, y solo de X. Si Android dice «ajuste restringido», andá a Ajustes › Apps › Mástil › menú ⋮ › «Permitir ajustes restringidos» y volvé a intentar.")
        boton("Abrir Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        titulo("2. Emparejar con Euríloco")
        parrafo("En el panel de Euríloco tocá «Emparejar teléfono» y escaneá el código con la cámara. Si la cámara no ofrece abrir Mástil, copiá el enlace del panel y pegalo acá:")
        val campo = EditText(this).apply {
            hint = "mastil://emparejar?h=…&t=…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }.also { col.addView(it) }
        boton("Emparejar") { emparejarCon(Uri.parse(campo.text.toString().trim())) }

        titulo("3. Sincronizar")
        parrafo("Mástil sincroniza solo cada 15 minutos cuando ve a Euríloco. Guarda hasta una semana; si pasan días sin conexión, te avisa.")
        boton("Sincronizar ahora") { sincronizar() }
        resultado = parrafo("")

        titulo("Volcado de pantalla")
        parrafo("Si X cambia y Mástil deja de leer bien, un volcado muestra cómo está armada la pantalla para ajustar el adaptador. Pedilo, abrí X en el feed y volvé.")
        boton("Pedir volcado") {
            volcadoPedido = true
            avisar("Listo. Abrí X en el feed; te aviso cuando esté guardado.")
        }
        boton("Compartir volcado") { compartirVolcado() }

        return ScrollView(this).apply { addView(col) }
    }
}
