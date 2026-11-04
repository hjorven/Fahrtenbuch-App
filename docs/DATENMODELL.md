# Datenmodell

Alle Tabellen liegen in PocketBase. Die Definition steht als Migration in `server/pb_migrations/`. Die Apps spiegeln dieses Modell lokal (SwiftData / Room).

Jede Tabelle hat zusätzlich die PocketBase-Felder `id`, `created`, `updated`. Die `id` erzeugt die App selbst (15 Zeichen, a-z0-9), damit offline angelegte Datensätze ohne Konflikt hochgeladen werden können.

## users (PocketBase-Standard)

Ein Benutzer pro Person: Ben, Anna, Admin.

| Feld | Typ | Beschreibung |
|---|---|---|
| `name` | Text | Anzeigename |

## fahrzeug

| Feld | Typ | Beschreibung |
|---|---|---|
| `name` | Text, Pflicht | z. B. "Bus" |
| `kennzeichen` | Text | |
| `kraftstoff` | Auswahl | benzin, diesel, elektro, sonstiges |
| `km_stand` | Zahl | zuletzt bekannter Kilometerstand |
| `preis_pro_km` | Zahl | für die Kostenaufteilung (V2) |
| `farbe` | Text | Farbe in der App, z. B. `#2E7D32` |
| `aktiv` | Bool | ausgeblendet, wenn verkauft |

## fahrt

| Feld | Typ | Beschreibung |
|---|---|---|
| `fahrzeug` | Relation fahrzeug, Pflicht | |
| `fahrer` | Relation users, Pflicht | |
| `start` | Datum, Pflicht | |
| `ende` | Datum | leer, solange die Fahrt läuft |
| `km_start` | Zahl | |
| `km_ende` | Zahl | |
| `strecke_km` | Zahl | aus GPS berechnet |
| `max_kmh` | Zahl | |
| `schnitt_kmh` | Zahl | |
| `zweck` | Auswahl | privat, geschaeftlich, sonstiges |
| `start_ort` | Text | z. B. per Reverse-Geocoding |
| `ziel_ort` | Text | |
| `route` | Text | Encoded Polyline der Strecke |
| `notiz` | Text | |
| `geloescht` | Bool | Soft-Delete |

Die Route wird als Encoded Polyline (Google-Format, Präzision 5) gespeichert. Eine Stunde Fahrt mit einem Punkt alle 5 Sekunden ergibt etwa 720 Punkte und nur wenige KB. Geschwindigkeiten pro Punkt kommen bei Bedarf als eigenes Feld `geschwindigkeiten` (JSON-Array) dazu.

## tankvorgang

| Feld | Typ | Beschreibung |
|---|---|---|
| `fahrzeug` | Relation fahrzeug, Pflicht | |
| `fahrer` | Relation users, Pflicht | |
| `datum` | Datum, Pflicht | |
| `km_stand` | Zahl, Pflicht | |
| `liter` | Zahl, Pflicht | |
| `preis_pro_liter` | Zahl | |
| `gesamtpreis` | Zahl | |
| `vollgetankt` | Bool | nur volle Tankungen zählen für den Verbrauch |
| `tankstelle` | Text | |
| `notiz` | Text | |
| `geloescht` | Bool | |

Verbrauch zwischen zwei vollen Tankungen:

```
verbrauch_l_100km = summe_liter_seit_letzter_vollen / (km_jetzt - km_letzte_volle) * 100
```

## kosten

| Feld | Typ | Beschreibung |
|---|---|---|
| `fahrzeug` | Relation fahrzeug, Pflicht | |
| `bezahlt_von` | Relation users | |
| `datum` | Datum, Pflicht | |
| `art` | Auswahl | werkstatt, versicherung, steuer, waesche, parken, sonstiges |
| `betrag` | Zahl, Pflicht | |
| `notiz` | Text | |
| `geloescht` | Bool | |

## Zugriffsregeln

Alle angemeldeten Benutzer dürfen alles lesen. Ändern und Löschen von Fahrten und Tankvorgängen darf nur der Ersteller:

```
listRule / viewRule:  @request.auth.id != ""
createRule:           @request.auth.id != "" && @request.body.fahrer = @request.auth.id
updateRule:           fahrer = @request.auth.id
deleteRule:           (leer = nur Admin; Löschen läuft über geloescht = true)
```

**Daten aufräumen immer über `geloescht = true`, nicht hart löschen.** Ein Handy, das einen Datensatz noch lokal hat, lädt einen hart gelöschten Datensatz beim nächsten Abgleich wieder hoch. Mit `geloescht = true` übernehmen alle Handys das Löschen.
