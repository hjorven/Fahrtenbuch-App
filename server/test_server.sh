#!/usr/bin/env bash
# Prüft Migrationen und Hooks gegen eine echte PocketBase (gleiche Version wie docker-compose.yml).
# Läuft in GitHub Actions, geht aber auch lokal:  cd server && ./test_server.sh
set -euo pipefail
cd "$(dirname "$0")"

VERSION=$(grep -oE 'pocketbase:[0-9.]+' docker-compose.yml | cut -d: -f2)
ARBEIT=$(mktemp -d)
trap 'kill "${PB_PID:-0}" 2>/dev/null || true; rm -rf "$ARBEIT"' EXIT

echo "PocketBase $VERSION"
curl -sSL -o "$ARBEIT/pb.zip" "https://github.com/pocketbase/pocketbase/releases/download/v${VERSION}/pocketbase_${VERSION}_linux_amd64.zip"
unzip -q "$ARBEIT/pb.zip" -d "$ARBEIT"
PB="$ARBEIT/pocketbase"
URL=http://127.0.0.1:8099

"$PB" serve --dir="$ARBEIT/data" --migrationsDir=pb_migrations --hooksDir=pb_hooks --http=127.0.0.1:8099 >"$ARBEIT/pb.log" 2>&1 &
PB_PID=$!
for _ in $(seq 1 30); do curl -sf "$URL/api/health" >/dev/null && break; sleep 1; done
curl -sf "$URL/api/health" >/dev/null || { cat "$ARBEIT/pb.log"; exit 1; }

"$PB" superuser upsert admin@test.local AdminPasswort123 --dir="$ARBEIT/data" >/dev/null

fehler() { echo "FEHLER: $*"; cat "$ARBEIT/pb.log"; exit 1; }
json() { curl -sS -H 'Content-Type: application/json' "$@"; }

ADMIN=$(json -X POST "$URL/api/collections/_superusers/auth-with-password" \
  -d '{"identity":"admin@test.local","password":"AdminPasswort123"}' | jq -r .token)
[ -n "$ADMIN" ] && [ "$ADMIN" != null ] || fehler "Superuser-Login"

benutzer() {
  json -X POST "$URL/api/collections/users/records" -H "Authorization: $ADMIN" \
    -d "{\"email\":\"$1@test.local\",\"password\":\"Passwort12345\",\"passwordConfirm\":\"Passwort12345\",\"name\":\"$1\",\"verified\":true}" | jq -r .id
}
login() {
  json -X POST "$URL/api/collections/users/auth-with-password" \
    -d "{\"identity\":\"$1@test.local\",\"password\":\"Passwort12345\"}" | jq -r .token
}
BEN=$(benutzer ben); benutzer anna >/dev/null
T_BEN=$(login ben); T_ANNA=$(login anna)

AUTO=$(curl -sS "$URL/api/collections/fahrzeug/records" -H "Authorization: $T_BEN" | jq -r '.items[0].id')
[ -n "$AUTO" ] && [ "$AUTO" != null ] || fehler "kein Auto"

TANK=$(json -X POST "$URL/api/collections/tankvorgang/records" -H "Authorization: $T_BEN" \
  -d "{\"id\":\"belegtest000001\",\"fahrzeug\":\"$AUTO\",\"fahrer\":\"$BEN\",\"datum\":\"2026-11-03 10:00:00.000Z\",\"km_stand\":123456,\"liter\":45.23,\"preis_pro_liter\":1.789,\"vollgetankt\":true}")
echo "$TANK" | jq -e '.id == "belegtest000001" and .beleg == ""' >/dev/null || fehler "Anlegen: $TANK"
echo "Tankvorgang angelegt, Feld beleg vorhanden"

# Kleinstes gültiges JPEG-Kopfstück reicht für die Typ-Erkennung.
printf '\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9' >"$ARBEIT/beleg.jpg"

# Fremder Benutzer darf kein Foto anhängen.
CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "$URL/api/collections/tankvorgang/records/belegtest000001" \
  -H "Authorization: $T_ANNA" -F "beleg=@$ARBEIT/beleg.jpg;type=image/jpeg")
[ "$CODE" = 404 ] || [ "$CODE" = 403 ] || fehler "Fremder Upload ergab $CODE"

ANTWORT=$(curl -sS -X PATCH "$URL/api/collections/tankvorgang/records/belegtest000001" \
  -H "Authorization: $T_BEN" -F "beleg=@$ARBEIT/beleg.jpg;type=image/jpeg")
