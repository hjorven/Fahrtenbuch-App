# Projektplan

## Ziel

- Jeder Fahrer wählt ein Auto, startet eine Fahrt (auch per Widget) und die App zeichnet Route, Geschwindigkeit, Dauer und Kilometer auf.
- Tankvorgänge und sonstige Kosten werden pro Auto erfasst.
- Alle drei Geräte sehen die Daten der anderen.
- Die Daten liegen auf einem eigenen Server im Heimnetz, nicht in einer fremden Cloud.

## Architektur

```
iPhone (SwiftUI)  ─┐
iPhone (SwiftUI)  ─┼──►  PocketBase im Heimnetz (HP ProBook, Docker)
Android (Compose) ─┘     REST-API + Realtime + Login + Admin-UI
```

### Offline-first

- Jede App speichert alles lokal (iOS: SwiftData, Android: Room).
- Neue und geänderte Datensätze kommen in eine lokale Warteschlange (Outbox).
- Sobald der Server erreichbar ist (Heim-WLAN oder Tailscale), wird die Outbox hochgeladen und neue Daten der anderen werden abgeholt.
- Jeder Datensatz bekommt eine UUID, die auf dem Gerät erzeugt wird. Dadurch gibt es keine ID-Konflikte beim späteren Hochladen.

### Konfliktregeln

- Fahrten und Tankvorgänge darf nur der Ersteller bearbeiten.
- Bei Fahrzeugen (z. B. km-Stand) gewinnt die letzte Änderung (`updated`-Zeitstempel).
- Gelöschte Datensätze werden nur als gelöscht markiert (`geloescht = true`), damit andere Geräte die Löschung mitbekommen.

### Erreichbarkeit

- Standard: nur im Heimnetz. Die FritzBox lässt sich nicht konfigurieren, also keine Portfreigabe.
- Optional: Tailscale auf Server und Handys, dann klappt der Sync auch unterwegs.
- Geplant: Umzug des Servers auf einen Raspberry Pi 4B mit ESP32-Touch-Display (Fahrten, km pro Monat und Jahr, Server-Menü), siehe [RASPBERRY-PI.md](RASPBERRY-PI.md).

## Features

### Version 1

- [ ] Login pro Person (PocketBase-Benutzer)
- [ ] Fahrzeug auswählen, Fahrt starten/stoppen, km-Stand bestätigen
- [ ] GPS-Aufzeichnung im Hintergrund (Route, Geschwindigkeit, Dauer)
- [ ] Zweck der Fahrt (privat / geschäftlich / sonstiges), Notiz
- [ ] Tankvorgang erfassen (Liter, Preis pro Liter, Gesamtpreis, km-Stand, vollgetankt)
- [ ] Fahrtenliste und Kartenansicht aller Fahrten von allen Geräten
- [ ] Statistik pro Auto: Kilometer, Verbrauch (L/100 km), Kosten pro km

### Version 2

- [ ] Widget und Kontrollzentrum/Schnelleinstellung zum Starten einer Fahrt
- [ ] Live Activity (iOS) bzw. laufende Benachrichtigung (Android) während der Fahrt
- [ ] Automatischer Start bei Bluetooth-Verbindung mit dem Auto
- [ ] Kostenaufteilung: Preis pro km pro Auto festlegen, Abrechnung pro Fahrer und Monat
- [ ] Export als CSV und PDF

## iOS

- SwiftUI für iOS 26 mit den Standard-Komponenten (Liquid Glass): `NavigationStack`, `TabView`, `.glassEffect()`
- Karten: MapKit
- Lokale Daten: SwiftData
- Standort: `CLLocationUpdate.liveUpdates()` und `CLBackgroundActivitySession`, Background Mode "Location updates"
- Fahrt starten von außen: ein gemeinsames `AppIntent` (`StartTripIntent`, `StopTripIntent`), genutzt von
  - interaktivem Widget (WidgetKit)
  - Control im Kontrollzentrum
  - Kurzbefehle-Automation "Wenn mit Bluetooth *Auto* verbunden"
- Live Activity (ActivityKit) mit Geschwindigkeit, Strecke, Dauer
- Projektdatei mit XcodeGen (`project.yml`), damit kein Mac zum Anlegen nötig ist
- Build: GitHub Actions auf einem macOS-Runner erzeugt eine unsignierte IPA, SideStore signiert beim Installieren

