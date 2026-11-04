# Android-App

Kotlin, Jetpack Compose, Material 3 mit Dynamic Color. Offline-first mit Room, Abgleich mit dem PocketBase-Server im Heimnetz.

## Funktionen (Version 1)

- Läuft sofort ohne Server (lokaler Modus mit den drei Autos). Server später unter Einstellungen > "Mit Server verbinden", dann werden die bisherigen Fahrten dem Konto zugeordnet und hochgeladen
- Auto wählen, Fahrt starten und beenden, GPS-Aufzeichnung im Hintergrund (Strecke, Geschwindigkeit, Dauer, Route)
- Automatisches Fahrtende nach einstellbarer Stillstandszeit (Standard 10 Minuten), zurückdatiert auf den Beginn des Stillstands
- Start und Ziel als Adresse (Reverse-Geocoding)
- Fahrtenliste aller Personen mit Filter pro Auto, Detailansicht mit Karte (OpenStreetMap), Zweck und Notiz
- Tankvorgänge erfassen (Liter, Preis pro Liter oder Gesamtpreis, km-Stand, vollgetankt)
- Statistik pro Auto und Monat: km, Verbrauch, Kosten pro km, Abrechnung pro Person
- Homescreen-Widget und Schnelleinstellungs-Kachel zum Starten und Stoppen
- Laufende Benachrichtigung mit "Fahrt beenden"

## Aufbau

```
android/app/src/main/java/de/hjorven/fahrtenbuch/
  data/       Room-Datenbank, PocketBase-Client, Sync, Statistik
  location/   Foreground Service, Stillstandserkennung, Streckenberechnung
  ui/         Compose-Oberfläche (Material 3)
  widget/     Glance-Widget, Schnelleinstellungs-Kachel
```

## APK bekommen

Jeder Push auf den Branch `android` baut über GitHub Actions eine fertige APK und legt sie als Release ab (`android-<nummer>`). Bei Pull Requests liegt sie als Artefakt am Workflow-Lauf. Auf dem Handy:

1. Release-Seite des Repos öffnen, neueste `Fahrtenbuch-<nummer>.apk` herunterladen
2. Installation aus dieser Quelle einmalig erlauben
3. Installieren und loslegen. Der Server kann später in den Einstellungen verbunden werden

### Signierschlüssel einmalig einrichten

Damit sich neue APKs über die alte installieren lassen, müssen alle mit demselben Schlüssel signiert sein. Der Schlüssel gehört nicht ins Repo, sondern in die GitHub-Secrets. Einmalig auf dem PC:

```bash
keytool -genkeypair -keystore fahrtenbuch.jks -alias fahrtenbuch \
  -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Fahrtenbuch"
base64 -w0 fahrtenbuch.jks
```

Dann im Repo unter Settings > Secrets and variables > Actions vier Secrets anlegen:

| Secret | Inhalt |
|---|---|
| `FB_KEYSTORE_BASE64` | Ausgabe von `base64 -w0 fahrtenbuch.jks` |
| `FB_KEYSTORE_PASSWORD` | Passwort aus `keytool` |
| `FB_KEY_ALIAS` | `fahrtenbuch` |
| `FB_KEY_PASSWORD` | Passwort aus `keytool` (meist dasselbe) |

`fahrtenbuch.jks` sicher aufbewahren (z. B. im Passwortmanager). Ohne die Secrets baut die Action trotzdem, signiert aber mit einem wechselnden Debug-Schlüssel.

## Selbst bauen

Auf dem Bazzite-PC mit Android SDK:

```bash
cd android
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Nach der Installation

- Beim ersten Fahrtstart Standort und Benachrichtigungen erlauben
- Einstellungen > Hintergrund: "Akku-Optimierung ausschalten", sonst stoppen manche Hersteller die Aufzeichnung
- Für Widget und Kachel ohne geöffnete App: Standort auf "Immer erlauben" stellen

## Bekannte Grenzen

- Namen der anderen Personen erscheinen erst, wenn auf dem Server die Tabelle `users` für alle Angemeldeten lesbar ist. Bis dahin steht dort "Fahrer". Admin-Oberfläche: Collections > users > API Rules > List/Search und View auf `@request.auth.id != ""` setzen.
- Stauerkennung über Bewegungssensoren und automatischer Start per Bluetooth kommen in Version 2.

## Beleg- und Tacho-Scan

Im Tank-/Ladeformular: "Tankbeleg fotografieren" (bzw. "Ladebeleg") öffnet die Kamera-App, ML Kit liest den Bon direkt auf dem Handy (Modell ist in der App, kein Internet, keine Cloud) und füllt Menge, Preis pro Liter/kWh, Gesamtbetrag, Tankstelle und Datum aus. Das Foto wird verkleinert (etwa 2000 px) unter `files/belege/<id>.jpg` gespeichert und beim Abgleich an `tankvorgang.beleg` hochgeladen (Server-Migration `1762600000_beleg.js`). Fotos von anderen Handys werden beim Öffnen des Eintrags mit Datei-Token geladen.

"km-Stand vom Tacho fotografieren" gibt es im Tankformular, im Dialog "Tacho-Stand" einer Fahrt und beim Nachtragen. Die App schlägt die erkannte Zahl vor ("Steht das auf dem Tacho?") und übernimmt sie erst nach "Ja, stimmt". Tageskilometer, Uhrzeit, Tempo und Temperatur werden aussortiert, der letzte bekannte km-Stand hilft beim Aussuchen. Die Auswertung steckt in `data/ScanLeser.kt` und ist in `LogikTest` getestet.
