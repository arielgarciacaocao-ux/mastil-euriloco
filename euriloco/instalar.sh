#!/bin/sh
# Instala Euríloco en la Raspberry Pi como servicio del usuario actual.
# Uso: sh instalar.sh   (desde la carpeta euriloco, sin sudo)
set -e

ORIGEN="$(cd "$(dirname "$0")" && pwd)"
DESTINO="$HOME/.local/lib/euriloco"

echo "1/4 Instalando el generador de códigos QR (pide la contraseña)..."
sudo apt-get install -y python3-qrcode >/dev/null || echo "   No se pudo instalar python3-qrcode; el panel mostrará el enlace en lugar del QR."

echo "2/4 Copiando Euríloco a $DESTINO..."
mkdir -p "$DESTINO"
cp "$ORIGEN/euriloco.py" "$ORIGEN/veda.py" "$ORIGEN/veda_inicial.json" "$ORIGEN/panel.html" "$DESTINO/"

echo "3/4 Registrando el servicio..."
mkdir -p "$HOME/.config/systemd/user"
cp "$ORIGEN/euriloco.service" "$HOME/.config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable euriloco.service
# restart: si ya estaba corriendo una versión anterior, la reemplaza (los datos se conservan).
systemctl --user restart euriloco.service
# Que siga corriendo aunque nadie haya iniciado sesión:
sudo loginctl enable-linger "$USER" || true

echo "4/4 Listo."
sleep 1
systemctl --user --no-pager status euriloco.service | head -3
echo
echo "Panel: abrí http://localhost:8737 en el navegador de la Raspberry."
echo "Para emparejar el teléfono, tocá «Emparejar teléfono» en el panel."
