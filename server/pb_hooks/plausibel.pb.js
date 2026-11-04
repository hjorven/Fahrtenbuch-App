/// <reference path="../pb_data/types.d.ts" />
// Plausibilitätsprüfung für Daten aus den Apps.
//
// Offensichtlich unmögliche Werte werden mit 400 abgelehnt (die Apps prüfen dieselben
// Regeln schon bei der Eingabe, hier ist nur das Sicherheitsnetz). Kleinere Fehler
// werden still korrigiert:
// - Laden zuhause kostet nichts: preis_pro_liter und gesamtpreis werden 0.
// - Fehlt der Gesamtpreis, wird er aus Menge mal Preis berechnet.
// - GPS-Ausreißer über 250 km/h bei max_kmh/schnitt_kmh werden auf 0 (unbekannt) gesetzt.
//
// Superuser (Admin-Oberfläche) werden nicht geprüft, damit man dort alles reparieren kann.
//
// Hinweis: PocketBase führt jeden Handler isoliert aus, deshalb stehen die Regeln
// in pb_hooks/plausibel.js und werden pro Aufruf mit require() geladen.

onRecordCreateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).tankvorgang(e.record);
  e.next();
}, "tankvorgang");

onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).tankvorgang(e.record);
  e.next();
}, "tankvorgang");

onRecordCreateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).fahrt(e.record);
  e.next();
}, "fahrt");

onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).fahrt(e.record);
  e.next();
}, "fahrt");

onRecordCreateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).fahrzeug(e.record);
  e.next();
}, "fahrzeug");

onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).fahrzeug(e.record);
  e.next();
}, "fahrzeug");

onRecordCreateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).ausgleich(e.record);
  e.next();
}, "ausgleich");

onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).ausgleich(e.record);
  e.next();
}, "ausgleich");

onRecordCreateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).kosten(e.record);
  e.next();
}, "kosten");

onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) require(`${__hooks}/plausibel.js`).kosten(e.record);
  e.next();
}, "kosten");
