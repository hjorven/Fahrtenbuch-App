/// <reference path="../pb_data/types.d.ts" />
// km-Stand eines Autos darf über die Apps nur steigen.
//
// Fall: Zwei Handys fahren dasselbe Auto, eins ist dabei offline. Es kennt noch den alten
// km-Stand, zählt seine Fahrt darauf und lädt später einen niedrigeren Wert hoch als den,
// der schon auf dem Server steht. Ohne diese Regel würde der neuere, höhere Stand
// überschrieben. Jetzt bleibt der höhere Wert stehen, und die Apps holen ihn beim
// nächsten Abgleich wieder ab.
//
// Einen zu hoch eingetippten km-Stand korrigiert man in der Admin-Oberfläche
// (Superuser dürfen den Wert auch senken).
onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) {
    const alt = e.record.original().getFloat("km_stand");
    const neu = e.record.getFloat("km_stand");
    if (neu < alt) {
      e.record.set("km_stand", alt);
    }
  }
  e.next();
}, "fahrzeug");
