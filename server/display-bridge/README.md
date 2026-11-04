# display-bridge

Kleiner Python-Dienst auf dem Raspberry Pi. Er liest das Fahrtenbuch aus PocketBase, rechnet km, Rekorde und Kosten aus und schickt alles per UART ans ESP32-Display (ESP32-2432S028R). Außerdem führt er die Befehle aus dem Server-Menü des Displays aus. Plan und Protokoll: `docs/RASPBERRY-PI.md` auf `main`, Teil 2.

Er schreibt nichts in die Datenbank. Einzige Ausnahme ist "Backup jetzt", das über die PocketBase-API ein Backup anlegt.

## Einrichten auf dem Pi

1. Superuser nur für die Bridge anlegen (Passwort selbst ausdenken):
   ```bash
   docker exec -it fahrtenbuch /usr/local/bin/pocketbase superuser upsert display@fahrtenbuch.local <passwort> --dir=/pb_data
   ```
2. Installieren:
   ```bash
   cd ~/Fahrtenbuch-App && git pull
   cd server/display-bridge && sudo ./install.sh
   ```
3. Passwort und Schnittstelle eintragen (`SERIAL_PORT=/dev/ttyUSB0` für USB-Kabel, `/dev/serial0` für GPIO):
   ```bash
   sudo nano /etc/fahrtenbuch-display.env
   sudo systemctl restart fahrtenbuch-display
   ```
4. Prüfen:
   ```bash
   systemctl status fahrtenbuch-display --no-pager
   journalctl -u fahrtenbuch-display -f
   ```

## Testen ohne Display

```bash
sudo -u fbdisplay bash -c 'set -a; . /etc/fahrtenbuch-display.env; python3 /opt/fahrtenbuch-display/bridge.py --trocken --einmal'
```

Schreibt alle Nachrichten, die ans Display gehen würden, ins Terminal.

Tests der Berechnungen (laufen auch in GitHub Actions):

```bash
python3 -m unittest -v test_bridge
```

## Was berechnet wird

- **km:** pro Fahrt `km_ende - km_start`, wenn beide gesetzt sind, sonst `strecke_km`. Gezählt wird nach dem Startdatum. Gelöschte (`geloescht`), laufende und Test-Fahrten (`test`, Test-Modus der Apps) zählen nicht. Dasselbe gilt für Test-Tankungen.
- **Laufende Fahrten:** Fahrten ohne `ende`, die in den letzten 12 Stunden gestartet sind. Ältere offene Fahrten sind hängen geblieben und erscheinen nicht als "Unterwegs".
- **Kosten:** `gesamtpreis` oder `liter * preis_pro_liter` aus `tankvorgang`, dazu `betrag` aus `kosten` (Werkstatt, Versicherung usw.) getrennt. Beim E-Auto (`kraftstoff = elektro`) steht `liter` für kWh, die Einheit wird mitgeschickt.
- **Laden zuhause ist kostenlos:** Ladevorgänge mit `ladeort = zuhause` zählen 0 Euro. Für das E-Auto kommen zusätzlich `kwh_zuhause_monat`, `kwh_oeffentlich_monat` und `anteil_zuhause` (Prozent der kWh im Jahr) mit.
- **Verbrauch:** L/100 km zwischen vollen Tankungen. Beim E-Auto zählt jeder Ladevorgang, weil selten voll geladen wird (wie in den Apps).
- **Kosten pro km:** alle Kosten des Jahres geteilt durch die km des Jahres.

## Backup-Wächter

Die Nachricht `server` enthält `backup_alter_h` und `backup_warnung`. Die Warnung ist gesetzt, wenn es kein PocketBase-Backup gibt oder das neueste älter als 2 Tage ist. Steht in `/etc/fahrtenbuch-display.env` zusätzlich `BACKUP_KOPIE=/mnt/backup/fahrtenbuch`, prüft die Bridge auch diesen Ordner (USB-Stick) und warnt, wenn er fehlt oder die neueste Datei dort älter als 2 Tage ist. Das Display zeigt die Warnung oben in der Kopfzeile.

## Server-Menü

| Befehl | Ausführung |
|---|---|
| `pb_neustart` | `sudo docker restart fahrtenbuch` (nur diese Regel, keine docker-Gruppe) |
| `backup` | `POST /api/backups` |
| `reboot` | `sudo systemctl reboot` (Regeln in `/etc/sudoers.d/fahrtenbuch-display`) |
| `poweroff` | `sudo systemctl poweroff`, vorher `{"t":"aus"}` ans Display |
| `neu_laden` | alle Daten neu schicken |

Ist `PIN` gesetzt, muss das Display die PIN mitschicken.

## Einstellungen ans Display

- `DREHUNG=0` oder `DREHUNG=180` in `/etc/fahrtenbuch-display.env`: Nach jedem `hallo` schickt die Bridge `{"t":"einstellung","drehung":...}`, wenn das Display etwas anderes meldet. Leer lassen, wenn nur der Schalter "Bild drehen" im Server-Menü gelten soll.

## Ohne Display

Fehlt die Schnittstelle (Display nicht angesteckt), läuft der Dienst weiter, schreibt einmal eine Warnung ins Log und versucht alle 10 Sekunden, sie zu öffnen. Sobald sie da ist, gehen sofort alle Daten raus.

## Firmware-Update

Die Firmware gehört der PC-Session (Branch `display`). Für ein Update über die Leitung stoppt `display/tools/display_update.py` diesen Dienst selbst und startet ihn danach wieder.
