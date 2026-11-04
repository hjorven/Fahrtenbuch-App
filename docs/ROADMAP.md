# Roadmap

Stand 2026-11-03, 22:40. Ziel: Das Fahrtenbuch wirkt und funktioniert wie ein fertiges Produkt, für Admin und seine Eltern.

Ideen und Begründungen: Dokument "Fahrtenbuch: Runde 2, auf dem Weg zum fertigen Produkt" (https://claude.ai/artifact/EJS4T2RVvoNWtpQunbRH5d). Admin hat am 2026-11-03 um 22:35 **alle Punkte außer Nr. 8** (einfache Ansicht) gewählt und Nr. 6 (Bluetooth) überarbeiten lassen, siehe unten. Umsetzung: [NACHTPLAN.md](NACHTPLAN.md), Phase 6c.

Legende Aufwand: S = ein Abend, M = ein bis zwei Abende, L = mehrere Teile (App, Server, Display).

## Stufe 1: Stabil (läuft, Nachtplan Phase 0 bis 8)

- 17 Befunde aus dem adb-Test, Live-Upload laufender Fahrten alle 2 Minuten, Logo und SideStore-Quelle, Display-Ansichten im Wechsel.
- Danach Praxistest mit allen drei Handys.

## Stufe 2: Einfach einrichten

Ziel: Ein neues Handy ist in unter 2 Minuten verbunden, ohne dass jemand eine Adresse oder ein Passwort abtippt.

| Nr. | Punkt | Wo | Aufwand |
|---|---|---|---|
| 1 | Handy per QR-Code verbinden (Display "Mehr > Handy verbinden", App "QR-Code scannen") | Apps, Bridge, Firmware | L |
| 2 | Konto per QR-Einladung statt Start-Passwort, Umzug auf ein neues Handy per QR | Server, Apps | L |
| 3 | Tailscale-Adresse kommt automatisch vom Server, Server-Suche im Heimnetz (mDNS) | Server, Pi, Apps | M |
| 4 | Startseite auf dem Pi (Port 8091) mit Installation, SideStore-Quelle und QR-Codes | Server (downloads) | S |
| 5 | Verbindungstest als Ampel in der Einrichtung, mit "So behebst du das" | Apps | S |

## Stufe 3: Nichts vergessen

Ziel: Jede Fahrt wird aufgezeichnet, auch wenn niemand daran denkt, und jeder Beleg ist in 10 Sekunden erfasst.

| Nr. | Punkt | Wo | Aufwand |
|---|---|---|---|
| 6 | Auto-Bluetooth erkennt die Fahrt und fragt, wer fährt (Beifahrer, Fahrer wechseln, Kosten teilen) | Server, Apps | L |
| 7 | "Fahrt vergessen?" aus der Bewegungserkennung | Apps | M |
| 9 | Tanken in drei Schritten (Kamera zuerst) | Apps | S |
| 12 | Beleg-Scan mit echten Belegen prüfen, Scan-Protokoll im Test-Modus | Apps | M |

### Nr. 6 im Detail (überarbeitet nach des Admins Einwand)

Problem: Sitzt Admin als Beifahrer im Auto und verbindet sein Handy für Musik, darf die Fahrt nicht ihm zugeschrieben werden.

1. **Nie still starten, sondern fragen.** Verbindet sich das Handy mit dem Bluetooth eines Autos (Zuordnung Gerät zu Auto in den Einstellungen), kommt eine Benachrichtigung "Kombi verbunden. Fährst du?" mit **Ich fahre**, **Ich fahre mit** und **Nein**.
2. **Läuft schon eine Fahrt eines anderen mit diesem Auto** (Server, dank Live-Upload höchstens 2 Minuten alt), kommt keine Frage nach dem Fahrer, sondern "Ben fährt den Kombi" mit **Ich fahre mit**.
3. **Keine Antwort:** Meldet die Bewegungserkennung nach 3 Minuten "im Fahrzeug", startet die App eine **vorläufige Fahrt**. Am Ende fragt sie "War das deine Fahrt?" mit **Ja**, **Jemand anderes ist gefahren** (Fahrer wählen) und **Löschen**. Vorläufige Fahrten zählen erst nach Bestätigung, am Display stehen sie als "unbestätigt".
4. **Fahrer wechseln:** In der laufenden Fahrt und im Fahrt-Detail. Unterwegs wird die Fahrt am aktuellen Punkt geteilt (Teilen gibt es schon), der zweite Teil gehört dem neuen Fahrer. Nachträglich geht die ganze Fahrt an eine andere Person.
5. **Kosten teilen:** "Ich fahre mit" oder ein Schalter im Fahrt-Detail trägt Mitfahrer ein. Der Anteil an den Kosten wird dann gleichmäßig auf Fahrer und Mitfahrer verteilt, die km zählen weiter nur beim Fahrer.
6. **Zwei Handys gleichzeitig:** Wer zuerst "Ich fahre" drückt, startet. Das zweite Handy sieht die Fahrt beim nächsten Abgleich (direkt nach dem Start) und bietet nur noch "Ich fahre mit" an.

Server: Feld `fahrt.mitfahrer` (Relation auf mehrere `users`), Feld `fahrt.vorlaeufig` (Bool), Hook: Den Fahrer darf nur der bisherige Fahrer ändern. Abrechnung in beiden Apps teilt den Anteil durch (1 + Anzahl Mitfahrer). Die Bridge zählt vorläufige Fahrten nicht in Rekorde und Kosten.

## Stufe 4: Für die Familie

Ziel: Anna und Ben nutzen die App ohne Rückfragen.

| Nr. | Punkt | Wo | Aufwand |
|---|---|---|---|
| 10 | Monatsabrechnung als Nachricht mit "Bezahlt" | Apps | M |
| 11 | Klare Fehlermeldungen mit "Was tun?", Hilfe-Seite | Apps | S |
| 13 | Belege ansehen (Vorschau, Vollbild, auch die der anderen) | Apps | S |

Nr. 8 (einfache Ansicht) entfällt (Admin, 2026-11-03).

## Stufe 5: Pflegen ohne Hinfahren

Ziel: Der Admin sieht Probleme, bevor jemand anruft.

| Nr. | Punkt | Wo | Aufwand |
|---|---|---|---|
| 15 | Geräte-Übersicht (App-Version, letzter Sync pro Person), Pi-Status | Server, Apps, Bridge | M |
| 19 | Monatliche Backup-Probe | Server | S |
| 20 | Oberflächentests mit Screenshots in der CI (iOS-Simulator, Android-Emulator) | CI | M |
| 21 | "Problem melden" schickt Diagnose an den Server | Server, Apps | M |

## Stufe 6: Extras

| Nr. | Punkt | Wo | Aufwand |
|---|---|---|---|
| 14 | Lade-App-Screenshots über "Teilen" importieren | Apps | M |
| 16 | Termine pro Auto (TÜV, Inspektion, Reifen, Versicherung) | Server, Apps, Bridge | M |
| 17 | Auto reservieren | Server, Apps, Bridge | L |
| 18 | Display: Uhrzeit, Wetter mit Glättewarnung, Startbild mit Logo | Bridge, Firmware | S |
| 22 | Export als PDF und CSV | Apps | M |
| 23 | Ein Erscheinungsbild für Apps und Display | Apps, Firmware | M |
| 24 | Bessere Widgets | Apps | S |
| 25 | Sonstige Kosten (Werkstatt, Versicherung, Parken) | Server, Apps | M |

## Offen

- E-Auto-API (inoffiziell, km-Stand und Ladevorgänge): wartet auf des Admins Entscheidung.
- Nr. 12 braucht 5 bis 10 Fotos echter Belege von Admin.
