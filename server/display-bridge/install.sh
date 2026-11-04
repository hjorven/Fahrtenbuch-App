#!/usr/bin/env bash
# Richtet die display-bridge auf dem Raspberry Pi ein. Aufruf: sudo ./install.sh
# Kann beliebig oft wiederholt werden (z. B. nach git pull), die Konfiguration bleibt erhalten.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Bitte mit sudo starten: sudo ./install.sh" >&2
  exit 1
fi

HIER="$(cd "$(dirname "$0")" && pwd)"
ZIEL=/opt/fahrtenbuch-display
ENV=/etc/fahrtenbuch-display.env

echo "1/5 Pakete"
# python3-pil: Karte der letzten Fahrt (karte.py), ohne läuft die Bridge auch, nur ohne Karte
apt-get install -y -qq python3 python3-serial python3-pil >/dev/null

echo "2/5 Benutzer fbdisplay"
if ! id fbdisplay >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin fbdisplay
fi
usermod -aG dialout fbdisplay
# Ältere Installationen hatten fbdisplay in der Gruppe docker (= root-gleich). Jetzt reicht sudo
# für genau "docker restart/inspect fahrtenbuch", siehe sudoers-fahrtenbuch-display.
if id -nG fbdisplay | tr ' ' '\n' | grep -qx docker; then
  gpasswd -d fbdisplay docker >/dev/null
fi

echo "3/5 Programm nach $ZIEL"
install -d -m 755 "$ZIEL"
install -m 644 "$HIER/bridge.py" "$ZIEL/bridge.py"
install -m 644 "$HIER/karte.py" "$ZIEL/karte.py"
install -m 644 "$HIER/wetter.py" "$ZIEL/wetter.py"

echo "4/5 sudoers-Regel (nur reboot, poweroff und docker restart/inspect fahrtenbuch)"
TMP=$(mktemp)
cp "$HIER/sudoers-fahrtenbuch-display" "$TMP"
visudo -cf "$TMP" >/dev/null
install -m 440 "$TMP" /etc/sudoers.d/fahrtenbuch-display
rm -f "$TMP"

echo "5/5 Konfiguration und Dienst"
if [[ ! -f $ENV ]]; then
  install -m 640 -g fbdisplay "$HIER/display.env.beispiel" "$ENV"
  echo "   $ENV angelegt. Bitte PB_PASSWORD und SERIAL_PORT eintragen:"
  echo "   sudo nano $ENV"
else
  chgrp fbdisplay "$ENV" && chmod 640 "$ENV"
  echo "   $ENV bleibt wie es ist."
fi
install -m 644 "$HIER/fahrtenbuch-display.service" /etc/systemd/system/fahrtenbuch-display.service
systemctl daemon-reload
systemctl enable fahrtenbuch-display >/dev/null

if grep -q '^PB_PASSWORD=.\+' "$ENV"; then
  systemctl restart fahrtenbuch-display
  echo "Fertig. Status: systemctl status fahrtenbuch-display --no-pager"
else
  echo "Fertig, Dienst startet erst nach dem Eintragen von PB_PASSWORD:"
  echo "   sudo systemctl restart fahrtenbuch-display"
fi