NAME=$(echo "$ANTWORT" | jq -r .beleg)
[ -n "$NAME" ] && [ "$NAME" != null ] || fehler "Upload: $ANTWORT"
echo "$ANTWORT" | jq -e '.liter == 45.23 and .gesamtpreis > 80' >/dev/null || fehler "Werte nach Upload: $ANTWORT"
echo "Beleg hochgeladen: $NAME"

DATEI="$URL/api/files/tankvorgang/belegtest000001/$NAME"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$DATEI")
[ "$CODE" != 200 ] || fehler "Beleg ohne Token abrufbar"
DTOKEN=$(json -X POST "$URL/api/files/token" -H "Authorization: $T_ANNA" | jq -r .token)
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$DATEI?token=$DTOKEN")
[ "$CODE" = 200 ] || fehler "Beleg mit Token ergab $CODE"
echo "Beleg nur mit Datei-Token abrufbar"

# Ältere App ohne Beleg: normales JSON-PATCH lässt das Foto stehen.
ANTWORT=$(json -X PATCH "$URL/api/collections/tankvorgang/records/belegtest000001" -H "Authorization: $T_BEN" \
  -d '{"tankstelle":"Aral"}')
echo "$ANTWORT" | jq -e --arg n "$NAME" '.beleg == $n and .tankstelle == "Aral"' >/dev/null || fehler "JSON-PATCH: $ANTWORT"
echo "JSON-Änderung behält den Beleg"

# Nachtplan 5b Fall 13: Fahrt und Tankung in der Zukunft lehnt der Server auch ohne App ab.
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/fahrt/records" -H "Authorization: $T_BEN" \
  -d "{\"fahrzeug\":\"$AUTO\",\"fahrer\":\"$BEN\",\"start\":\"2030-01-01 10:00:00.000Z\",\"ende\":\"2030-01-01 11:00:00.000Z\",\"zweck\":\"privat\"}")
[ "$CODE" = 400 ] || fehler "Fahrt in der Zukunft ergab $CODE"
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/tankvorgang/records" -H "Authorization: $T_BEN" \
  -d "{\"fahrzeug\":\"$AUTO\",\"fahrer\":\"$BEN\",\"datum\":\"2030-01-01 10:00:00.000Z\",\"km_stand\":123500,\"liter\":30,\"preis_pro_liter\":1.7}")
[ "$CODE" = 400 ] || fehler "Tankung in der Zukunft ergab $CODE"
JETZT=$(date -u +'%Y-%m-%d %H:%M:%S.000Z')
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/fahrt/records" -H "Authorization: $T_BEN" \
  -d "{\"fahrzeug\":\"$AUTO\",\"fahrer\":\"$BEN\",\"start\":\"$JETZT\",\"zweck\":\"privat\"}")
[ "$CODE" = 200 ] || fehler "Laufende Fahrt ab jetzt ergab $CODE"
echo "Zukunft abgelehnt, laufende Fahrt ab jetzt erlaubt"

# Roadmap Nr. 3: Adressen für die Apps, nur angemeldet.
INFO=$(curl -sS "$URL/api/fahrtenbuch/info" -H "Authorization: $T_BEN")
echo "$INFO" | jq -e '.heimnetz == "http://fahrtenbuch.local:8090" and (.downloads | length) > 0' >/dev/null || fehler "Info: $INFO"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$URL/api/fahrtenbuch/info")
[ "$CODE" = 401 ] || fehler "Info ohne Anmeldung ergab $CODE"
echo "Adressen-Info nur angemeldet"

