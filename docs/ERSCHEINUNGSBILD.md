# Erscheinungsbild (Roadmap Nr. 23, Vorschlag)

Stand der Nacht 2026-11-03/04. **Nur ein Vorschlag, noch nichts umgesetzt.** Über Farben und Stil entscheidet Admin; danach setzt die Cloud oder die PC-Session das in Apps und Firmware um.

## Was es heute gibt

| Wo | Grundfarbe / Akzent | Hintergrund | Schrift |
|---|---|---|---|
| Android | Material 3, `primary` #1B5FAA (dunkel #A4C8FF) | System hell/dunkel | Roboto (System) |
| iOS | keine eigene Akzentfarbe (System-Blau), Glas-Knöpfe | System hell/dunkel | SF (System) |
| Display | Grau #9AA5B1 für Nebentext, Rot/Bernstein für Warnungen | fast schwarz #101418, Kopf #1E2A35, Karte #0B0F14 | Montserrat (LVGL) |
| Gemeinsam | Autofarben aus `fahrzeug.farbe` (z. B. Kombi #2E7D32, Bus #EF6C00, E-Auto #1565C0) | | |

Die Autofarben sind schon überall dieselben. Was auseinanderläuft: der Akzent (Android Blau #1B5FAA, iOS System-Blau, Display ohne Akzent) und die Warnfarben.

## Vorschlag

1. **Ein Akzent für alles:** #1B5FAA (Android-`primary`), auf dunklem Grund #A4C8FF. iOS: `AccentColor` im Asset-Katalog damit füllen. Display: Knöpfe und Auswahl in dieser Farbe statt LVGL-Blau.
2. **Autofarben bleiben die Hauptfarbe** für alles, was zu einem Auto gehört (Punkt, Linie auf der Karte, Kopfzeile am Display).
3. **Warnfarben einheitlich:** Rot #D32F2F für „fällig/Fehler“, Bernstein #F9A825 für „bald/Hinweis“, Grün nur für „erledigt/bezahlt“.
4. **Ruhige Flächen:** wenig Rahmen, eckig statt stark gerundet, eine Schrift pro Plattform (System-Schrift in den Apps, Montserrat am Display).
5. **Icon:** das neue App-Icon (Runde 11) als Startbild am Display (Roadmap Nr. 18 „Startbild mit Logo“).

## Offen für Admin

- Akzent so lassen (Blau) oder eine andere Grundfarbe?
- Display eher dunkel (wie jetzt) oder hell am Tag?
