# Display-Firmware (ESP32-2432S028R)

Firmware für das 2,8-Zoll-Touch-Display am Raspberry Pi. Das Display zeigt, was die display-bridge auf dem Pi schickt (`server/display-bridge` auf Branch `server`), und schickt Anfragen und Befehle aus dem Server-Menü zurück. Plan und Protokoll: `docs/RASPBERRY-PI.md` auf `main`, Teil 2.

## Seiten

- **Übersicht:** pro Auto km im Monat und im Jahr, km-Stand, laufende Fahrten. Auto antippen öffnet die Detailseite (km pro Monat als Balkendiagramm, km pro Fahrer, Kosten).
- **Fahrten:** die letzten 20 Fahrten, antippen zeigt Details.
- **Rekorde:** längste Fahrten, umschaltbar Monat, Jahr, gesamt, dazu längste pro Auto, schnellste Fahrt, meiste km.
- **Mehr:** Kosten pro Auto (Diesel in Liter, E-Auto in kWh), Server-Infos, Adresse für die Apps als QR-Code, Server-Menü.

Server-Menü: PocketBase neu starten, Backup jetzt, Pi neu starten, Pi ausschalten. Jeder Knopf muss 3 Sekunden gehalten werden, Neustart und Ausschalten fragen danach noch einmal nach. Ist auf dem Pi eine PIN gesetzt, oben ins Feld "PIN" eintippen.

Nach 2 Minuten ohne Berührung geht es zurück zur Übersicht, von 22 bis 6 Uhr wird das Display gedimmt. Kommt 90 Sekunden nichts vom Pi, steht oben "Server nicht erreichbar".

## Bild drehen

Steht das Bild auf dem Kopf, im Server-Menü auf **„Bild drehen"** tippen (kein Halten nötig). Bild und Touch drehen sich um 180°. Die Einstellung bleibt im Display gespeichert und gilt nach einem Neustart sofort, auch ohne Pi. Alternativ kann der Pi die Drehung setzen:

```json
{"t":"einstellung","drehung":180}
```

`0` = quer mit USB-Anschluss rechts (Standard), `180` = auf dem Kopf. Das Display meldet die aktuelle Drehung im `hallo` mit (`"drehung":0|180`).

## Welche Datei?

Die GitHub Action baut bei jedem Push auf `display` ein Release `display-N` mit drei Dateien:

| Datei | Board |
|---|---|
| `fahrtenbuch-display-cyd.bin` | ein USB-Anschluss (Micro-USB). Zuerst diese probieren. |
| `fahrtenbuch-display-cyd_invertiert.bin` | wie oben, falls die Farben invertiert aussehen |
| `fahrtenbuch-display-cyd2usb.bin` | zwei USB-Anschlüsse (Micro-USB und USB-C) |

Beim Start zeigt das Display 2,5 Sekunden lang drei Farbfelder mit der Beschriftung Rot, Grün, Blau. Passen Farbe und Beschriftung nicht zusammen, die nächste Datei flashen.

## Update vom Pi (normaler Weg, ohne Knöpfe und ohne USB)

Ab dieser Version nimmt die Firmware Updates über dieselbe Leitung an, über die sie mit dem Pi redet (GPIO an `CN1` oder USB). Auf dem Pi:

```bash
# App-Datei aus dem Release display-N auf den Pi holen (Endung -app.bin!)
sudo python3 display_update.py fahrtenbuch-display-cyd-app.bin            # GPIO, /dev/serial0
sudo python3 display_update.py fahrtenbuch-display-cyd-app.bin --port /dev/ttyUSB0
```

Das Werkzeug liegt im Release und unter `display/tools/`. Es stoppt die display-bridge kurz, schickt die Firmware in 1-KB-Blöcken (etwa 1–2 Minuten), das Display zeigt dabei den Fortschritt. Die neue Firmware landet im zweiten App-Slot und wird erst nach geprüfter MD5-Summe aktiv. Bricht etwas ab, läuft die alte einfach weiter. Danach startet das Werkzeug die Bridge wieder.

Gehen auf der Leitung Bytes verloren, fordert das Display den betroffenen Block neu an (`"status":"nochmal"`). Scheitert ein ganzer Durchlauf, versucht das Werkzeug es bis zu dreimal. Mit `--build N` prüft es nach dem Neustart, dass wirklich `display-N` läuft. Die Build-Nummer steht auch auf dem Display unter „Mehr".

Die Partitionstabelle hat dafür zwei App-Slots (`min_spiffs.csv`). Ältere Firmware ohne Update-Empfang (ohne `-app.bin` im Release) hatte nur einen, deshalb muss **einmal** per esptool geflasht werden (nächster Abschnitt). Danach nie wieder.

## Flashen per esptool (Erstinstallation oder Notfall)

5V-Kabel vom Pi abziehen, Display per USB anstecken.

```bash
pipx install esptool        # Bazzite: in der Distrobox, Benutzer in Gruppe dialout
esptool --chip esp32 --port /dev/ttyUSB0 write_flash 0x0 fahrtenbuch-display-cyd.bin
```

Klappt das Verbinden nicht: Taste `BOOT` gedrückt halten, kurz `RST` drücken, `BOOT` loslassen, nochmal versuchen.

**Vom Pi über die Pins, ohne USB:** Der Flash-Zugang (Bootloader) des ESP32 liegt an `P1` (TX/RX), nicht an `CN1`. Datenkabel dafür an `P1` stecken: Pi-Pin 8 (TX) → `P1` RX, Pi-Pin 10 (RX) → `P1` TX. Die Firmware redet auch über `P1` mit dem Pi, die Kabel können also dort bleiben. Dann `BOOT` halten, `RST` tippen, `BOOT` loslassen und auf dem Pi:

```bash
python3 -m venv ~/.venv-esptool && ~/.venv-esptool/bin/pip install esptool   # einmalig, das Debian-esptool hat keine Stub-Dateien
sudo systemctl stop fahrtenbuch-display
~/.venv-esptool/bin/esptool --chip esp32 --port /dev/serial0 --before no_reset --after no_reset \
  write_flash 0x0 fahrtenbuch-display-cyd.bin
sudo systemctl start fahrtenbuch-display    # danach RST am Display drücken
```

## Anschluss

- **USB-Kabel zum Pi** (Variante B): Strom und Daten, auf dem Pi `SERIAL_PORT=/dev/ttyUSB0`.
- **GPIO** (Variante A): Strom über `P1`, Daten über `CN1` (GPIO27 = RX, GPIO22 = TX), auf dem Pi `SERIAL_PORT=/dev/serial0`. Verkabelung in `docs/RASPBERRY-PI.md` 2.1.

Die Firmware hört auf beiden Wegen gleichzeitig, dieselbe Datei geht für beide.

## Touch stimmt nicht

Liegt der Touchpunkt daneben, die Werte `TOUCH_X_MIN/MAX` und `TOUCH_Y_MIN/MAX` oben in `src/main.cpp` anpassen.

## Selbst bauen

```bash
pip install platformio
cd display && pio run -e cyd
```

Die Schriften in `src/fonts` (Montserrat mit Umlauten und Euro-Zeichen) sind mit `lv_font_conv` 1.5.2 erzeugt (`--bpp 4 --size 12/14/20 -r 0x20-0x7E,0xA0-0xFF,0x20AC,0x2013,0x2192`, dazu die LVGL-Symbole aus FontAwesome).