# Roadmap Nr. 1/2: Einladen per Code, einmal benutzbar.
EIN=$(json -X POST "$URL/api/fahrtenbuch/einladung" -H "Authorization: $T_BEN")
CODE_E=$(echo "$EIN" | jq -r .code)
[ ${#CODE_E} = 12 ] || fehler "Einladung: $EIN"
NEU=$(json -X POST "$URL/api/fahrtenbuch/beitreten" -d "{\"code\":\"$CODE_E\",\"name\":\"Clara\",\"email\":\"clara@test.local\",\"passwort\":\"OmaPasswort123\"}")
echo "$NEU" | jq -e '.token != null and .record.name == "Clara" and .record.passwort_aendern == false' >/dev/null || fehler "Beitreten: $NEU"
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/fahrtenbuch/beitreten" -d "{\"code\":\"$CODE_E\",\"name\":\"X\",\"email\":\"x@test.local\",\"passwort\":\"XPasswort12345\"}")
[ "$CODE" = 400 ] || fehler "Code zweimal benutzt ergab $CODE"
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/fahrtenbuch/beitreten" -d '{"code":"FALSCHFALSCH","name":"X","email":"y@test.local","passwort":"XPasswort12345"}')
[ "$CODE" = 400 ] || fehler "Falscher Code ergab $CODE"
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/fahrtenbuch/einladung")
[ "$CODE" = 401 ] || fehler "Einladung ohne Anmeldung ergab $CODE"
echo "Einladung: einmal benutzbar, falscher Code abgelehnt"

# Nachtplan 5c: Passwort zurücksetzen per Code vom Admin (15 min).
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/fahrtenbuch/reset" -H "Authorization: $T_BEN" -d '{"email":"anna@test.local"}')
[ "$CODE" = 401 ] || [ "$CODE" = 403 ] || fehler "Reset durch normalen Benutzer ergab $CODE"
RES=$(json -X POST "$URL/api/fahrtenbuch/reset" -H "Authorization: $ADMIN" -d '{"email":"anna@test.local"}')
CODE_R=$(echo "$RES" | jq -r .code)
[ ${#CODE_R} = 12 ] || fehler "Reset: $RES"
NEU=$(json -X POST "$URL/api/fahrtenbuch/zuruecksetzen" -d "{\"code\":\"$CODE_R\",\"passwort\":\"NeuesPasswort123\"}")
echo "$NEU" | jq -e '.token != null and .record.email == "anna@test.local"' >/dev/null || fehler "Zurücksetzen: $NEU"
T=$(json -X POST "$URL/api/collections/users/auth-with-password" -d '{"identity":"anna@test.local","password":"NeuesPasswort123"}' | jq -r .token)
[ -n "$T" ] && [ "$T" != null ] || fehler "Login mit neuem Passwort"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$URL/api/collections/einladung/records" -H "Authorization: $T_BEN")
[ "$CODE" = 403 ] || [ "$CODE" = 404 ] || [ "$CODE" = 200 ] || fehler "Liste einladung ergab $CODE"
LISTE=$(curl -sS "$URL/api/collections/einladung/records" -H "Authorization: $T_BEN")
echo "$LISTE" | jq -e '(.items // []) | length == 0' >/dev/null || fehler "Codes für Benutzer sichtbar: $LISTE"
echo "Reset nur vom Admin, neues Passwort gilt, Codes nicht abrufbar"

# Roadmap Nr. 15/21: Version am eigenen Konto, Problem melden (nur für sich, lesen nur Admin).
CODE=$(json -o /dev/null -w '%{http_code}' -X PATCH "$URL/api/collections/users/records/$BEN" -H "Authorization: $T_BEN" \
  -d '{"app_version":"1.0.60","geraet":"Pixel 8, Android 16","letzter_sync":"2026-11-04 01:00:00.000Z"}')
[ "$CODE" = 200 ] || fehler "app_version setzen ergab $CODE"
BER=$(json -X POST "$URL/api/collections/bericht/records" -H "Authorization: $T_BEN" \
  -d "{\"von\":\"$BEN\",\"text\":\"Fahrt fehlt\",\"diagnose\":{\"punkte\":12},\"app_version\":\"1.0.60\"}")
echo "$BER" | jq -e '.text == "Fahrt fehlt"' >/dev/null || fehler "Bericht: $BER"
ANNA=$(json -X POST "$URL/api/collections/users/auth-with-password" -d '{"identity":"anna@test.local","password":"NeuesPasswort123"}' | jq -r .record.id)
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/bericht/records" -H "Authorization: $T_BEN" -d "{\"von\":\"$ANNA\",\"text\":\"x\"}")
[ "$CODE" = 400 ] || [ "$CODE" = 403 ] || fehler "Bericht für andere ergab $CODE"
LISTE=$(curl -sS "$URL/api/collections/bericht/records" -H "Authorization: $T_BEN")
echo "$LISTE" | jq -e '(.items // []) | length == 0' >/dev/null || fehler "Berichte für Benutzer lesbar: $LISTE"
echo "Version am Konto, Bericht nur für sich und nur für den Admin lesbar"

# Roadmap Nr. 17: Auto reservieren, keine Überschneidung, nur für sich.
res() { json -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/reservierung/records" -H "Authorization: $1" \
  -d "{\"fahrzeug\":\"$AUTO\",\"wer\":\"$2\",\"von\":\"$3\",\"bis\":\"$4\"}"; }
[ "$(res "$T_BEN" "$BEN" "2026-12-05 09:00:00.000Z" "2026-12-05 14:00:00.000Z")" = 200 ] || fehler "Reservierung anlegen"
T_ANNA_NEU=$(json -X POST "$URL/api/collections/users/auth-with-password" -d '{"identity":"anna@test.local","password":"NeuesPasswort123"}' | jq -r .token)
[ "$(res "$T_ANNA_NEU" "$ANNA" "2026-12-05 13:00:00.000Z" "2026-12-05 16:00:00.000Z")" = 400 ] || fehler "Überschneidung nicht abgelehnt"
[ "$(res "$T_ANNA_NEU" "$ANNA" "2026-12-05 14:00:00.000Z" "2026-12-05 16:00:00.000Z")" = 200 ] || fehler "Direkt danach abgelehnt"
[ "$(res "$T_ANNA_NEU" "$ANNA" "2026-12-06 10:00:00.000Z" "2026-12-06 09:00:00.000Z")" = 400 ] || fehler "Ende vor Beginn angenommen"
[ "$(res "$T_ANNA_NEU" "$BEN" "2026-12-07 10:00:00.000Z" "2026-12-07 11:00:00.000Z")" != 200 ] || fehler "Für andere reserviert"
echo "Reservierung: Überschneidung abgelehnt, direkt danach erlaubt, nur für sich"

# Roadmap Nr. 6: Mitfahrer tragen sich selbst ein, ändern sonst nichts an fremden Fahrten.
FAHRT=$(json -X POST "$URL/api/collections/fahrt/records" -H "Authorization: $T_BEN" \
  -d "{\"fahrzeug\":\"$AUTO\",\"fahrer\":\"$BEN\",\"start\":\"2026-11-03 10:00:00.000Z\",\"ende\":\"2026-11-03 11:00:00.000Z\",\"zweck\":\"privat\",\"vorlaeufig\":true}" | jq -r .id)
[ -n "$FAHRT" ] && [ "$FAHRT" != null ] || fehler "Fahrt mit vorlaeufig"
MF=$(json -X POST "$URL/api/fahrtenbuch/mitfahren" -H "Authorization: $T_ANNA_NEU" -d "{\"fahrt\":\"$FAHRT\"}")
echo "$MF" | jq -e --arg m "$ANNA" '.mitfahrer == [$m]' >/dev/null || fehler "Mitfahren: $MF"
CODE=$(json -o /dev/null -w '%{http_code}' -X PATCH "$URL/api/collections/fahrt/records/$FAHRT" -H "Authorization: $T_ANNA_NEU" -d '{"zweck":"sonstiges"}')
[ "$CODE" = 404 ] || [ "$CODE" = 403 ] || fehler "Mitfahrer ändert fremde Fahrt: $CODE"
CODE=$(json -o /dev/null -w '%{http_code}' -X POST "$URL/api/fahrtenbuch/mitfahren" -H "Authorization: $T_BEN" -d "{\"fahrt\":\"$FAHRT\"}")
[ "$CODE" = 400 ] || fehler "Mitfahren an eigener Fahrt ergab $CODE"
MF=$(json -X POST "$URL/api/fahrtenbuch/mitfahren" -H "Authorization: $T_ANNA_NEU" -d "{\"fahrt\":\"$FAHRT\",\"raus\":true}")
echo "$MF" | jq -e '.mitfahrer == []' >/dev/null || fehler "Austragen: $MF"
echo "Mitfahrer: selbst ein- und austragen, fremde Fahrt sonst nicht änderbar"

# Roadmap Nr. 25: Sonstige Kosten, nur im eigenen Namen, unsinnige Beträge abgelehnt.
kost() { json -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/kosten/records" -H "Authorization: $T_BEN" \
  -d "{\"fahrzeug\":\"$AUTO\",\"erfasst_von\":\"$1\",\"datum\":\"$2\",\"art\":\"werkstatt\",\"betrag\":$3,\"test\":true}"; }
[ "$(kost "$BEN" "2026-11-01 10:00:00.000Z" 189.996)" = 200 ] || fehler "Kosten anlegen"
[ "$(kost "$BEN" "2026-11-01 10:00:00.000Z" -5)" = 400 ] || fehler "Negativer Betrag angenommen"
[ "$(kost "$BEN" "2099-01-01 10:00:00.000Z" 20)" = 400 ] || fehler "Kosten in der Zukunft angenommen"
[ "$(kost "$ANNA" "2026-11-01 10:00:00.000Z" 20)" != 200 ] || fehler "Kosten im fremden Namen angenommen"
K=$(json "$URL/api/collections/kosten/records?filter=test%3Dtrue" -H "Authorization: $T_BEN" | jq '.items[0].betrag')
[ "$K" = 190 ] || fehler "Betrag nicht gerundet: $K"
echo "Kosten: Test-Feld, Betrag geprüft und gerundet, nur im eigenen Namen"

echo "Alles in Ordnung"
