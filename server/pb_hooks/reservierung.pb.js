/// <reference path="../pb_data/types.d.ts" />
// Reservierungen prüfen (Roadmap Nr. 17), Regeln in pb_hooks/reservierung.js.
onRecordCreateRequest((e) => {
  require(`${__hooks}/reservierung.js`).pruefen(e.app, e.record);
  e.next();
}, "reservierung");

onRecordUpdateRequest((e) => {
  require(`${__hooks}/reservierung.js`).pruefen(e.app, e.record);
  e.next();
}, "reservierung");