```bash
xcodebuild -project Fahrtenbuch.xcodeproj -scheme Fahrtenbuch \
  -sdk iphoneos -configuration Release \
  CODE_SIGNING_ALLOWED=NO build
```

Hinweise zu SideStore mit kostenloser Apple-ID: siehe [SIDESTORE.md](SIDESTORE.md).

## Android

- Kotlin, Jetpack Compose, Material 3 (Expressive), Dynamic Color
- Karten: osmdroid (ohne API-Schlüssel) oder Google Maps Compose
- Lokale Daten: Room
- Standort: Foreground Service vom Typ `location` mit Fused Location Provider
- Fahrt starten von außen: Glance-Widget und Schnelleinstellungs-Kachel (`TileService`)
- Auto-Start: Bluetooth-Verbindung mit dem Auto erkennen
- Build: Gradle auf dem Bazzite-PC oder per GitHub Actions, APK direkt installieren
- Updates: Eine neue APK installiert sich nur über die alte, wenn sie mit **demselben Schlüssel** signiert ist. Keystore einmal erzeugen, sicher aufbewahren (nicht im Repo, steht in `.gitignore`) und als GitHub-Secret für den Build hinterlegen.
- Hintergrund: Akku-Optimierung für die App ausschalten, sonst beenden manche Hersteller (Samsung, Xiaomi) die GPS-Aufzeichnung. Die App fragt das beim ersten Start ab.

```bash
./gradlew assembleRelease
```

## Reihenfolge

1. Server: PocketBase auf dem ProBook starten, Migration einspielen, drei Benutzer anlegen
2. Android-App V1 (schnellster Testzyklus, kein Signieren nötig)
3. iOS-App V1, GitHub Action für die IPA, SideStore auf beiden iPhones einrichten
4. Sync zwischen allen drei Geräten testen
5. Widgets, Live Activity, Bluetooth-Auto-Start
6. Optional: Tailscale für Sync von unterwegs

## Entscheidungen

### Kein Finanzamt-Fahrtenbuch

Fahrten dürfen nachträglich bearbeitet werden (vom Ersteller), z. B. um vergessene Fahrten nachzutragen oder falsch erkannte Fahrten zu teilen oder zusammenzulegen.

### Automatisches Fahrtende

Eine Fahrt endet automatisch, wenn das Auto **10 Minuten** steht (Wert pro Person in den Einstellungen änderbar).

- Stillstand = Geschwindigkeit unter 3 km/h und Position innerhalb von 100 m.
- Das Fahrtende wird auf den **Beginn** des Stillstands zurückdatiert, damit die Wartezeit nicht mitzählt.
- Fährt man innerhalb der 10 Minuten weiter (Briefkasten, Bäcker, kurz tanken), bleibt es eine Fahrt.
- Längere Stopps (Einkaufen) ergeben automatisch Hin- und Rückfahrt.
- Zusätzlich, wo möglich: Trennt sich das Handy vom Bluetooth des Autos, endet die Fahrt sofort.
- Stau: Bewegungserkennung (iOS CoreMotion, Android Activity Recognition) meldet weiter "im Fahrzeug", solange niemand aussteigt. Erst wenn sie "gehend" oder "still" meldet, läuft der 10-Minuten-Zähler.
- In der Fahrtenliste lassen sich zwei Fahrten zusammenlegen und eine Fahrt an einem Punkt teilen, falls die Automatik danebenliegt.

### Kostenaufteilung

Kein manueller Durchschnittsverbrauch pro Fahrt. Stattdessen rechnet die App aus den Tankvorgängen:

1. **Kosten pro km pro Auto** = Tankkosten / gefahrene km zwischen vollen Tankungen (gleitend über die letzten 3 vollen Tankungen).
2. **Anteil pro Fahrer** = eigene km x Kosten pro km.
3. **Saldo** = Anteil minus selbst bezahlte Tankrechnungen.

Beispiel Bus, ein Monat, 9,0 L/100 km, 1,75 Euro/L ergibt 0,16 Euro/km:

| | km | Anteil | getankt bezahlt | Saldo |
|---|---|---|---|---|
| Ben | 600 | 96 Euro | 140 Euro | bekommt 44 Euro |
| Admin | 300 | 48 Euro | 0 Euro | zahlt 48 Euro |
| Anna | 100 | 16 Euro | 20 Euro | bekommt 4 Euro |

Optional pro Auto ein fester Preis pro km (`preis_pro_km`), der die Berechnung überschreibt, z. B. wenn Verschleiß und Versicherung mit rein sollen.
