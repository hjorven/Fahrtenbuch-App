# Server (PocketBase)

Läuft im Heimnetz auf dem HP ProBook (Ubuntu, headless) in Docker.

## Einrichten

Repo auf den ProBook holen und starten:

```bash
git clone -b server git@github.com:hjorven/Fahrtenbuch-App.git
cd Fahrtenbuch-App/server
docker compose up -d
docker compose logs -f
```

Beim ersten Start spielt PocketBase die Migration aus `pb_migrations/` ein: Tabellen `fahrzeug`, `fahrt`, `tankvorgang`, `kosten` und die drei Autos.

Prüfen, ob es läuft:

```bash
curl http://localhost:8090/api/health
```

## Admin-Konto anlegen

```bash
docker exec -it fahrtenbuch /usr/local/bin/pocketbase superuser upsert deine@mail.de EinSicheresPasswort --dir=/pb_data
```

Danach im Browser: `http://<probook-ip>:8090/_/`

## Benutzer anlegen

In der Admin-Oberfläche unter **Collections > users** drei Benutzer anlegen (Ben, Anna, Admin) mit E-Mail, Passwort und Name. Mit diesen Daten melden sich die Apps an.

## IP-Adresse

Die Apps brauchen eine feste Adresse. Optionen:

- ProBook-IP herausfinden mit `hostname -I` und hoffen, dass die FritzBox sie nicht ändert (sie vergibt meist dauerhaft dieselbe)
- `http://<hostname>.local:8090` per mDNS (Avahi ist auf Ubuntu meist schon aktiv)
- Tailscale: `http://<probook>.<tailnet>.ts.net:8090`, funktioniert auch unterwegs

## Backup

Alle Daten liegen in `server/pb_data/`. Einfaches tägliches Backup per Cron:

```bash
0 3 * * * tar czf ~/backups/fahrtenbuch-$(date +\%F).tar.gz -C ~/Fahrtenbuch-App/server pb_data
```

## Getestet

Beide Migrationen wurden gegen PocketBase 0.31 geprüft: Tabellen und die drei Autos werden angelegt, Anmeldung funktioniert, Fahrten können mit eigener ID angelegt und nur vom Ersteller geändert werden, alle Angemeldeten sehen die Namen der anderen (E-Mail-Adressen bleiben verborgen).

Die Migration `1762200000_elektro_laden.js` wurde gegen PocketBase 0.40.6 geprüft: Der E-Auto heißt danach richtig und hat `kraftstoff = elektro`, Kombi und Bus haben `diesel`, und `tankvorgang` hat das neue Feld `ladeort` (zuhause/oeffentlich). Bei Elektro-Autos steht `liter` für kWh und `preis_pro_liter` für den Preis pro kWh.

Falls das Docker-Image `--migrationsDir=/pb_migrations` nicht an seinen Startbefehl anhängt: Logs beim ersten Start ansehen und `command` in `docker-compose.yml` anpassen.

## Regeln im Server (pb_hooks)

`pb_hooks/km_stand.pb.js`: Der km-Stand eines Autos kann über die Apps nur steigen. Lädt ein Handy, das offline war, später einen älteren, niedrigeren Stand hoch, bleibt der höhere stehen, und die Apps übernehmen ihn beim nächsten Abgleich. Einen zu hoch eingetippten Wert korrigiert man in der Admin-Oberfläche unter `fahrzeug`, dort darf er auch sinken.

`pb_hooks/plausibel.pb.js` (Regeln in `pb_hooks/plausibel.js`): Sicherheitsnetz gegen unmögliche Werte aus den Apps. Abgelehnt (400) werden Tank- und Ladevorgänge mit 0 oder mehr als 200 Litern bzw. kWh oder mehr als 5 Euro pro Einheit, Fahrten mit Ende vor dem Start oder km am Ende kleiner als am Start, km-Stände über 2 Mio. und Ausgleichszahlungen mit Betrag 0 oder an sich selbst. Still korrigiert werden: Laden zuhause kostet 0 Euro, ein fehlender Gesamtpreis wird aus Menge mal Preis berechnet, Geschwindigkeiten über 250 km/h (GPS-Ausreißer) werden 0. Für Superuser in der Admin-Oberfläche gilt nichts davon.

