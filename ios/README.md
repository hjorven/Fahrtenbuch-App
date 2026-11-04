# iOS-App

SwiftUI für iOS 26 mit Liquid Glass, SwiftData, MapKit, ActivityKit und WidgetKit. Gleiche Funktionen wie die Android-App, gleicher Server.

## Funktionen (Version 1)

- Läuft sofort ohne Server (lokaler Modus mit den drei Autos). Server später unter Einstellungen > "Mit Server verbinden", dann werden die bisherigen Fahrten dem Konto zugeordnet und hochgeladen
- Auto wählen, Fahrt starten und beenden, GPS-Aufzeichnung im Hintergrund (Strecke, Geschwindigkeit, Dauer, Route)
- Automatisches Fahrtende nach einstellbarer Stillstandszeit (Standard 10 Minuten), zurückdatiert
- Live Activity auf Sperrbildschirm und in der Dynamic Island, mit "Beenden"-Knopf
- Homescreen- und Sperrbildschirm-Widget, Schalter im Kontrollzentrum (auch für die Action-Taste)
- Siri und Kurzbefehle: "Fahrt starten" und "Fahrt beenden", z. B. als Automation beim Verbinden mit dem Auto-Bluetooth
- Fahrtenliste aller Personen mit Filter, Detailansicht mit Karte, Zweck und Notiz
- Tanken, Statistik und Monatsabrechnung wie auf Android

## Aufbau

```
ios/
  project.yml               XcodeGen-Projektdefinition
  Fahrtenbuch/
    Daten/                  SwiftData-Modelle, PocketBase-Client, Sync, Statistik
    Fahrt/                  Fahrtaufzeichnung (CoreLocation, Live Activity)
    Ansichten/              SwiftUI-Oberfläche
  FahrtenbuchWidgets/       Widget + Control + Live Activity (eine Erweiterung)
  Shared/                   AppIntents, Live-Activity-Daten, gemeinsamer Speicher
```

## IPA bekommen

Ohne Mac: Jeder Push auf den Branch `ios` baut über GitHub Actions (macOS-Runner) eine unsignierte IPA und legt sie als Release `ios-<nummer>` ab. SideStore signiert sie beim Installieren mit der eigenen Apple-ID. Anleitung: [docs/SIDESTORE.md](../docs/SIDESTORE.md) im Branch `main`.

Hinweis: macOS-Minuten zählen in privaten Repos zehnfach gegen das kostenlose GitHub-Kontingent (2000 Minuten im Monat, also etwa 200 macOS-Minuten). Ein Build dauert einige Minuten.

## Mit Mac selbst bauen

```bash
brew install xcodegen
cd ios
xcodegen generate
open Fahrtenbuch.xcodeproj
```

## Nach der Installation

- Beim ersten Start Standort und lokales Netzwerk erlauben (sonst erreicht die App den Server nicht)
- Für Widget, Kontrollzentrum und Kurzbefehle ohne geöffnete App: Einstellungen > Hintergrund > Standort "Immer" erlauben
- Bluetooth-Automatik: Kurzbefehle > Automation > Bluetooth > Auto wählen > "Sofort ausführen" > Aktion "Fahrt starten"

## Technische Hinweise

- Die App Group wird zur Laufzeit aus der Bundle-ID abgeleitet (`Shared/AppGruppe.swift`), weil SideStore beim Signieren die Team-ID anhängt.
- Die Intents sind `LiveActivityIntent`s und laufen dadurch immer im App-Prozess, auch wenn sie vom Widget kommen.

## Beleg- und Tacho-Scan

Im Tank-/Ladeformular öffnet "Tankbeleg fotografieren" (bzw. "Ladebeleg") den Dokumentenscanner von iOS (findet den Bon, entzerrt ihn, löst selbst aus). Vision liest den Text auf dem iPhone (keine Cloud), `Daten/ScanLeser.swift` erkennt Menge (L/kWh), Preis pro Einheit, Summe, Datum und Tankstelle. Das Foto liegt unter Application Support/belege/<id>.jpg und wird beim Abgleich an `tankvorgang.beleg` hochgeladen (Server-Migration `1762600000_beleg.js`); Fotos von anderen Handys werden beim Öffnen mit Datei-Token geladen.

"km-Stand vom Tacho fotografieren" gibt es im Tankformular, beim Tacho-Abgleich einer Fahrt und beim Nachtragen; übernommen wird erst nach "Ja, stimmt". Gleiche Regeln und Testfälle wie Android (`LogikTests`).
