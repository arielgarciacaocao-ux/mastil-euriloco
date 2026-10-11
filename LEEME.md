# Mástil y Euríloco — etapa 1: el espejo

En esta etapa Mástil **solo observa**: mide cuánto tiempo te detenés en cada post de X y se lo cuenta a Euríloco, que lo guarda y te lo muestra en gráficos. No avisa, no salta, no toca nada. Sirve para dos cosas: comprobar que la lectura de X funciona en tu teléfono y ver tu punto de partida antes de poner las primeras cuerdas.

```
mastil-euriloco/
├── euriloco/      el daemon para la Raspberry Pi (Python, sin dependencias)
├── mastil/        la app para Android (Kotlin)
└── .github/       la receta para que GitHub compile la app
```

---

## Parte A · Euríloco en la Raspberry Pi 500

1. Copiá la carpeta `mastil-euriloco` a la Raspberry (por ejemplo, a tu carpeta personal).
2. Abrí una terminal en la carpeta `euriloco` y escribí:

   ```
   sh instalar.sh
   ```

   Te va a pedir la contraseña una vez. El instalador deja a Euríloco corriendo como servicio: arranca solo al prender la Raspberry, aunque nadie inicie sesión.
3. Abrí el navegador de la Raspberry en **http://localhost:8737**. Vas a ver el panel vacío: es normal hasta la primera sincronización.

Comandos útiles, por si hacen falta:

| Para… | Escribí |
| --- | --- |
| ver si está andando | `systemctl --user status euriloco` |
| reiniciarlo | `systemctl --user restart euriloco` |
| ver sus mensajes | `journalctl --user -u euriloco -f` |
| desemparejar el teléfono (clave nueva) | `python3 ~/.local/lib/euriloco/euriloco.py --nueva-clave` y después reiniciarlo |

Los datos quedan en `~/.local/share/euriloco/euriloco.db` y la configuración en `~/.config/euriloco/`.

---

## Parte B · Compilar la app Mástil

La app hay que compilarla una vez para obtener el instalador (el archivo `.apk`). Lo hace GitHub, gratis, con la receta que está en `.github/`.

**Camino corto:** creá una cuenta en github.com, hacé un repositorio **privado** vacío llamado `mastil-euriloco` y pasame el nombre (`tu-usuario/mastil-euriloco`). Con eso subo los archivos y lanzo la compilación yo.

**Camino manual:**

1. En el repositorio, «Add file» › «Upload files» y arrastrá **el contenido** de la carpeta `mastil-euriloco`. Ojo: la carpeta `.github` está oculta; en el explorador de archivos activá «mostrar archivos ocultos» (Ctrl+H) para poder arrastrarla.
2. Andá a la pestaña **Actions**. Vas a ver «Compilar Mástil» corriendo; tarda unos 5 minutos.
3. Cuando termine con una tilde verde, entrá y descargá **mastil-apk** (abajo de todo, en «Artifacts»). Es un zip con `app-debug.apk` adentro.

---

## Parte C · Instalar Mástil en el teléfono

1. Pasá `app-debug.apk` al teléfono (por cable, por mail o por Drive) y abrilo. Android va a pedir permiso para instalar apps de esa fuente: aceptá.
2. Abrí Mástil y tocá **Abrir Accesibilidad**. Buscá Mástil en la lista y activalo.
3. Si Android dice **«ajuste restringido»** (pasa con apps instaladas fuera de la tienda): andá a Ajustes › Apps › Mástil, tocá el menú ⋮ arriba a la derecha › **«Permitir ajustes restringidos»**, y volvé al paso 2.
4. Aceptá el permiso de notificaciones: es para avisarte si la sincronización se atrasa.

---

## Parte D · Emparejar

1. En el panel de Euríloco tocá **Emparejar teléfono**: aparece un código QR.
2. Escanealo con la cámara del teléfono y elegí abrir con Mástil.
   Si la cámara no ofrece Mástil, el panel muestra también el enlace: copialo, pegalo en Mástil y tocá **Emparejar**.
3. Mástil prueba la conexión al instante. Si dice «Sincronizado», listo.

Desde ahí, Mástil sincroniza solo cada 15 minutos cuando está en la red de casa. Guarda hasta una semana en el teléfono; si pasan cinco días sin ver a Euríloco, te avisa.

**Fuera de casa (opcional):** instalá Tailscale en el teléfono y en la Raspberry con la misma cuenta y volvé a emparejar. El código QR va a incluir la dirección de Tailscale y Mástil la usará cuando no encuentre la red de casa.

---

## Qué esperar y qué revisar

- Usá X normalmente un día. Después tocá **Sincronizar ahora** en Mástil y mirá el panel: tiempo por día, cuánto te detenés en cada post, las cuentas donde más se queda tu atención y las últimas detenciones largas.
- **Si la tabla de detenciones muestra textos que no son posts** (menús, botones) o no aparecen cuentas: X armó su pantalla distinto de lo previsto. En Mástil tocá **Pedir volcado**, abrí X en el feed, volvé y tocá **Compartir volcado**. Mandámelo y ajusto el adaptador.
- La lectura de X es la parte más frágil: X cambia su app seguido. Por eso la etapa 1 existe antes de cualquier aviso.

## Seguridad y privacidad

- Mástil solo lee la pantalla de X (`com.twitter.android`); Android no le entrega nada de otras apps.
- Los registros viajan solo a tu Raspberry, con una clave propia del emparejamiento. En la red de casa la conexión no va cifrada en esta etapa; con Tailscale, sí. El cifrado propio llega antes de la distribución pública.
- El panel de Euríloco solo se abre desde la propia Raspberry, porque muestra la clave en el QR.
