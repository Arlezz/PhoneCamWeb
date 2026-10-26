#!/usr/bin/env bash
# Mantiene el puerto de la Mac conectado al teléfono aunque desenchufes y vuelvas a
# enchufar el cable. Al desconectar el cable, adb borra el "forward"; este script lo
# vuelve a crear apenas el teléfono reaparece. La extensión y el visor reconectan solos.
#
# Uso: tools/usb-forward.sh [puerto]     (Ctrl+C para salir)

PORT="${1:-5000}"

if ! command -v adb >/dev/null 2>&1; then
  echo "No encuentro adb. Revisa que platform-tools esté en el PATH."
  exit 1
fi

trap 'echo; echo "Listo."; exit 0' INT

while true; do
  echo "Esperando el teléfono por USB…"
  adb wait-for-usb-device
  if adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null; then
    echo "$(date +%H:%M:%S)  Conectado: localhost:$PORT → teléfono"
  else
    echo "$(date +%H:%M:%S)  No se pudo crear el forward; reintento en 2 s"
    sleep 2
    continue
  fi
  adb wait-for-usb-disconnect
  echo "$(date +%H:%M:%S)  Teléfono desconectado"
done