## Ausgleichszahlungen

Migration `1762300000_ausgleich.js` legt die Tabelle `ausgleich` an (`von`, `an`, `betrag`, `zeitraum` als `JJJJ-MM`, `datum`, `notiz`, `geloescht`). Damit tragen die Apps ein, dass jemand seinen Anteil aus der Monatsabrechnung bezahlt hat. Eintragen und ändern darf nur, wer beteiligt ist.

Migration und Hook wurden gegen PocketBase 0.40.6 geprüft. Auf dem Pi nach dem `git pull` einmal neu starten, damit beides geladen wird:

```bash
cd ~/Fahrtenbuch-App && git pull && cd server && docker compose restart
```

## PocketBase-Version

Die Version steht fest in `docker-compose.yml` (zurzeit `0.40.6`). Für ein Update die Nummer dort ändern, vorher ein Backup anlegen, dann auf dem Server:

```bash
git pull && docker compose pull && docker compose up -d
```

## Display (display-bridge)

`display-bridge/` ist der Dienst für das ESP32-Display am Pi. Einrichtung und Tests: [display-bridge/README.md](display-bridge/README.md).

## Test-Modus

Migration `1762400000_test_modus.js`: `fahrt.test`, `tankvorgang.test` (Bool) und `fahrt.diagnose` (JSON, max. 200 KB). Test-Datensätze zählen in den Apps und auf dem Display nicht mit. Den km-Stand des Autos setzen die Apps bei Test-Fahrten nicht hoch. Aufgeräumt wird über `geloescht = true`. Gegen PocketBase 0.40.6 geprüft: Anlegen mit `test` und `diagnose` als normaler Benutzer, Bridge ignoriert die Datensätze.

## Start-Passwort ändern

Migration `1762500000_passwort_aendern.js` legt `users.passwort_aendern` (Bool) an und setzt es bei allen bestehenden Konten auf `true`. Die Apps zeigen dann nach dem Abgleich einen Dialog "Eigenes Passwort festlegen" (bisheriges, neues, wiederholen) und melden sich danach mit dem neuen Passwort an. Der Hook `pb_hooks/passwort.pb.js` setzt das Feld zurück, sobald ein Benutzer sein Passwort ändert, und verhindert, dass es ohne Passwortwechsel auf `false` gesetzt wird. Neue Konten in der Admin-Oberfläche mit Haken bei `passwort_aendern` anlegen. Auf dem Pi:

```bash
ssh admin@fahrtenbuch.local
cd ~/Fahrtenbuch-App && git pull
cd server && docker compose restart pocketbase
docker logs fahrtenbuch --tail 20        # Migration 1762500000_passwort_aendern ohne Fehler
```

## Belegfoto (Tank- und Ladebelege)

Migration `1762600000_beleg.js` legt `tankvorgang.beleg` an (eine Bilddatei, JPEG/PNG/HEIC, max. 10 MB, Vorschaubild 400 px breit). Die Apps fotografieren den Kassenbon, lesen Menge, Preis, Datum und Tankstelle direkt auf dem Handy aus (Android ML Kit, iOS Vision, keine Cloud) und laden das Foto beim nächsten Abgleich hoch. Das Feld ist `protected`: Fotos sind nur angemeldet mit einem kurzlebigen Datei-Token abrufbar, nicht über eine offene Adresse. Ein Foto anhängen darf nur, wer den Eintrag angelegt hat. Die Fotos liegen in `pb_data/storage/` und sind damit im normalen Backup von `pb_data` enthalten.

`test_server.sh` prüft Migrationen und Hooks gegen die PocketBase-Version aus `docker-compose.yml` (läuft bei jedem PR in GitHub Actions): Anlegen, Upload durch den Fahrer, Upload durch Fremde abgelehnt, Abruf nur mit Token, JSON-Änderung lässt das Foto stehen.

Auf dem Pi einspielen:

```bash
ssh admin@fahrtenbuch.local
cd ~/Fahrtenbuch-App && git pull
cd server && docker compose restart pocketbase
docker logs fahrtenbuch --tail 20        # Migration 1762600000_beleg ohne Fehler
```
