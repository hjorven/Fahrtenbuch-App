# Raspberry Pi 4B als Server mit Display

Plan für den Umzug des Fahrtenbuch-Servers vom HP ProBook auf einen Raspberry Pi 4B, plus ein 2,8-Zoll-Touch-Display (ESP32-2432S028R) am Pi. Das Display ist eine Art kleines Info-Tablet: Es zeigt Fahrten und Kilometer pro Auto, und man kann darüber den Server neu starten oder den Pi ausschalten.

Stand: 2026-11-03. Umgesetzt: Server läuft auf dem Pi (SD-Karte, LAN, Benutzer `admin`, Git-Checkout per Deploy-Key), display-bridge installiert, Display über den Stecker "UART" angeschlossen, Firmware `display-9`. Aktueller Stand und Absprachen: [ABSTIMMUNG.md](ABSTIMMUNG.md).

## Überblick

```
iPhone (Admin) ─┐
iPhone (Ben)  ─┼──►  Raspberry Pi 4B
Android (Anna)─┘     ├─ pocketbase (Docker)     Datenbank, REST-API, Admin-UI (wie bisher)
                       └─ display-bridge          Python-Dienst direkt auf dem Pi (systemd):
                                                  liest PocketBase nur, schickt Daten ans Display,
                                                  führt Server-Befehle vom Display aus
                                │
                                │ UART über GPIO (oder USB-Kabel)
                                ▼
                       ESP32-2432S028R, 2,8"-Touch-Display (320 x 240)
                       Übersicht, letzte und längste Fahrten, Kosten, Server-Menü
```

Die Apps merken vom Umzug nichts außer der neuen Server-Adresse. Datenmodell, Migrationen und API bleiben **unverändert**: Das Display liest nur und schreibt nichts in die Datenbank.

## Das Display: ESP32-2432S028R

Bestätigt von Admin. Das Board ist als "Cheap Yellow Display" (CYD) bekannt:

- ESP32-WROOM mit eigenem WLAN und Bluetooth
- Display ILI9341, 320 x 240, resistiver Touch (XPT2046)
- RGB-LED, Lichtsensor, microSD-Slot, USB (Micro-USB oder USB-C) mit Seriell-Wandler (CH340)
- Steckerleisten: `P1` (5V, TX, RX, GND) und `CN1` (GND, GPIO22, GPIO27, 3,3V)

Wichtig: Das ist ein **eigener kleiner Computer**. Der Pi steuert die Pixel nicht direkt, sondern schickt dem ESP32 Daten, und die Firmware auf dem ESP32 zeichnet die Oberfläche. Deshalb braucht es eine Firmware für den ESP32 und einen kleinen Dienst auf dem Pi.

## Hardware

| Teil | Empfehlung | Warum |
|---|---|---|
| Raspberry Pi 4B | vorhanden, 4 oder 8 GB RAM | PocketBase braucht unter 100 MB, mehr als genug |
| Speicher | SSD per USB 3 (z. B. 128 GB im USB-Gehäuse) | SD-Karten gehen bei ständigen Datenbank-Schreibzugriffen kaputt |
| Netzteil | offizielles 5V/3A USB-C | billige Netzteile verursachen Abstürze und Datenverlust |
| Netzwerk | LAN-Kabel zur FritzBox | stabiler als WLAN; WLAN geht aber auch |
| Gehäuse | mit Lüfter oder Passivkühlung | Pi 4 drosselt sonst im Sommer |
| Kabel Display | 4 Jumper-Kabel (Buchse auf JST 1,25 mm, liegt dem CYD meist bei) | für GPIO-Anschluss |

Optional: kleiner Halter oder 3D-gedrucktes Gehäuse, in dem Pi und Display zusammen stehen.

## Teil 1: Server auf den Pi umziehen

### 1.1 Pi einrichten (am Linux-PC)

Raspberry Pi Imager gibt es als Flatpak:

```bash
flatpak install -y flathub org.raspberrypi.rpi-imager
flatpak run org.raspberrypi.rpi-imager
```

Im Imager:

