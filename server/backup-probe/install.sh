#!/usr/bin/env bash
# Monatliche Backup-Probe einrichten (Roadmap Nr. 19). Aufruf auf dem Pi:
#   cd ~/Fahrtenbuch-App/server/backup-probe && sudo ./install.sh
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "Bitte mit sudo starten" >&2; exit 1; }
HIER="$(cd "$(dirname "$0")" && pwd)"
install -d -m 755 /opt/fahrtenbuch-backup-probe
install -m 644 "$HIER/probe.py" /opt/fahrtenbuch-backup-probe/probe.py
install -m 644 "$HIER/fahrtenbuch-backup-probe.service" /etc/systemd/system/
install -m 644 "$HIER/fahrtenbuch-backup-probe.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now fahrtenbuch-backup-probe.timer >/dev/null
echo "Erste Probe:"
systemctl start fahrtenbuch-backup-probe.service || true
cat /var/lib/fahrtenbuch-backup-probe/ergebnis.json; echo
systemctl list-timers fahrtenbuch-backup-probe.timer --no-pager | head -3
