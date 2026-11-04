/// <reference path="../pb_data/types.d.ts" />
// Test-Modus der Apps.
//
// - fahrt.test / tankvorgang.test: Datensätze aus dem Test-Modus. Sie zählen nirgends mit
//   (Statistik, Rekorde, Kosten, Verbrauch, Display) und setzen den km-Stand des Autos
//   nicht hoch (das verhindern die Apps, weil der km-Stand ein eigener Datensatz ist).
//   Aufgeräumt wird wie überall über geloescht = true, damit kein Handy sie neu hochlädt.
// - fahrt.diagnose: JSON mit Akku-, GPS-, Hintergrund- und Sync-Diagnose einer Fahrt,
//   nur im Test-Modus gefüllt.

migrate((app) => {
  const fahrt = app.findCollectionByNameOrId("fahrt");
  fahrt.fields.add(new BoolField({ name: "test" }));
  fahrt.fields.add(new JSONField({ name: "diagnose", maxSize: 200000 }));
  app.save(fahrt);

  const tank = app.findCollectionByNameOrId("tankvorgang");
  tank.fields.add(new BoolField({ name: "test" }));
  app.save(tank);
}, (app) => {
  const fahrt = app.findCollectionByNameOrId("fahrt");
  fahrt.fields.removeByName("test");
  fahrt.fields.removeByName("diagnose");
  app.save(fahrt);

  const tank = app.findCollectionByNameOrId("tankvorgang");
  tank.fields.removeByName("test");
  app.save(tank);
});