- Gerät: Raspberry Pi 4
- System: **Raspberry Pi OS Lite (64-bit)** (ohne Desktop)
- Ziel: die SSD (am PC per USB angesteckt). Der Pi 4 bootet von USB, wenn keine SD-Karte steckt (bei sehr alten Pis vorher Bootloader per SD aktualisieren).
- Einstellungen: Hostname `fahrtenbuch`, Benutzer `admin`, SSH aktivieren mit **öffentlichem Schlüssel** (vom PC), Zeitzone Europe/Berlin, WLAN nur falls kein Kabel

Dann SSD an den Pi (blauer USB-3-Port), LAN-Kabel, Strom. Nach etwa einer Minute:

```bash
ssh admin@fahrtenbuch.local
sudo apt update && sudo apt full-upgrade -y
```

### 1.2 Docker installieren

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
exit   # neu einloggen, damit die Gruppe greift
```

### 1.3 Repo holen und Version festlegen

Das bisherige Image `ghcr.io/muchobien/pocketbase` gibt es auch für arm64. Es steht aber auf `latest`, das ist beim Umzug riskant: Auf beiden Rechnern muss **dieselbe PocketBase-Version** laufen. Deshalb zuerst auf dem HP nachsehen und die Version in `docker-compose.yml` fest eintragen (eigener PR auf dem Branch `server`):

```bash
# auf dem HP
docker exec fahrtenbuch /usr/local/bin/pocketbase --version
```

```yaml
image: ghcr.io/muchobien/pocketbase:0.31.0   # Beispiel, die Version vom HP eintragen
```

Auf dem Pi:

```bash
git clone -b server https://github.com/hjorven/Fahrtenbuch-App.git
cd Fahrtenbuch-App/server
docker compose pull   # prüft gleich, ob das Image für arm64 da ist
```

### 1.4 Daten vom HP übernehmen

Umzug an einem Abend, wenn keiner fährt. Die Apps arbeiten offline weiter und laden später hoch, es geht also nichts verloren.

```bash
# 1. auf dem HP: Server stoppen und Daten einpacken
cd ~/Fahrtenbuch-App/server
docker compose down
tar czf ~/pb_data-umzug.tar.gz pb_data

# 2. vom HP auf den Pi kopieren
scp ~/pb_data-umzug.tar.gz admin@fahrtenbuch.local:~/

# 3. auf dem Pi: auspacken und starten
cd ~/Fahrtenbuch-App/server
tar xzf ~/pb_data-umzug.tar.gz
docker compose up -d
docker compose logs --tail=50
curl -s http://localhost:8090/api/health
```

Danach in der Admin-Oberfläche `http://fahrtenbuch.local:8090/_/` prüfen: Benutzer, Autos und Fahrten sind da.

Den HP **nicht wieder starten**, solange die Apps noch auf ihn zeigen könnten, sonst landen Daten auf zwei Servern. Die Sicherung `pb_data-umzug.tar.gz` auf dem HP eine Weile aufheben.

### 1.5 Neue Adresse in den Apps

Die FritzBox lässt sich nicht konfigurieren, also gibt es keine feste IP-Reservierung. Möglichkeiten, in dieser Reihenfolge:

1. **`http://fahrtenbuch.local:8090`** (mDNS). Funktioniert auf iPhones zuverlässig, auf Android ab Version 12 meistens. Einfach testen.
2. **IP-Adresse** (`hostname -I` auf dem Pi). Die FritzBox vergibt meist dauerhaft dieselbe, garantiert ist das aber nicht.
3. **Tailscale** (`http://fahrtenbuch.<tailnet>.ts.net:8090`). Feste Adresse, funktioniert auch unterwegs. Siehe 1.7.

Auf jedem Handy in der App unter Einstellungen die Server-Adresse ändern und neu anmelden. Das Display zeigt die aktuelle Adresse und einen QR-Code dazu an (siehe Teil 2).

### 1.6 Backup

PocketBase hat eingebaute Backups: Admin-Oberfläche > Settings > Backups > "Auto backups" aktivieren, z. B. täglich um 3 Uhr, die letzten 7 behalten. Die liegen aber auf demselben Pi. Zusätzlich eine Kopie woanders hin, z. B. nachts auf einen USB-Stick am Pi:

