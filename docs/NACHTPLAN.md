# Gesamtplan und Nachtplan (Stand 2026-11-03, 22 Uhr)

Diese Datei hat zwei Teile:

1. **Bestandsaufnahme:** Was jeder Thread gebaut hat, wie die Teile zusammenhängen, wo es Widersprüche und Lücken gibt.
2. **Nachtplan für die PC-Session** (Claude Code auf des Admins Linux-PC, mit SSH auf den Pi und adb auf das Android-Testhandy): alles auf den aktuellen Stand bringen, Ende-zu-Ende testen, Fehler selbst beheben, mergen, protokollieren.

Protokoll der Nacht: in `docs/ABSTIMMUNG.md` als **"## Runde 12: PC (Nacht)"**.

---

## Teil 1: Bestandsaufnahme

### Was jeder Thread gemacht hat

| Thread | Ergebnis | PRs | Stand |
|---|---|---|---|
| Fahrtenbuch-App bauen | Android (Kotlin, Room) und iOS (SwiftUI, SwiftData), offline-first mit PocketBase-Sync, lokaler Modus ohne Server, Laden für den E-Auto (kWh, zuhause = 0 €), Diesel für Kombi und Bus, zwei Server-Adressen (Heimnetz zuerst, dann Tailscale), Offline-Hinweis | #2 bis #30 | gemergt |
| Alle Teile auf Fehler prüfen | Selbstabbruch beim Auto-Stopp, Doppelstart, Lücken-Halt, Sync-Verluste, PocketBase auf 0.40.6 gepinnt, Hook `km_stand.pb.js`: km-Stand über die Apps nur steigend | #15 bis #18, #23 | gemergt, auf dem Pi |
| Feature-Ideen | Fahrten bearbeiten, teilen, zusammenlegen, Tacho-Abgleich, Karte, Stau-Erkennung (Bewegung), Jahresstatistik, Ausgleichszahlungen (`ausgleich`), Prüf-Hook `plausibel.pb.js`, Backup-Wächter, Update-Hinweis | #31 bis #35 | gemergt, auf dem Pi |
| Abstimmung Cloud und PC (dieser Thread) | display-bridge (sudo statt docker-Gruppe, Drehung, leises Warten), Test-Modus mit Diagnose in Server, Bridge, Android, iOS | #26, #38 bis #44 | gemergt, auf dem Pi geprüft (Runde 11) |
| Display (PC-Session) | Firmware `display-13`: Drehung 180°, Update über UART ohne Knöpfe, UI-Korrekturen, Build-Nummer im `hallo` | #27, #32, #34, #36, #37, #41 | läuft am Pi |
| Updates und Bedienung idiotensicher | Branch `downloads` mit neuester APK/IPA, `abholen.py` auf dem Pi (Timer alle 10 min, Port 8091, SideStore-Quelle `apps.json`), Android Ein-Klick-Update, iOS-Version `1.0.<Build>`, Einrichtung prüft Berechtigungen erneut, Start-Passwort-Dialog (`users.passwort_aendern`, Migration `1762500000`) | #45 bis #48, #51 bis #53 | gemergt, Migration auf dem Pi |
| App-Logo | Neues Icon "G" (Strecke A nach B), SideStore-Icon mit Fingerabdruck in der Adresse gegen den Bild-Cache (#55), Quellen-Icon und `tintColor` `FF3B30` in der SideStore-Quelle (#62) | #49, #50, #55, #62 | gemergt, **#62 noch nicht auf dem Pi** |
| Passwort-Manager (im Thread "Updates idiotensicher") | Android: Autofill-Typen und "Passwort speichern?" (#59), iOS: `textContentType` für die Passwörter-App (#60), Hinweis "Passwort vergessen", Reset über die Admin-Oberfläche (#61) | #59, #60, #61 | #60, #61 gemergt, #59 Stand 22:20 noch offen |
| Tankbelege und Tacho scannen | Belegfoto am Tank- und Ladeeintrag (Migration `1762600000_beleg`), Erkennung auf dem Handy (Android ML Kit, iOS Vision), Tacho-Foto mit Rückfrage | #54, #56, #57 | gemergt, **Migration noch nicht auf dem Pi** |
| E-Auto-API | Recherche: nur inoffizielle API (km-Stand, Ladevorgänge), Zweitkonto empfohlen | keine | **wartet auf des Admins Entscheidung**, nicht Teil der Nacht |
| Raspberry Pi mit Display planen | Plan `docs/RASPBERRY-PI.md`, Umzug vom HP auf den Pi | #9, #10, #39 | erledigt, Display läuft |

### Aktueller Stand der Branches

| Branch | Inhalt | Neuester Stand |
|---|---|---|
| `main` | Doku (`docs/`), Abstimmung | Runde 11 PC mit 17 Android-Befunden, Cloud war dran |
| `server` | PocketBase (Docker), 7 Migrationen bis `1762600000_beleg`, 4 Hooks, `display-bridge/`, `downloads/`, `test_server.sh` | #54 |
| `android` | App, Room-Version 4 plus Beleg | #56, Build `android-37` (1.0.37) |
| `ios` | App, SwiftData | #57, Build `ios-25` (1.0.25) |
| `display` | Firmware (gehört der PC-Session) | `display-13` |
| `downloads` | nur die neueste APK/IPA mit `android.json`/`ios.json` | APK 37, IPA 25 |

Der Pi hat `server` ausgecheckt (`~/Fahrtenbuch-App`).

### Widersprüche und Lücken

1. **17 Befunde aus dem adb-Test (Runde 11, Nachtrag) sind offen.** Getestet wurde 1.0.31, die Befunde gelten auch für 1.0.37. Die meisten betreffen vermutlich auch iOS (Nr. 2, 3, 5, 6, 8, 11, 14, 16).
2. **Migration `1762600000_beleg` fehlt auf dem Pi.** Bis dahin bleiben Belegfotos auf den Handys und werden später hochgeladen.
3. **Plan in Runde 10 sagte "Hook km_stand ignoriert Test-Fahrten".** Korrigiert im Nachtrag: Das machen die Apps. In Runde 11 auf dem Pi bestätigt. Kein Handlungsbedarf.
4. **Unterschiedliche Befehle für den Server-Neustart** in den Threads (`docker compose restart pocketbase` und `docker compose up -d`). Beide spielen Migrationen ein. Einheitlich ab jetzt: `docker compose up -d`, weil es auch Änderungen an `docker-compose.yml` übernimmt.
5. **`main` enthält noch eine veraltete Kopie der iOS-App unter `ios/`** samt `ios.yml`. Gebaut wird nur auf `ios`. Die Kopie verwirrt und sollte von `main` entfernt werden.
6. **iOS wurde nie per Oberfläche getestet**, nur mit Logiktests in CI. Ohne Mac geht das nachts nicht. Admin testet morgens am iPhone (Liste in Teil 2, Phase 8).
7. **Bridge-Hinweise aus Runde 11** offen: Log-Lärm durch `sudo docker inspect` alle 30 s (ca. 8.600 Journal-Zeilen pro Tag), `details` vor dem ersten Laden ohne Antwort.
8. **Viele alte Arbeitsbranches** (`claude/project-thread-*`, `fw-*`). Schaden nicht, Aufräumen nur nach Rückfrage beim Admin.
9. **Display zeigt nur Übersicht und Menü.** Admin wünscht wechselnde, schön gemachte Ansichten (Karte der letzten Fahrt im Nachtmodus, Rekorde, Zusammenfassung). Siehe Phase 6b.
9b. **Thread "Raspberry Pi planen" steht als blockiert**, ist aber erledigt: Server und Display laufen auf dem Pi.
10. **Fahrten unterwegs:** Eine laufende Fahrt wird lokal jede Minute gespeichert, aber erst beim Start und am Ende hochgeladen (Android zusätzlich alle 30 min über WorkManager, iOS gar nicht zwischendurch). Unterwegs sehen die anderen also nur "unterwegs seit ...", nicht die Strecke, und bei einem Absturz fehlt auf dem Server alles seit dem Start. Siehe Phase 4b.
11. **Offen beim Admin:** Annas Android (Ein-Klick-Update ab 1.0.31), Bens iPhone (SideStore), echte km-Stände eintragen, E-Auto-Entscheidung.

---

## Teil 2: Nachtplan für die PC-Session

### Regeln für die Nacht

- **Die PC-Session darf heute Nacht alle Branches ändern** (`main`, `server`, `android`, `ios`, `display`). Die Cloud ändert nichts, bis unten in `ABSTIMMUNG.md` wieder "## Cloud ist dran" steht.
- **Admin erlaubt ausdrücklich (2026-11-03, 22:12):** automatisch mergen und alles tun, was PC oder Pi nicht schadet. Dazu gehört auch, systemd-Dienste und -Timer auf dem Pi zu erstellen, zu aktualisieren und neu zu starten, `install.sh`-Skripte mit `sudo` auszuführen und Pakete für Tests zu installieren. Nicht erlaubt bleibt alles, was Daten oder Systeme gefährdet: Pi neu starten oder ausschalten, Partitionen, Firewall, SSH- oder Tailscale-Konfiguration ändern, echte Daten löschen.
- Jede Änderung als eigener Branch mit PR auf den passenden Basis-Branch, Squash-Merge **nach grüner CI** ohne Rückfrage. Kein Force-Push auf `main`, `server`, `android`, `ios`, `display`.
- Ein Merge auf `android` oder `ios` erzeugt ein neues Release und bietet es nach 10 Minuten über den Pi allen Handys an. Deshalb Fixes sammeln und pro App höchstens zwei- bis dreimal in der Nacht mergen, jeweils erst nach dem eigenen Test.
- **Nur Test-Daten:** Fahrten und Tankungen ausschließlich im Test-Modus. Echte Datensätze nie ändern oder löschen. Aufräumen nur über `geloescht=true` bzw. "Alle Test-Fahrten löschen".
- **Keine Passwörter ändern**, auch nicht über den Start-Passwort-Dialog (dort "Später" wählen). Keine Passwörter, Tokens oder Schlüssel in Repo, Logs oder `ABSTIMMUNG.md`.
- **Pi nicht neu starten und nicht ausschalten**, Display-Menüpunkte D5 Neustart und Aus nicht auslösen. PocketBase-Neustart nur über `docker compose up -d` nach einem Backup.
- Vor jeder Server-Änderung ein Backup (Phase 1).
- **Anhalten nur**, wenn Admin physisch etwas tun muss (Handy entsperren, Kabel, Freigabe) oder ein Schritt echte Daten gefährden würde. Dann den Grund unter "Offen für Admin" eintragen und mit dem nächsten unabhängigen Punkt weitermachen.
- Blockiert die Rechteprüfung der PC-Session trotzdem einen Schritt, ihn nicht umgehen: als "Offen für Admin" mit dem genauen Befehl eintragen und weitermachen.
- Nach jeder Phase das Protokoll in `ABSTIMMUNG.md` ergänzen, committen und nach `main` pushen.
- **Wächter bis 08:00 (Nachtrag 22:45):** Der Wächter, den Admin eingerichtet hat, läuft die ganze Nacht bis 08:00 Uhr weiter, alle 5 Minuten (nicht nur 30 Minuten). Er prüft neue Commits auf `main` und neue PRs. Ändern sich `docs/NACHTPLAN.md`, `docs/ROADMAP.md` oder `docs/ABSTIMMUNG.md`, macht die PC-Session `git pull` und nimmt die neuen Punkte in ihre Reihenfolge auf, ohne die laufende Phase abzubrechen.
- **Nicht vor 08:00 aufhören:** Ist eine Phase fertig oder blockiert, sofort mit der nächsten unabhängigen weitermachen. Sind alle Phasen durch, mit der Liste aus Phase 6c Punkt 11 weitermachen. Mindestens alle 2 Stunden eine kurze Zeile "Stand HH:MM" ins Protokoll schreiben und pushen, damit morgens sichtbar ist, wie weit die Nacht gekommen ist.

### Vor dem Schlafengehen (Admin, 5 Minuten)

1. Android-Testhandy per USB am PC, am Ladegerät, Bildschirmsperre für die Nacht aus (Einstellungen > Sicherheit > Displaysperre "Keine"), USB-Debugging an.
2. PC darf nicht in den Ruhezustand. Die PC-Session startet ihre Arbeit dafür mit `systemd-inhibit` (siehe Phase 0).
3. Fahrtenbuch-App auf dem Testhandy angemeldet (des Admins Konto), Test-Modus darf an.
4. Die Claude-Code-Session auf dem PC starten und den Satz am Ende dieser Datei einfügen.

### Phase 0: Start und Überblick

```bash
cd ~/Fahrtenbuch-App
git fetch --all --prune
git checkout main && git pull
adb devices -l                      # Testhandy muss als "device" erscheinen
adb shell svc power stayon usb      # Bildschirm bleibt an, solange USB steckt
ssh admin@fahrtenbuch.local 'cd ~/Fahrtenbuch-App && git status -sb && git log --oneline -3'
```

Lange Läufe mit `systemd-inhibit --what=sleep:idle --why="Fahrtenbuch Nachtplan" <befehl>` starten, damit der PC wach bleibt.

### Phase 1: Pi auf den aktuellen Stand

```bash
ssh admin@fahrtenbuch.local
cd ~/Fahrtenbuch-App/server
docker exec fahrtenbuch ls /pb_data/backups 2>/dev/null | tail -3
```

Backup über die PocketBase-API anlegen. Der Display-Benutzer ist Superuser, sein Zugang steht nur in `/etc/fahrtenbuch-display.env` (nie ausgeben, nie ins Repo):

```bash
sudo bash -c 'set -a; . /etc/fahrtenbuch-display.env; python3 - <<PY
import json, os, urllib.request
u = os.environ["PB_URL"]
def post(pfad, daten, token=None):
    r = urllib.request.Request(u + pfad, json.dumps(daten).encode(), {"Content-Type": "application/json"})
    if token: r.add_header("Authorization", token)
    return urllib.request.urlopen(r).read()
t = json.loads(post("/api/collections/_superusers/auth-with-password",
                    {"identity": os.environ["PB_EMAIL"], "password": os.environ["PB_PASSWORD"]}))["token"]
post("/api/backups", {"name": "vor_nacht_20261103.zip"}, t)
print("Backup angelegt")
PY'
docker exec fahrtenbuch ls -la /pb_data/backups | tail -3
```

Erwartet: `vor_nacht_20261103.zip` mit einigen hundert KB. Dann:

```bash
cd ~/Fahrtenbuch-App && git pull
cd server && docker compose up -d
sleep 10
docker logs fahrtenbuch --tail 20
curl -s http://localhost:8090/api/health; echo
sudo python3 -c "import sqlite3;c=sqlite3.connect('file:/home/admin/Fahrtenbuch-App/server/pb_data/data.db?mode=ro',uri=True);print(c.execute('select file from _migrations order by applied desc limit 3').fetchall())"
```

Erwartet: `1762600000_beleg.js` steht oben, Health 200.

Downloads und Bridge:

```bash
sudo systemctl start fahrtenbuch-downloads.service
journalctl -u fahrtenbuch-downloads -n 5 --no-pager -o cat
curl -s http://localhost:8091/android.json; echo
curl -s http://localhost:8091/apps.json | python3 -c "import sys,json;a=json.load(sys.stdin)['apps'][0];print(a['version'],a['downloadURL'])"
systemctl status fahrtenbuch-display --no-pager | head -5
```

Erwartet: Android `versionCode` = neuester Build auf `downloads`, iOS-Version = neuester `ios-N`. Bridge `active (running)`. Hat sich `server/display-bridge/` seit der letzten Installation geändert, `cd ~/Fahrtenbuch-App/server/display-bridge && sudo ./install.sh` (bei Blockade: für Admin eintragen).

### Phase 2: Builds und CI prüfen

Für jeden Branch (`server`, `android`, `ios`, `display`) den letzten Workflow-Lauf prüfen. Ist einer rot, zuerst diesen Fehler beheben (eigener PR), bevor getestet wird.

```bash
gh run list --branch android --limit 3
gh run list --branch ios --limit 3
gh run list --branch server --limit 3
gh run list --branch display --limit 3
```

Lokal auf dem PC (x86):

```bash
cd ~/Fahrtenbuch-App && git checkout server && git pull
cd server && ./test_server.sh
cd display-bridge && python3 -m unittest -v test_bridge
cd ../downloads && python3 -m unittest -v test_abholen
cd ~/Fahrtenbuch-App && git checkout main
```

### Phase 3: Server-API und Sync (nur Test-Daten)

Mit des Admins Konto (Token aus der lokalen Umgebung) gegen `http://fahrtenbuch.local:8090`:

1. Test-Fahrt mit `"test": true` und `diagnose` anlegen: HTTP 200, km-Stand des Autos unverändert.
2. Test-Tankung mit `"test": true` und Belegfoto (multipart, Feld `beleg`, kleines JPEG): HTTP 200. Das Foto ist geschützt: ohne Token muss `/api/files/tankvorgang/<id>/<datei>` scheitern, mit Datei-Token (`POST /api/files/token`, dann `?token=...`) klappen.
3. Prüf-Hook: Tankung mit 500 Litern muss abgelehnt werden (HTTP 400).
4. km-Stand-Hook: Senken des km-Stands als normaler Benutzer muss scheitern oder unverändert bleiben.
5. Bridge-Trockenlauf zeigt keine der Test-Daten:
   ```bash
   ssh admin@fahrtenbuch.local "sudo -u fbdisplay bash -c 'set -a; . /etc/fahrtenbuch-display.env; python3 /opt/fahrtenbuch-display/bridge.py --trocken --einmal'"
   ```
6. Danach alle eigenen Test-Datensätze `geloescht=true`.

### Phase 4: Android Ende-zu-Ende per adb

Neueste APK vom Pi holen und prüfen:

```bash
mkdir -p ~/fb-test && cd ~/fb-test
curl -s http://fahrtenbuch.local:8091/android.json -o android.json
DATEI=$(python3 -c "import json;print(json.load(open('android.json'))['datei'])")
curl -sO "http://fahrtenbuch.local:8091/$DATEI"
python3 -c "import json,hashlib;j=json.load(open('android.json'));print('ok' if hashlib.sha256(open(j['datei'],'rb').read()).hexdigest()==j['sha256'] else 'FALSCH')"
adb install -r "$DATEI"
adb shell am start -n de.hjorven.fahrtenbuch/.MainActivity
```

Werkzeuge pro Schritt:

```bash
adb exec-out screencap -p > schritt.png                 # Bildschirm ansehen
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml   # Knöpfe und Koordinaten
adb shell input tap X Y                                  # tippen
adb shell input text 'Hallo'                             # Text (Leerzeichen als %s)
adb shell input keyevent KEYCODE_BACK                    # zurück
adb logcat -c; adb logcat -d -b crash; adb logcat -d '*:E' | grep -i fahrtenbuch
```

Fahrt ohne Auto simulieren (Android 15, erst ausprobieren):

```bash
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider gps
adb shell cmd location providers set-test-provider-enabled gps true
# alle 3 s einen Punkt weiter, z. B. 0,0003 Grad Breite = ca. 33 m
adb shell cmd location providers set-test-provider-location gps --location 52.5200,13.4050 --accuracy 5
# ... am Ende:
adb shell cmd location providers remove-test-provider gps
```

Nimmt der Fused-Provider die Testpositionen nicht an, die Fahrt-Logik nur mit Start/Stopp ohne Strecke testen und das im Protokoll vermerken.

Abläufe (jeder mit Screenshot und Logcat ohne Fehler):

1. **Einrichtung:** App-Daten löschen (`adb shell pm clear de.hjorven.fahrtenbuch`), Einrichtung komplett durchlaufen, alle Berechtigungen. Danach erneut anmelden. Vorher prüfen, dass keine ungesyncten Daten auf dem Handy liegen.
2. **Login und Start-Passwort:** Dialog erscheint, "Später" wählen. Passwort nicht ändern. **Passwort-Manager** (wird gerade im Thread "Updates und Bedienung idiotensicher" gebaut): Sobald der Build da ist, prüfen, dass Login-Felder als E-Mail und Passwort erkannt werden (Autofill-Hinweise im UI-Baum, Google-Passwortmanager bietet Speichern an) und "Passwort vergessen" funktioniert, falls eingebaut. Auf iOS morgens Admin (Phase 8).
3. **Test-Modus:** an, Banner in allen Menüs, Aufnahme-Rate wechseln.
4. **Fahrt Start/Stopp:** per Knopf, per Benachrichtigung, Auto-Ende nach Stillstand (Auto-Stopp auf 3 min stellen), mit Testpositionen. Diagnose prüfen und exportieren.
5. **Tanken (Kombi, Diesel) und Laden (E-Auto, zuhause und öffentlich):** Plausibilität, Gesamtpreis, Test-Kennzeichnung.
6. **Beleg-Scan und Tacho-Scan:** Kamera zeigt nachts nichts Sinnvolles. Wenn die App eine Galerie-Auswahl hat, ein Beispielbild hineinlegen:
   ```bash
   adb push beleg.jpg /sdcard/Pictures/beleg.jpg
   adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/beleg.jpg
   ```
   Sonst nur prüfen, dass Kamera-Dialog und Abbrechen sauber funktionieren.
7. **Update-Knopf:** "Nach Updates suchen". Nach einem eigenen Merge in der Nacht muss die neue Version angeboten und per Ein-Klick installiert werden.
8. **Offline und Tailscale:** `adb shell svc wifi disable` (adb läuft über USB weiter): Verbindung über Tailscale bzw. Offline-Hinweis, Eintrag offline anlegen, `adb shell svc wifi enable`, Eintrag muss hochgeladen werden. "Zuletzt synchronisiert" prüfen (Befund 3).
9. **Bearbeiten, Teilen, Zusammenlegen** einer Test-Fahrt, Statistik und Karte (Test-Daten dürfen nicht zählen).
10. **Aufräumen:** "Alle Test-Fahrten löschen", Server prüfen, Testpositionen entfernen.

### Phase 4b: Logo und SideStore-Quelle (Nachtrag 22:20)

Auf dem Pi die Downloads neu installieren (PR #62) und die Quelle prüfen:

```bash
ssh admin@fahrtenbuch.local
cd ~/Fahrtenbuch-App && git pull
cd server/downloads && sudo ./install.sh
rm -f ~/ipas/.stand && sudo systemctl start fahrtenbuch-downloads.service
curl -s http://localhost:8091/apps.json | python3 -c "import sys,json;d=json.load(sys.stdin);a=d['apps'][0];print('Quelle', d.get('iconURL'), d.get('tintColor'));print('App', a.get('iconURL'), a.get('tintColor'), a['version'])"
```

Erwartet: Quelle und App haben eine `iconURL` mit `?v=<Fingerabdruck>`, `tintColor` ist `FF3B30`, Version = neuester iOS-Build. Das Icon unter der Adresse muss das neue "G" sein (`curl -s -o icon.png "<iconURL>"`, ansehen). Auf dem Android-Testhandy: neues Icon im Launcher nach dem Update.

### Phase 4c: Tailscale und Fahrten unterwegs (Nachtrag 22:20)

**Prüfen:**

```bash
ssh admin@fahrtenbuch.local 'tailscale status | head -5; tailscale ip -4'
adb shell dumpsys connectivity | grep -i -m3 tailscale    # VPN aktiv auf dem Testhandy?
```

Pi und Testhandy müssen im Tailnet sein. Fehlt Tailscale auf dem Handy oder ist es abgemeldet: unter "Offen für Admin" eintragen (Anmeldung braucht ihn), Rest weiter.

1. **Fallback Heimnetz nach Tailscale:** In der App beide Adressen gesetzt (Heimnetz `http://fahrtenbuch.local:8090`, Tailscale `http://fahrtenbuch.<tailnet>.ts.net:8090`). `adb shell svc wifi disable`, mobile Daten an (`adb shell svc data enable`), Tailscale-App verbunden. Sync auslösen: Einstellungen müssen "Verbunden über Tailscale" zeigen, ein Test-Eintrag kommt auf dem Server an. Danach Tailscale-App trennen: Offline-Hinweis muss erscheinen, Eintrag bleibt lokal und wird nach `adb shell svc wifi enable` hochgeladen.
2. **Updates unterwegs:** `android.json` über Tailscale abrufbar (`http://fahrtenbuch.<tailnet>.ts.net:8091/android.json` vom Handy aus, z. B. "Nach Updates suchen" bei WLAN aus). Gleiches gilt für die SideStore-Quelle, falls SideStore die Tailscale-Adresse nutzt.
3. **Laufende Fahrt zwischendurch hochladen (fehlt, bauen):**
   - Android: Im Ticker von `TripService` nach `zwischenspeichern()` höchstens alle 2 Minuten `SyncWorker.jetzt(this)` auslösen (nur wenn Netz da ist, der Worker hat die Netz-Bedingung schon). Der Sync darf die laufende Fahrt nicht als sauber markieren, wenn sie sich während des Uploads geändert hat (die Prüfung `aktuell == f` in `SyncManager.hochladen` gibt es schon).
   - iOS: In `FahrtRecorder.zwischenspeichern()` höchstens alle 2 Minuten `Task { await SyncManager.shared.sync() }`.
   - Server und Bridge kommen damit schon klar (`ende` leer = läuft, Bridge zeigt laufende Fahrten bis 12 h).
   - Test: Fahrt mit Testpositionen (Phase 4) 5 Minuten laufen lassen, auf dem Server muss `strecke_km` und `route` während der Fahrt wachsen, am Display bzw. in der App eines anderen Kontos "unterwegs" stehen. Mit WLAN aus über Tailscale wiederholen.
   - Akku: Im Test-Modus die Diagnose mit und ohne Zwischen-Upload vergleichen und im Protokoll festhalten.
4. **Passwort-Manager:** PR #59 (Android) muss gemergt sein, sonst nach grüner CI mergen. Dann den Testpunkt aus Phase 4, Ablauf 2 ausführen.

### Phase 5: Fehler beheben

Reihenfolge:

1. Alle neuen Fehler aus Phase 2 bis 4.
2. Die 17 Befunde aus Runde 11 (Android), wichtigste zuerst: 6 (Tacho-Plausibilität), 5 (ungespeicherte Änderungen), 3 (letzter Sync), 2 (falsches "nachgetragen"), 1 (Diagnose-Layout), 4 (Auto-Auswahl abgeschnitten), dann 7 bis 17.
3. Dieselben Punkte auf iOS prüfen und beheben, soweit sie dort auch vorkommen (vor allem 2, 3, 5, 6, 8, 11, 14, 16). iOS nur über CI testbar: Logiktests ergänzen, wo es geht.
4. Bridge: Image-Version nur einmal und nach `pb_neustart` lesen (Log-Lärm), `details` vor dem ersten Laden beantworten. Tests in `test_bridge.py` ergänzen.
5. Veraltete iOS-Kopie `ios/` und `.github/workflows/ios.yml` von `main` entfernen (vorher prüfen, dass nichts darauf verweist).

Pro Fix: Branch, Test (lokal bzw. CI), PR, CI grün, Squash-Merge, neue APK installieren und den betroffenen Ablauf aus Phase 4 wiederholen.

### Phase 5b: Randfälle und Worst-Case-Szenarien (Nachtrag 22:50)

Admin wünscht, dass ungewöhnliche Situationen und schlimmste Fälle gezielt geprüft werden. Diese Phase kommt direkt nach Phase 5 und vor Phase 6. Jeder Fall bekommt im Protokoll eine Zeile mit "ok", "Fehler gefunden (PR)" oder "nicht prüfbar (Grund)". Ein gefundener Fehler wird wie in Phase 5 behoben und bekommt einen Logik- oder `test_server.sh`-Test, damit er nicht wiederkommt. Es gelten die Regeln für die Nacht: nur Testfahrten, kein Neustart des Pi, kein Eingriff in Firewall, SSH oder Tailscale. Ausfälle werden nur auf dem Handy simuliert (WLAN oder Daten aus, falsche Adresse).

**Verbindung und Sync**
1. Netz bricht mitten in der Fahrt ab (`adb shell svc wifi disable` und `svc data disable`) und kommt nach 10 Minuten wieder: Es dürfen keine Punkte fehlen, keine Fahrt doppelt sein und kein Upload hängen bleiben.
2. Wechsel zwischen Heimnetz und Tailscale während eines laufenden Uploads (Adresse 1 absichtlich falsch eintragen): Der Ersatzweg muss greifen, und der Offline-Hinweis darf nicht hängen bleiben.
3. Server antwortet nicht oder langsam (falsche Adresse, Zeitüberschreitung): Die App friert nicht ein und zeigt eine verständliche Meldung.
4. Derselbe Datensatz wird zweimal hochgeladen (Upload abgebrochen, bevor die Antwort ankommt): Auf dem Server darf trotzdem nur ein Datensatz entstehen.
5. Zwei Geräte bearbeiten gleichzeitig dieselbe Fahrt, oder zwei Fahrten laufen gleichzeitig mit demselben Auto: Was gewinnt, und geht etwas verloren? Ein offline gesendeter alter km-Stand darf den neueren nicht überschreiben.
6. Anmeldung läuft ab (Token ungültig) während einer Fahrt: Die Fahrt bleibt lokal erhalten, und nach erneuter Anmeldung wird nachgeladen.

**Aufzeichnung**
7. App wird während der Fahrt beendet (`adb shell am kill`, danach `am force-stop`), Handy-Neustart nur simuliert: Die Fahrt muss weiterlaufen oder sauber als unterbrochen enden, nie still verschwinden.
8. Standortrecht während der Fahrt entzogen (`adb shell pm revoke` bzw. `appops set ... COARSE`), Energiesparmodus an (`adb shell settings put global low_power 1`), Akku fast leer (`adb shell dumpsys battery set level 3`): klare Meldung, nichts stürzt ab. Danach mit `dumpsys battery reset` und `low_power 0` zurücksetzen.
9. GPS-Lücke wie im Tunnel (Mock-Positionen 5 Minuten aussetzen) und GPS-Sprünge (ein Punkt 50 km daneben): keine Fantasie-Strecke und keine Rekord-Geschwindigkeit von 900 km/h, auch nicht auf dem Display (6b).
10. Fahrt über Mitternacht, über den Monatswechsel und über die Zeitumstellung: Fahrt richtig zugeordnet, Statistik und Display zählen sie nur einmal.
11. Sehr lange Fahrt (mehrere Stunden, viele tausend Punkte) und sehr kurze Fahrt (unter 100 m): Speicher, Upload-Größe, Kartenanzeige und Verwerfen kurzer Fahrten prüfen.
12. App-Update während einer laufenden Fahrt (eigene APK per `adb install -r`): Die Fahrt muss das Update überstehen.

**Eingaben und Daten**
13. Unsinnige Eingaben: km-Stand kleiner als der letzte, 0 oder 9 999 999 km, Preis negativ, Liter 0, Datum in der Zukunft, Sonderzeichen und Emojis im Zweck, sehr langer Text. Die App lehnt freundlich ab, und der Server (`plausibel.pb.js`) lehnt auch ohne App ab.
14. Beleg- und Tacho-Scan mit schlechtem Bild (dunkel, schräg, unscharf, falsches Motiv, sehr großes Foto über 10 MB): kein Absturz, keine falschen Werte ohne Rückfrage.
15. Auto, Fahrer oder Tankung löschen, an denen noch Fahrten hängen: keine verwaisten Datensätze und keine kaputte Abrechnung.
16. Leere Datenbank und ein ganz neues Konto ohne Fahrten: Startseite, Statistik, Abrechnung und Display zeigen keine Fehler und keine Division durch 0.

**Server und Pi** (nur lesen oder Test-Instanz)
17. PocketBase für kurze Zeit nicht erreichbar, etwa während `docker compose up -d` nach dem Backup: Apps und Bridge holen nach, das Display zeigt "Server nicht erreichbar" statt eines Absturzes.
18. Speicher und Backups: `df -h`, Größe von `pb_data` und Backups prüfen. Was passiert, wenn das Backup-Ziel voll ist? Nur rechnerisch oder an einer Test-Kopie prüfen, nichts absichtlich vollschreiben.
19. Uhrzeit von Handy und Server weichen ab (Handy-Uhr per adb 10 Minuten verstellen, danach zurück): Sortierung, "letzter Sync" und Konfliktlösung bleiben richtig.
20. Mehrfaches falsches Passwort, Passwort-vergessen-Hinweis, Abmelden mit noch nicht hochgeladenen Fahrten: Die App muss warnen, bevor lokale Daten verloren gehen.

Die Fälle, die sich nachts nicht prüfen lassen (echter Tunnel, echter Handy-Neustart, iOS), kommen als Testpunkte in die Liste für Admin in Phase 8.

### Phase 5c: Überall eine zweite Möglichkeit (Nachtrag 22:52)

Admin legt fest, dass es bei **jeder Eingabe und jedem automatischen Ablauf mindestens einen zweiten Weg** geben muss. Wenn Kamera, Automatik oder Netz versagen, darf niemand hängen bleiben. Diese Phase kommt nach 5b. Die PC-Session prüft jeden Punkt in der Android-App per adb und im iOS-Code, baut fehlende Wege ein und protokolliert pro Punkt "vorhanden", "eingebaut (PR)" oder "offen". Neue Wege aus 6c (QR, Bluetooth, "Fahrt vergessen?") bekommen ihren zweiten Weg gleich beim Bau.

| Erster Weg | Zweiter Weg (Pflicht) |
|---|---|
| Beleg per Kamera scannen | Foto aus der Galerie wählen **und** alles von Hand eingeben: Datum, Auto, Liter bzw. kWh, Preis pro Liter bzw. kWh, Gesamtpreis, km-Stand, Ort. Jedes erkannte Feld bleibt änderbar. Der Knopf "Ohne Beleg" ist immer sichtbar, nicht erst nach einem Fehlschlag. |
| Tacho per Kamera scannen | km-Stand von Hand eintippen (Ziffernfeld). |
| Kamera-Recht verweigert | Hinweis mit Knopf "Einstellungen öffnen", daneben Galerie und Handeingabe. |
| Fahrt startet und endet automatisch | Großer Knopf "Fahrt starten" und "Fahrt beenden". Fahrt nachtragen mit Start, Ziel, km und Uhrzeiten, falls gar nichts aufgezeichnet wurde. |
| Standort-Recht verweigert oder GPS aus | Fahrt von Hand eintragen (km-Stand vorher und nachher). Klarer Hinweis, wie man das Recht erteilt. |
| Fahrer über Bluetooth (6c Nr. 6) | Fahrer und Mitfahrer von Hand wählen und ändern, auch nachträglich. |
| Login mit Passwort | **Passwort vergessen:** (a) Text "Frag Admin (Admin)" mit Anleitung für den Admin. (b) Zurücksetzen per Einmal-Code vom Pi: Der Admin erzeugt am Display unter "Mehr > Handy verbinden" (PIN) oder in der Admin-Oberfläche einen QR-Code mit kurzlebigem Code (gleiche Technik wie die `einladung` aus 6c Nr. 1 und 2, Typ `reset`, 15 Minuten, einmal benutzbar). Die App scannt ihn und lässt ein neues Passwort setzen. Ist die Kamera nicht verfügbar, wird der Code abgetippt. |
| Einrichtung per QR (6c Nr. 1 und 2) | Adresse, E-Mail und Passwort von Hand eintragen, Einladungscode abtippen. |
| Server über mDNS finden (6c Nr. 3) | Adresse von Hand eintragen, Vorschläge `fahrtenbuch.local` und die Tailscale-Adresse. |
| Heimnetz-Adresse | Tailscale-Adresse als Ersatz (gibt es schon), dazu Knopf "Erneut versuchen". |
| Sync automatisch | Knopf "Jetzt synchronisieren" mit Anzeige, was noch nicht hochgeladen ist. |
| Android Ein-Klick-Update | Link zur Pi-Startseite (6c Nr. 4) mit direktem APK-Download. |
| iOS-Update über SideStore | Pi-Startseite mit Link zur IPA und kurzer Anleitung, falls die Quelle hakt. |
| Display-Touch | Server-Steuerung (Neustart, Aus) auch per SSH-Befehl in RASPBERRY-PI.md. PIN vergessen: Zurücksetzen über einen Eintrag in `/etc/fahrtenbuch-display.env`, beschrieben in RASPBERRY-PI.md. |
| Belegfoto hochladen | Ohne Netz bleibt das Foto lokal und wird später hochgeladen. Der Eintrag lässt sich auch ganz ohne Foto speichern. |

Zur Prüfung gehört bei jedem Punkt auch: Ist der zweite Weg **ohne Erklärung auffindbar** (sichtbarer Knopf, kein verstecktes Menü), und funktioniert er offline?

### Phase 6: Display

```bash
ssh admin@fahrtenbuch.local 'journalctl -u fahrtenbuch-display -n 30 --no-pager -o cat'
```

Prüfen: `hallo` mit aktueller Build-Nummer, km pro Monat/Jahr passen zur Statistik der App, keine Test-Daten sichtbar, keine Fehlerschleifen. Firmware nur aktualisieren, wenn in der Nacht ein Firmware-Fix gemergt wurde, über `display_update.py` wie gewohnt.

### Phase 6b: Display schöner machen (Wunsch von Admin, 22:24)

Ziel: Das Display ist kein reines Übersichts- und Einstellungs-Dashboard mehr, sondern zeigt im Wechsel eine schön gemachte Zusammenfassung der Daten. Server-Menü, Einstellungen und PIN bleiben unverändert erreichbar.

**Ansichten im Wechsel** (alle 15 s weiter, Tippen = nächste Ansicht, Wischen oder Halten wie bisher ins Menü, nach 60 s ohne Berührung zurück in den Wechsel):

1. **Letzte Fahrt mit Karte:** Route als helle Linie (Farbe des Autos) auf dunkler Karte im Nachtmodus-Stil, nur wichtige Straßen (Autobahn, Bundes-, Landstraße) in Grautönen, Start- und Zielpunkt. Darunter Auto, Fahrer, km, Dauer, Ø und max km/h.
2. **Rekorde:** schnellste Fahrt (max km/h), längste Strecke, längste Dauer, größte Tankung bzw. Ladung, günstigster und teuerster Preis pro Liter bzw. kWh. Jeweils mit Auto und Datum.
3. **Monat und Jahr:** km pro Auto als Balken, Kosten, Verbrauch (L/100 km bzw. kWh/100 km).
4. **Übersicht** wie bisher (Autos, wer gerade unterwegs ist).

Test-Daten zählen nirgends (wie bisher).

**Pi-Seite (`server/display-bridge/`, Branch `server`):**

- Karte auf dem Pi rendern, nicht auf dem ESP32. Wichtige Straßen einmal pro Fahrt über die Overpass-API holen (`highway` in `motorway|trunk|primary|secondary`, Bounding-Box der Route plus 10 %), auf dem Pi zwischenspeichern (z. B. `/var/cache/fahrtenbuch-display/`), damit jede Fahrt nur einmal abgefragt wird. Ohne Netz: nur die Route auf dunklem Grund.
- Mit Pillow ein Bild 320×200 in 4 Farben zeichnen (Hintergrund fast schwarz, Straßen zwei Grautöne, Route in Autofarbe), als 2 Bit pro Pixel packen (16 KB), in Blöcken über die UART schicken. Bei 115200 Baud dauert das etwa 2 bis 3 s, nur bei einer neuen letzten Fahrt.
- Neue Nachrichten, erst in `docs/RASPBERRY-PI.md` 2.5 festlegen, dann bauen:
  - `{"t":"karte","id":"<fahrt-id>","w":320,"h":200,"farben":["#0b0f14","#2a3340","#4a5566","#ff3b30"],"teil":0,"von":N,"daten":"<base64>"}` mit Bestätigung bzw. `nochmal` wie beim Firmware-Update.
  - `rekorde` um Tank- und Ladewerte und Datum erweitern.
- Tests in `test_bridge.py`: Rendern ohne Netz, Packen und Zerlegen, Test-Daten ignoriert, Cache-Treffer ohne zweite Abfrage.
- `pillow` als Abhängigkeit in `install.sh` (z. B. `apt-get install -y python3-pil`).

**Firmware (`display/`, Release `display-N`):**

- Karten-Bild aus den Blöcken in einen LVGL-Canvas mit Palette (I2) laden, ohne PSRAM passt das in den RAM (16 KB).
- Ansichten-Wechsel mit sanftem Übergang, Schrift und Farben passend zum dunklen Kartenstil.
- Server-Menü (Neustart PocketBase, Backup, Pi-Neustart, Aus, Drehung, PIN) unverändert, Halte-Geste wie bisher.
- Nachts optional dunkler (Hintergrundbeleuchtung per PWM, z. B. 23 bis 6 Uhr auf 20 %).
- Über `display_update.py` vom Pi aufspielen, Ergebnis mit Foto vom Display im Protokoll beschreiben (Admin schaut morgens drauf).

Reihenfolge: zuerst Protokoll in RASPBERRY-PI.md, dann Bridge (CI grün, auf dem Pi installieren), dann Firmware. Die Phase kommt nach den Fehlern aus Phase 5, weil sie die größte ist. Reicht die Nacht nicht, im Protokoll festhalten, wie weit sie ist.

### Phase 6c: Runde 2 aus der Roadmap (Nachtrag 22:40)

Admin hat um 22:35 die Ideen aus Runde 2 freigegeben: **alle Punkte außer Nr. 8**, Nr. 6 überarbeitet. Liste, Ziele und Details stehen in **[ROADMAP.md](ROADMAP.md)**. Diese Phase kommt nach Phase 5 und 6b. Was die Nacht nicht schafft, bleibt in der Roadmap und übernimmt die Cloud, sobald "## Cloud ist dran" steht. Im Protokoll pro Nummer festhalten: fertig (PR), angefangen (Branch) oder nicht begonnen.

**Reihenfolge** (nach Nutzen, kleine Punkte zuerst, damit möglichst viel fertig wird):

1. **Nr. 4 Startseite auf dem Pi** (`server/downloads/`): `index.html` im Ziel-Ordner, von `abholen.py` bei jedem Lauf neu geschrieben: Knopf "Android-App laden" (neueste APK), "SideStore-Quelle hinzufügen" (`sidestore://source?url=...` plus Adresse zum Kopieren), Versionen, kurze Anleitung. Die QR-Codes aus Nr. 1 kommen dazu, sobald es sie gibt (Python `qrcode` oder `segno` als SVG, `apt-get install -y python3-qrcode` in `install.sh`). Test in `test_abholen.py`.
2. **Nr. 3 Adressen vom Server:** Hook `pb_hooks/info.pb.js` mit `GET /api/fahrtenbuch/info` (nur angemeldet) liefert `heimnetz`, `tailscale`, `downloads` aus Umgebungsvariablen in `docker-compose.yml` (`FB_HEIMNETZ`, `FB_TAILSCALE`, `FB_DOWNLOADS`). Die Apps holen das nach dem Login und tragen fehlende Adressen selbst ein. mDNS: Avahi-Dienst `_fahrtenbuch._tcp` auf Port 8090 (`/etc/avahi/services/fahrtenbuch.service`), Apps suchen im Login (Android `NsdManager`, iOS `NWBrowser` plus `NSBonjourServices` in `project.yml`). Test in `test_server.sh`.
3. **Nr. 5 Verbindungstest:** In der Einrichtung und unter Einstellungen eine Ampel: Heimnetz, Tailscale, Downloads, Standort, Bewegung, Benachrichtigung. Rot hat einen Knopf "So behebst du das".
4. **Nr. 1 und 2 QR-Code:**
   - Format: `fahrtenbuch://verbinden?heim=<url>&tail=<url>&dl=<url>[&einladung=<code>]`, URL-kodiert.
   - Server: Collection `einladung` (Code 12 Zeichen, `erstellt_von`, `gueltig_bis` 24 h, `benutzt`), Hook `POST /api/fahrtenbuch/einladung` (nur angemeldet) und `POST /api/fahrtenbuch/beitreten` (Code, Name, E-Mail, Passwort: legt den Benutzer an, `passwort_aendern=false`, markiert den Code als benutzt, gibt ein Auth-Token zurück). Einmal benutzbar, abgelaufene Codes werden abgelehnt. Tests in `test_server.sh`.
   - Apps: Login-Seite mit "QR-Code scannen" (Android CameraX plus ML Kit Barcode, iOS `DataScannerViewController`), Einstellungen "Familienmitglied einladen" (zeigt den QR) und "Auf neues Handy umziehen" (QR mit kurzlebigem Token aus `auth-refresh`, 10 Minuten).
   - Display: neue Nachricht `{"t":"qr","text":"fahrtenbuch://..."}` (Pi → ESP32), Firmware zeigt sie mit `lv_qrcode` unter "Mehr > Handy verbinden" (mit PIN). Die Bridge baut den Text aus `FB_HEIMNETZ`/`FB_TAILSCALE` in `/etc/fahrtenbuch-display.env`, ohne Einladungscode. Erst in RASPBERRY-PI.md 2.5 eintragen, dann bauen.
5. **Nr. 9 und 12 Belege:** Knopf "Getankt" bzw. "Geladen" öffnet zuerst die Kamera, "Ohne Beleg" führt zum bisherigen Formular. Scan-Protokoll im Test-Modus (erkannter Text, übernommene Felder, Export). Für die Trefferquote braucht es echte Fotos: unter "Offen für Admin" eintragen ("5 bis 10 Belege fotografieren, in den Thread schicken"). Bis dahin vorhandene Testfälle in `ScanLeser` um typische Layouts erweitern (Aral, Shell, Jet, Ladesäulen-Beleg).
6. **Nr. 6 Bluetooth, Fahrer und Mitfahrer:** genau nach ROADMAP.md "Nr. 6 im Detail". Server zuerst (Migration `fahrt.mitfahrer`, `fahrt.vorlaeufig`, Hook für Fahrerwechsel), dann Apps (Android `BluetoothDevice.ACTION_ACL_CONNECTED` im Manifest-Receiver, Berechtigung `BLUETOOTH_CONNECT`; iOS: Assistent für die Kurzbefehl-Automation, weil iOS kein Bluetooth-Ereignis an Apps gibt; die Rückfrage kommt dort als Benachrichtigung aus dem Intent). Abrechnung teilt den Anteil durch (1 + Mitfahrer), Logiktests in beiden Apps. Bridge: vorläufige Fahrten nicht zählen, "unbestätigt" anzeigen.
7. **Nr. 7 "Fahrt vergessen?":** Android `ActivityTransition` IN_VEHICLE ENTER/EXIT ohne laufende Fahrt → Benachrichtigung "Jetzt starten" oder "Später nachtragen" (Nachtragen-Formular mit Uhrzeiten). iOS beim Öffnen der App `queryActivityStarting` der letzten 24 h, Autofahrten über 5 Minuten ohne Fahrt als Hinweis oben.
8. **Nr. 11 und 13:** Fehlertexte vereinheitlichen, Hilfe-Seite, Belegvorschau.
9. **Nr. 15 und 21:** Apps schicken bei jedem Sync `app_version` und `geraet` an `users` (Migration), Statusseite im Index aus Nr. 4 (`status.json` von `abholen.py` oder ein kleiner Hook), Collection `bericht` für "Problem melden".
10. **Nr. 20 Oberflächentests:** iOS XCUITest-Ziel in `project.yml`, CI-Schritt nach den Logiktests, Screenshots als Artefakt. Android: Compose-UI-Tests oder Screenshot-Tests im Emulator (`reactivecircus/android-emulator-runner`).
11. **Danach nach Zeit:** Nr. 10, 19, 18, 24, 22, 16, 25, 23, 14, 17.

**Regeln wie oben:** Server-Änderungen zuerst und mit Backup, Protokolländerungen zuerst in RASPBERRY-PI.md, App-Merges sammeln (höchstens zwei bis drei pro App in der Nacht), nur Test-Daten, Abrechnungs- und Sync-Änderungen nur mit Logiktests.

### Phase 7: Abschluss

1. Alle Test-Daten auf dem Server `geloescht=true` (Abfrage `test=true && geloescht=false` muss leer sein).
2. `adb shell svc power stayon false`, Testpositionen entfernt.
3. Pi: Health ok, Downloads bieten die neuesten Builds an, Bridge läuft.
4. Protokoll in `ABSTIMMUNG.md` abschließen (Vorlage unten), ganz unten **"## Cloud ist dran"**, committen, pushen.

### Phase 8: Für Admin am Morgen (iPhone)

Die PC-Session schreibt diese Liste mit den aktuellen Versionen ins Protokoll:

1. SideStore: Quelle neu laden, Fahrtenbuch aktualisieren, neues Icon sichtbar.
2. Passwörter-App: Einen Eintrag für das Fahrtenbuch selbst anlegen (automatisches Speichern geht ohne Associated Domains nicht, siehe #60). Abmelden und neu anmelden: Die Tastaturleiste muss den Eintrag anbieten.
3. Unterwegs mit mobilen Daten und Tailscale: Fahrt starten, nach ein paar Minuten muss sie auf einem anderen Gerät oder am Display als "unterwegs" mit Strecke erscheinen.
4. Test-Modus an, kurze Testfahrt, Diagnose ansehen, Belegfoto einer echten Quittung, Tacho-Foto.
5. Test-Modus aus, "Alle Test-Fahrten löschen".
6. Auffälligkeiten mit Screenshot an die Cloud.

### Vorlage für das Protokoll

```markdown
## Runde 12: PC (Nacht 2026-11-03/04)

### Stand am Ende
- Pi: Migrationen bis ..., Backup ..., Downloads Android ... / iOS ..., Bridge ..., Display build ...
- Releases: android-..., ios-..., display-...

### Erledigt
- Phase ...: ... (PR #...)

### Befunde Runde 11
| Nr. | Android | iOS |
|---|---|---|
| 1 | behoben (#...) | nicht betroffen |

### Neue Fehler und was damit passiert ist
- ...

### Offen für Admin
- ... (genauer Befehl oder Handgriff)

## Cloud ist dran
```
