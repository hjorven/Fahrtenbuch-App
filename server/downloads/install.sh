#!/usr/bin/env bash
# Richtet das automatische Abholen neuer App-Builds auf dem Pi ein.
# Aufruf (als normaler Benutzer mit sudo, NICHT als root einloggen):
#   cd ~/Fahrtenbuch-App/server/downloads && sudo ./install.sh
# Kann beliebig oft wiederholt werden.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Bitte mit sudo starten: sudo ./install.sh" >&2
  exit 1
fi
BENUTZER="${SUDO_USER:-}"
if [[ -z $BENUTZER || $BENUTZER == root ]]; then
  echo "Bitte als normaler Benutzer (z. B. admin) mit sudo starten, nicht als root." >&2
  exit 1
fi
HEIM=$(getent passwd "$BENUTZER" | cut -d: -f6)
HIER="$(cd "$(dirname "$0")" && pwd)"
ENV=/etc/fahrtenbuch-downloads.env

# QR-Codes auf der Startseite (index.html); ohne das Paket zeigt die Seite nur Links.
apt-get install -y -qq python3-qrcode >/dev/null || echo "   python3-qrcode nicht installiert, Startseite ohne QR-Codes"

echo "1/4 Programm nach /opt/fahrtenbuch-downloads"
install -d -m 755 /opt/fahrtenbuch-downloads
install -m 644 "$HIER/abholen.py" /opt/fahrtenbuch-downloads/abholen.py

echo "2/4 Konfiguration $ENV"
if [[ ! -f $ENV ]]; then
  cat > "$ENV" <<KONF
# Ordner, den der nginx-Container (Port 8091) ausliefert
FB_ZIEL=$HEIM/ipas
# Git-Checkout mit Deploy-Key (daher kommt die Adresse des Repos)
FB_REPO=$HEIM/Fahrtenbuch-App
# So erreichen die Handys den Pi (steht in apps.json als Download-Adresse)
FB_BASIS=http://fahrtenbuch.local:8091
KONF
  chmod 644 "$ENV"
  echo "   angelegt"
else
  echo "   bleibt wie es ist"
fi

echo "3/4 systemd-Dienst und Timer (alle 10 Minuten, als $BENUTZER)"
sed "s/@BENUTZER@/$BENUTZER/" "$HIER/fahrtenbuch-downloads.service" > /etc/systemd/system/fahrtenbuch-downloads.service
install -m 644 "$HIER/fahrtenbuch-downloads.timer" /etc/systemd/system/fahrtenbuch-downloads.timer
systemctl daemon-reload
systemctl enable --now fahrtenbuch-downloads.timer >/dev/null

echo "4/4 Erster Durchlauf"
if systemctl start fahrtenbuch-downloads.service; then
  journalctl -u fahrtenbuch-downloads -n 10 --no-pager -o cat
  echo "Fertig. Angeboten wird:"
  ls -l "$HEIM/ipas" | grep -E "Fahrtenbuch-|json" || true
else
  echo "Erster Durchlauf fehlgeschlagen, Ausgabe:" >&2
  journalctl -u fahrtenbuch-downloads -n 30 --no-pager -o cat >&2
  exit 1
fi