```bash
# USB-Stick unter /mnt/backup einhängen (einmalig, Details siehe PC-AUFGABEN.md)
crontab -e
```

```
15 3 * * * rsync -a --delete ~/Fahrtenbuch-App/server/pb_data/backups/ /mnt/backup/fahrtenbuch/
```

Oder vom Linux-PC aus abholen, wann immer er läuft:

```bash
rsync -a admin@fahrtenbuch.local:Fahrtenbuch-App/server/pb_data/backups/ ~/Backups/fahrtenbuch/
```

### 1.7 Optional: Tailscale

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Danach Tailscale auch auf den Handys installieren. Die Apps nutzen dann die `ts.net`-Adresse, die sich nie ändert und auch unterwegs geht.

### 1.8 Wartung

```bash
# Updates des Systems (einmal im Monat)
sudo apt update && sudo apt full-upgrade -y

# Server aktualisieren (nach Änderungen im Branch server)
cd ~/Fahrtenbuch-App/server && git pull && docker compose up -d

# Temperatur und Spannung prüfen ("throttled=0x0" heißt alles gut)
vcgencmd measure_temp && vcgencmd get_throttled
```

## Teil 2: Display

### 2.1 Anbindung

Empfohlen ist **UART über die GPIO-Pins**, so wie gewünscht. Der ESP32 bekommt darüber Strom und Daten, kein WLAN-Passwort auf dem ESP32 nötig, und das Display funktioniert auch, wenn das WLAN spinnt.

Die Firmware wird **einmal per USB** aufgespielt (vom Linux-PC oder vom Pi aus). Danach reicht die GPIO-Verbindung.

#### Verkabelung (Variante A, GPIO) – so ist es beim Admin umgesetzt

Alle vier Adern am CYD-Stecker mit der Aufschrift **"UART"** (= `P1`: VIN, TX, RX, GND). Daten laufen damit über den UART0 des ESP32 (GPIO1/3). Vorteil: Über dieselbe Leitung ist auch der Bootloader vom Pi erreichbar, Firmware-Updates gehen ohne USB (siehe 2.6).

| Pi Pin | Pi Funktion | Kabel zu | CYD |
|---|---|---|---|
| 2 | 5V | rot | `P1` VIN (5V) |
| 6 | GND | schwarz | `P1` GND |
| 8 | GPIO14 (TXD) | gelb | `P1` RX |
| 10 | GPIO15 (RXD) | grün | `P1` TX |

Alternative (ursprünglicher Plan): Strom über `P1`, Daten über `CN1` (zweiter UART). Die Firmware hört auf beiden:

| Pi Pin | Pi Funktion | Kabel zu | CYD |
|---|---|---|---|
| 2 | 5V | rot | `P1` VIN (5V) |
| 6 | GND | schwarz | `P1` GND |
| 8 | GPIO14 (TXD) | gelb | `CN1` GPIO27 (RX der Firmware) |
| 10 | GPIO15 (RXD) | grün | `CN1` GPIO22 (TX der Firmware) |
| 14 | GND | schwarz | `CN1` GND |

- Beide Seiten arbeiten mit 3,3 V Logik, ein Pegelwandler ist nicht nötig.
- Den 3,3V-Pin von `CN1` **nicht** anschließen.
- Die Pin-Reihenfolge der Stecker unterscheidet sich je nach Board-Version. Vor dem Einstecken die Beschriftung auf der Platine prüfen.
- Nicht gleichzeitig USB-Kabel und 5V vom Pi anschließen. Zum Flashen das 5V-Kabel abziehen.
- Strombedarf des Displays etwa 100 bis 250 mA, das schafft der Pi mit dem offiziellen Netzteil problemlos.

UART am Pi einschalten (ohne Login-Konsole auf der Schnittstelle):

```bash
sudo raspi-config nonint do_serial_hw 0     # Hardware-UART an
sudo raspi-config nonint do_serial_cons 1   # Login-Konsole auf UART aus
echo "dtoverlay=disable-bt" | sudo tee -a /boot/firmware/config.txt   # stabiler PL011-UART statt Mini-UART
sudo reboot
```

