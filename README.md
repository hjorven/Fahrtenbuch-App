# Fahrtenbuch

Familien-Fahrtenbuch für drei Autos und drei Fahrer. Fahrten, Tankvorgänge und Kosten werden auf iPhone und Android erfasst und über einen eigenen Server im Heimnetz synchronisiert.

| Fahrzeug | Notiz |
|---|---|
| E-Auto | |
| Kombi | |
| Bus | |

## Bestandteile

| Branch | Ordner | Inhalt | Stand |
|---|---|---|---|
| `main` | `docs/` | Plan, Datenmodell, SideStore-Anleitung | fertig |
| `server` | `server/` | PocketBase (Docker) mit Datenbank-Schema als Migration | getestet |
| `ios` | `ios/` | iPhone-App, SwiftUI (iOS 26), Verteilung über SideStore | Version 1 |
| `android` | `android/` | Android-App, Jetpack Compose + Material 3 | Version 1 |

Jede Plattform wird in ihrem eigenen Branch entwickelt. Stabile Stände werden per Pull Request nach `main` gemergt, sodass `main` immer den zusammenpassenden Gesamtstand enthält.

## Dokumentation

- [Projektplan](https://github.com/hjorven/Fahrtenbuch-App/blob/main/docs/PLAN.md)
- [Datenmodell](https://github.com/hjorven/Fahrtenbuch-App/blob/main/docs/DATENMODELL.md)
- [iPhone-Installation mit SideStore](https://github.com/hjorven/Fahrtenbuch-App/blob/main/docs/SIDESTORE.md)
- [Aufgaben für den PC](https://github.com/hjorven/Fahrtenbuch-App/blob/main/docs/PC-AUFGABEN.md) (Checkliste für Claude Code im Terminal)
- Server einrichten: `server/README.md` im Branch `server`

## Schnellstart Server

```bash
git clone -b server git@github.com:hjorven/Fahrtenbuch-App.git
cd Fahrtenbuch-App/server
docker compose up -d
```

Danach Admin-Oberfläche unter `http://<server-ip>:8090/_/` öffnen.