Danach heißt die Schnittstelle `/dev/serial0`. Bluetooth am Pi wird dafür abgeschaltet, das braucht der Server nicht.

#### Variante B, USB-Kabel

Einfach ein USB-Kabel vom Pi zum Display. Strom und Daten laufen darüber, die Schnittstelle heißt dann `/dev/ttyUSB0`, und man kann die Firmware direkt vom Pi aus aktualisieren. Gut zum Testen, und als Ausweichlösung, falls die GPIO-Verkabelung Probleme macht. Die Software ist für beide Varianten gleich, nur der Name der Schnittstelle unterscheidet sich.

#### Nicht empfohlen: ESP32 direkt per WLAN

Der ESP32 könnte auch selbst per WLAN die PocketBase-API abfragen. Dann bräuchte er aber Login-Daten und müsste alle Berechnungen selbst machen. Mit der Bridge auf dem Pi bleibt die Firmware einfach und alle Logik liegt an einer Stelle.

### 2.2 Was das Display zeigt

Wie ein kleines Tablet zum Anschauen: Tippen auf die Reiter unten wechselt die Seite, in Listen wird gewischt. Nach 2 Minuten ohne Berührung geht es zurück zur Übersicht, nachts (Lichtsensor oder Uhrzeit) wird gedimmt.

**Seite 1: Übersicht** (Startseite)

```
┌──────────────────────────────────────┐
│ Fahrtenbuch               Mo 14:32   │
│                    Monat      Jahr   │
│ ■ Bus        412 km   6.480 km │
│   Stand 182.340 km                   │
│ ■ Kombi  655 km   9.120 km │
│   Stand 241.118 km                   │
│ ■ E-Auto     38 km     610 km │
│   Stand 8.902 km                     │
│                                      │
│ Unterwegs: Ben im Bus seit 13:50    │
│ [Übersicht][Fahrten][Rekorde][Mehr]  │
└──────────────────────────────────────┘
```

- pro Auto: Farbe aus der App, km in diesem Monat, km in diesem Jahr, aktueller km-Stand
- laufende Fahrten (Fahrt ohne `ende`): wer fährt welches Auto seit wann
- Auto antippen öffnet die Detailseite: km pro Monat als kleines Balkendiagramm (letzte 12 Monate), km pro Fahrer, Tankkosten und Verbrauch

**Seite 2: Letzte Fahrten**

Die letzten 20 Fahrten aller Geräte zum Durchwischen: Datum, Fahrer, Auto, km, Dauer, Start nach Ziel (wenn vorhanden). Antippen zeigt Details (Uhrzeiten, Schnitt- und Höchstgeschwindigkeit, Zweck, Notiz).

**Seite 3: Rekorde**

- längste Fahrten (Top 5), umschaltbar Monat / Jahr / gesamt
- längste Fahrt pro Auto, höchste Geschwindigkeit, Fahrer mit den meisten km im Monat
- Summen: Fahrten und km diesen Monat und dieses Jahr

**Seite 4: Mehr**

- Kosten: Tankkosten diesen Monat und dieses Jahr pro Auto, Verbrauch (L/100 km aus den vollen Tankungen), Kosten pro km
- Server-Info: läuft ja/nein, PocketBase-Version, Uptime, CPU-Temperatur, freier Speicher, letztes Backup
- Adresse für die Apps als Text und als **QR-Code**
- Knopf **Server-Menü** (siehe 2.3)

Die km kommen aus den Fahrten mit Start im jeweiligen Zeitraum (`km_ende - km_start`, falls leer `strecke_km`). Gelöschte Fahrten (`geloescht = true`) zählen nicht.

### 2.3 Server-Menü: Neustart und Ausschalten

| Knopf | Was passiert | Befehl auf dem Pi |
|---|---|---|
| PocketBase neu starten | nur der Server-Container, dauert etwa 5 Sekunden | `docker restart fahrtenbuch` |
| Pi neu starten | ganzer Pi, etwa 1 Minute | `systemctl reboot` |
| Pi ausschalten | Pi fährt sauber herunter | `systemctl poweroff` |
| Backup jetzt | Backup über die PocketBase-API anlegen | `POST /api/backups` |
| Display neu laden | Bridge schickt alle Daten neu | (intern) |

Schutz vor versehentlichem Tippen:

- Jeder Knopf fragt nach ("Pi wirklich ausschalten?") und muss dann **3 Sekunden gedrückt gehalten** werden.
- Optional eine 4-stellige PIN für das Server-Menü (in `/etc/fahrtenbuch-display.env`).

Wichtig zum Ausschalten: Danach ist der Pi aus und **kein** Handy kann mehr synchronisieren. Der Pi 4 hat keinen Einschaltknopf, zum Wiedereinschalten muss man den Stecker kurz ziehen und wieder einstecken. Das Display zeigt nach dem Herunterfahren "Jetzt kann der Stecker gezogen werden". Ausschalten ist also für Stromausfall-Ankündigungen, Umzug oder wenn man an die SSD will, nicht für jeden Abend. Weil das Display am 5V-Pin des Pi hängt, geht es nach dem Herunterfahren aus, sobald der Stecker gezogen ist.

#### Ohne Display (zweiter Weg, Nachtplan 5c)

Reagiert der Touch nicht oder ist das Display ab, geht alles auch per SSH vom PC (`ssh admin@fahrtenbuch.local`, sonst `ssh admin@<pi-ip>` oder über Tailscale `ssh admin@<tailscale-ip>`):

| Aufgabe | Befehl |
|---|---|
| PocketBase neu starten (vorher Backup) | `cd ~/Fahrtenbuch-App/server && docker compose up -d` |
| Backup jetzt | im Browser `http://fahrtenbuch.local:8090/_/` → Settings → Backups → „New backup“ |
| Pi neu starten | `sudo systemctl reboot` |
| Pi ausschalten | `sudo systemctl poweroff` (danach Stecker ziehen und wieder einstecken zum Einschalten) |
| Display-Bridge neu starten | `sudo systemctl restart fahrtenbuch-display` |
| Was macht die Bridge? | `journalctl -u fahrtenbuch-display -n 50 --no-pager` |

**PIN vergessen:** Die PIN steht als `PIN=` in `/etc/fahrtenbuch-display.env`. Zurücksetzen mit `sudo nano /etc/fahrtenbuch-display.env` (Zeile `PIN=` leer lassen = keine PIN, oder vier neue Ziffern), danach `sudo systemctl restart fahrtenbuch-display`. Die Datei enthält auch den Display-Zugang zu PocketBase, sie bleibt nur für root und die Bridge lesbar.

### 2.4 Software auf dem Pi: display-bridge

Ein kleiner Python-Dienst, der **direkt auf dem Pi** als systemd-Dienst läuft, nicht im Docker-Container. Grund: Er muss den Pi neu starten und ausschalten können, das ginge aus einem Container nur mit sehr weitgehenden Rechten.

Aufbau im Repo (Branch `server`):

```
server/display-bridge/
  bridge.py                     Hauptprogramm
  requirements.txt              pyserial, requests
  fahrtenbuch-display.service   systemd-Unit
  sudoers-fahrtenbuch-display   erlaubt genau reboot und poweroff
  install.sh                    richtet alles ein
```

Installation auf dem Pi:

```bash
cd ~/Fahrtenbuch-App/server/display-bridge
sudo ./install.sh
systemctl status fahrtenbuch-display --no-pager
journalctl -u fahrtenbuch-display -f
```

Was `install.sh` macht:

- Python-Umgebung unter `/opt/fahrtenbuch-display` anlegen und Pakete installieren
- Benutzer `fbdisplay` anlegen, Mitglied der Gruppen `dialout` (serielle Schnittstelle) und `docker` (Container neu starten)
- sudoers-Regel, die diesem Benutzer **nur** diese zwei Befehle ohne Passwort erlaubt:
  ```
  fbdisplay ALL=(root) NOPASSWD: /usr/bin/systemctl reboot, /usr/bin/systemctl poweroff
  ```
- Konfiguration `/etc/fahrtenbuch-display.env` anlegen (nicht im Repo):
  ```
  PB_URL=http://localhost:8090
  PB_EMAIL=display@fahrtenbuch.local
  PB_PASSWORD=...
  SERIAL_PORT=/dev/serial0        # Variante B: /dev/ttyUSB0
  APP_URL=http://fahrtenbuch.local:8090
  PIN=
  ```

Aufgaben der Bridge:

- alle 30 Sekunden (später per PocketBase-Realtime sofort) `fahrzeug`, `fahrt`, `tankvorgang` und `users` lesen und daraus km pro Monat und Jahr, Rekorde, laufende Fahrten, Kosten und Verbrauch berechnen
- das Ergebnis als JSON-Zeilen an den ESP32 schicken
- Server-Infos einsammeln (Temperatur über `vcgencmd`, Speicher, Uptime, Container-Status, letztes Backup)
- Befehle aus dem Server-Menü ausführen

Zugang zu PocketBase: ein **eigenes Superuser-Konto** nur für die Bridge (nötig für Backups und um alle Datensätze unabhängig von den Regeln zu lesen). Kein normaler Benutzer, damit kein "Display" als Fahrer in den Apps auftaucht. Das Passwort steht nur in `/etc/fahrtenbuch-display.env` (Rechte `600`).

```bash
docker exec -it fahrtenbuch /usr/local/bin/pocketbase superuser upsert display@fahrtenbuch.local <passwort> --dir=/pb_data
```

### 2.5 Protokoll zwischen Pi und ESP32

JSON, eine Nachricht pro Zeile, 115200 Baud. Feld `t` gibt den Typ an.

Pi an ESP32:

```json
{"t":"autos","d":[{"id":"t5xxxxxxxxxxxxx","name":"Bus","farbe":"#2E7D32","km":182340,"km_monat":412,"km_jahr":6480}]}
{"t":"laeuft","d":[{"fahrer":"Ben","auto":"Bus","seit":"13:50"}]}
{"t":"fahrten","d":[{"id":"...","datum":"02.11.","fahrer":"Admin","auto":"Kombi","km":23.4,"min":31,"von":"Zuhause","nach":"Schule"}]}
{"t":"rekorde","zeitraum":"monat","d":[{"datum":"14.11.","fahrer":"Ben","auto":"Bus","km":312.0}]}
{"t":"kosten","d":[{"auto":"Bus","euro_monat":86.40,"euro_jahr":1210.00,"l100":9.1,"euro_km":0.16}]}
{"t":"server","ok":true,"version":"0.31.0","url":"http://fahrtenbuch.local:8090","temp":48.2,"uptime_h":312,"backup":"02.11. 03:00","frei_gb":97}
{"t":"zeit","iso":"2026-11-02T14:32:00+01:00"}
```

ESP32 an Pi:

```json
{"t":"hallo","fw":1}
{"t":"details","auto":"t5xxxxxxxxxxxxx"}
{"t":"rekorde","zeitraum":"jahr"}
{"t":"befehl","was":"pb_neustart","pin":""}
{"t":"befehl","was":"reboot","pin":""}
{"t":"befehl","was":"poweroff","pin":""}
{"t":"befehl","was":"backup","pin":""}
```

Ergänzungen ab Firmware `display-9` (alle additiv, abgestimmt in ABSTIMMUNG.md Runde 9):

| Richtung | Nachricht | Zweck |
|---|---|---|
| ESP32 an Pi | `hallo` hat zusätzlich `"drehung":0\|180` und `"variante":"cyd"` | aktuelle Bilddrehung, Board-Variante |
| Pi an ESP32 | `{"t":"einstellung","drehung":0\|180}` | Drehung setzen, Firmware speichert sie im NVS. Die Bridge schickt sie, wenn `DREHUNG` in `/etc/fahrtenbuch-display.env` gesetzt ist und das Display etwas anderes meldet |
| Pi an ESP32 | `{"t":"update","groesse":N,"md5":"..."}` | Firmware-Update starten |
| ESP32 an Pi | `{"t":"update","status":"bereit"\|"ack"\|"ok"\|"fehler","bytes":...,"text":...}` | Ablauf des Updates |
| Pi an ESP32 | `server` hat zusätzlich `backup_alter_h` und `backup_warnung` | Backup-Wächter in der Kopfzeile |

Ergänzungen ab Firmware mit Ansichten-Wechsel (Nachtplan Phase 6b, 2026-11-03, alle additiv; ältere Firmware ignoriert sie):

| Richtung | Nachricht | Zweck |
|---|---|---|
| Pi an ESP32 | `{"t":"letzte","id":"...","datum":"03.11.","zeit":"21:00","fahrer":"Admin","auto":"Kombi","farbe":"#ff3b30","km":12.5,"min":20,"schnitt":38,"max":90,"von":"...","nach":"...","zweck":"privat"}` | letzte beendete Fahrt (ohne Test-Fahrten) für die Kartenansicht. Ohne Fahrten nur `{"t":"letzte"}` |
| Pi an ESP32 | `{"t":"karte","id":"<fahrt-id>","w":320,"h":200,"farben":["#0b0f14","#2a3340","#4a5566","#ff3b30"],"teil":0,"von":16,"daten":"<base64>"}` | Karte der letzten Fahrt als Bild mit 4 Farben, 2 Bit pro Pixel (LVGL `I2`, MSB zuerst, 80 Byte pro Zeile, 16.000 Byte). Teile zu je 1.000 Byte, 0,15 s Pause dazwischen. Farbe 0 Grund, 1 Landes-/Kreisstraße, 2 Autobahn/Bundesstraße, 3 Route in der Autofarbe. Gerendert auf dem Pi (`karte.py`), Straßen einmal pro Fahrt von Overpass, zwischengespeichert in `/var/cache/fahrtenbuch-display` |
| ESP32 an Pi | `{"t":"karte","id":"...","status":"ok"}` | Karte vollständig angekommen, die Bridge schickt sie erst nach neuer Fahrt oder `hallo` wieder |
| ESP32 an Pi | `{"t":"karte","id":"...","status":"nochmal","teil":N}` | Teil N fehlt oder war kaputt, die Bridge schickt ab Teil N noch einmal. Ohne Antwort schickt sie eine Karte höchstens dreimal |
| Pi an ESP32 | `rekorde` hat zusätzlich `laengste_dauer` (`min`, `fahrer`, `auto`, `datum`), `tanken` und `laden` (je `einheit`, `groesste` mit `menge`, `billigste` und `teuerste` mit `preis` pro Liter bzw. kWh, jeweils `auto` und `datum`; Laden zuhause zählt nur bei der Menge) | Rekord-Ansicht |
| Pi an ESP32 | `{"t":"termine","d":[{"auto":"Kombi","art":"TÜV","datum":"01.12.26","tage":17,"bald":true}]}` | nächste 5 offene Termine (Roadmap Nr. 16), `bald` = in 30 Tagen oder überfällig (`tage` negativ) |
| ESP32 an Pi | `hallo` hat zusätzlich `"grund":"strom"\|"neustart"\|"absturz"\|"watchdog"\|"spannung"\|"anderer"` (ab Firmware mit Diagnose, Entwurf #82) | Grund des letzten Neustarts |
| ESP32 an Pi | `{"t":"log","text":"..."}` (ab Firmware mit Diagnose) | z. B. fehlgeschlagene LVGL-Prüfung vor dem Neustart, landet im Journal der Bridge |
| Pi an ESP32 | `{"t":"qr","text":"fahrtenbuch://verbinden?heim=…&tail=…&dl=…"}` | QR zum Verbinden der Apps (Roadmap Nr. 1/2, „Mehr > Handy verbinden“), Adressen aus `/api/fahrtenbuch/info` |
| ESP32 an Pi | `{"t":"befehl","was":"reset_code","email":"…","pin":"…"}` | Passwort vergessen: Bridge holt als Superuser einen Reset-Code (15 min). **Nur mit gesetzter PIN**, sonst `fehler` |
| Pi an ESP32 | `{"t":"reset_code","code":"ABCD2345EFGH","gueltig_bis":"…","text":"fahrtenbuch://verbinden?…&reset=…"}` | Display zeigt QR und Code, die App setzt damit ein neues Passwort |
| ESP32 an Pi | auf `details` antwortet die Bridge auch vor dem ersten Laden, notfalls mit `{"t":"fehler","was":"details","text":"Server nicht erreichbar"}` | |

Während eines Updates laufen Rohdaten über die Leitung, die Bridge muss dafür gestoppt sein. Das erledigt `display/tools/display_update.py` selbst.

Auf `hallo` (nach jedem Neustart des ESP32) schickt die Bridge sofort alle Daten. Jeden Befehl beantwortet sie mit `{"t":"ok","was":"..."}` oder `{"t":"fehler","text":"..."}`, bei `poweroff` zuerst mit `{"t":"aus"}`, damit das Display "Jetzt kann der Stecker gezogen werden" zeigt. Kommt 90 Sekunden lang nichts von der Bridge, zeigt das Display "Server nicht erreichbar".

### 2.6 Firmware für den ESP32

**Zuständig ist die PC-Session** (Entscheidung von Admin, 2026-11-03): Branch `display`, Ordner `display/`, Workflow, Releases `display-N` und Flashen. Die Cloud ändert `display/` nicht. Protokolländerungen werden in ABSTIMMUNG.md abgestimmt. Updates laufen inzwischen vom Pi aus über die UART-Leitung (`display/tools/display_update.py`), USB ist nur noch für den Notfall nötig.

- PlatformIO mit Arduino-Framework, Grafik mit **LVGL** (fertige Reiter, Listen, Balkendiagramme, QR-Code-Widget) auf TFT_eSPI, Touch mit der XPT2046-Bibliothek, JSON mit ArduinoJson
- Ordner `display/` auf einem neuen Branch `display`
- GitHub Action baut die Firmware bei jedem Push und legt ein Release `display-N` mit einer fertigen `firmware.bin` an (wie bei iOS und Android)

Flashen per USB vom Linux-PC (oder vom Pi):

```bash
pipx install esptool
esptool --chip esp32 --port /dev/ttyUSB0 write_flash 0x0 firmware.bin
```

Auf Bazzite ggf. in der Distrobox und mit Zugriff auf `/dev/ttyUSB0` (Benutzer in Gruppe `dialout`), Details kommen in [PC-AUFGABEN.md](PC-AUFGABEN.md), sobald es so weit ist.

## Reihenfolge

1. **Hardware:** SSD und Netzteil besorgen, falls nicht da. Der Pi hat 4 oder 8 GB RAM, das ist mehr als genug.
2. **Server-PR:** PocketBase-Version im `docker-compose.yml` festnageln.
3. **Pi aufsetzen und umziehen** (Teil 1), Apps auf neue Adresse umstellen, eine Woche laufen lassen. HP als Reserve aufheben.
4. **display-bridge** schreiben und zuerst ohne Display testen (Ausgabe ins Log).
5. **Firmware V1:** Übersicht, letzte Fahrten, Server-Menü; Display per USB anschließen (Variante B).
6. **GPIO-Verkabelung** (Variante A), Bridge auf `/dev/serial0` umstellen.
7. **Firmware V2:** Rekorde, Detailseite pro Auto mit Diagramm, Kosten, QR-Code, Nachtmodus.

Schritt 3 geht unabhängig vom Display. Der Pi kann also schon Server sein, bevor das Display fertig ist.

## Entschieden

- Display: ESP32-2432S028R, Anbindung per UART über GPIO (Stecker "UART"/`P1`), Variante `cyd`.
- Firmware: PC-Session. display-bridge, Server, Apps: Cloud.
- Kein km-Limit. Das Display ist nur zum Anschauen plus Server-Menü, es schreibt nichts in die Datenbank.
- Pi 4B mit 4 oder 8 GB RAM.

## Noch offen

- SSD vorhanden oder besorgen?
- Steht der Pi in Kabelnähe zur FritzBox?
- Soll der HP danach ganz frei werden, oder als Backup-Ziel weiterlaufen?
