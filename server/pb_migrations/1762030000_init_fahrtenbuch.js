/// <reference path="../pb_data/types.d.ts" />
// Erstes Schema für das Fahrtenbuch (PocketBase v0.23+).
// Wird beim Start von PocketBase automatisch eingespielt.

const AUTH = '@request.auth.id != ""';

function zeitstempel() {
  return [
    { name: "created", type: "autodate", onCreate: true, onUpdate: false },
    { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
  ];
}

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");

  // ---------- fahrzeug ----------
  const fahrzeug = new Collection({
    type: "base",
    name: "fahrzeug",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH,
    updateRule: AUTH,
    deleteRule: null,
    fields: [
      { name: "name", type: "text", required: true, max: 60 },
      { name: "kennzeichen", type: "text", max: 15 },
      { name: "kraftstoff", type: "select", maxSelect: 1, values: ["benzin", "diesel", "elektro", "sonstiges"] },
      { name: "km_stand", type: "number", min: 0 },
      { name: "preis_pro_km", type: "number", min: 0 },
      { name: "farbe", type: "text", max: 9 },
      { name: "aktiv", type: "bool" },
      ...zeitstempel(),
    ],
  });
  app.save(fahrzeug);

  // ---------- fahrt ----------
  const fahrt = new Collection({
    type: "base",
    name: "fahrt",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH + " && @request.body.fahrer = @request.auth.id",
    updateRule: "fahrer = @request.auth.id",
    deleteRule: null,
    fields: [
      { name: "fahrzeug", type: "relation", required: true, collectionId: fahrzeug.id, maxSelect: 1, cascadeDelete: false },
      { name: "fahrer", type: "relation", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "start", type: "date", required: true },
      { name: "ende", type: "date" },
      { name: "km_start", type: "number", min: 0 },
      { name: "km_ende", type: "number", min: 0 },
      { name: "strecke_km", type: "number", min: 0 },
      { name: "max_kmh", type: "number", min: 0 },
      { name: "schnitt_kmh", type: "number", min: 0 },
      { name: "zweck", type: "select", maxSelect: 1, values: ["privat", "geschaeftlich", "sonstiges"] },
      { name: "start_ort", type: "text", max: 200 },
      { name: "ziel_ort", type: "text", max: 200 },
      { name: "route", type: "text", max: 2000000 },
      { name: "notiz", type: "text", max: 2000 },
      { name: "geloescht", type: "bool" },
      ...zeitstempel(),
    ],
    indexes: [
      "CREATE INDEX idx_fahrt_fahrzeug_start ON fahrt (fahrzeug, start)",
      "CREATE INDEX idx_fahrt_updated ON fahrt (updated)",
    ],
  });
  app.save(fahrt);

  // ---------- tankvorgang ----------
  const tank = new Collection({
    type: "base",
    name: "tankvorgang",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH + " && @request.body.fahrer = @request.auth.id",
    updateRule: "fahrer = @request.auth.id",
    deleteRule: null,
    fields: [
      { name: "fahrzeug", type: "relation", required: true, collectionId: fahrzeug.id, maxSelect: 1, cascadeDelete: false },
      { name: "fahrer", type: "relation", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "datum", type: "date", required: true },
      { name: "km_stand", type: "number", required: true, min: 0 },
      { name: "liter", type: "number", required: true, min: 0 },
      { name: "preis_pro_liter", type: "number", min: 0 },
      { name: "gesamtpreis", type: "number", min: 0 },
      { name: "vollgetankt", type: "bool" },
      { name: "tankstelle", type: "text", max: 200 },
      { name: "notiz", type: "text", max: 2000 },
      { name: "geloescht", type: "bool" },
      ...zeitstempel(),
    ],
    indexes: [
      "CREATE INDEX idx_tank_fahrzeug_datum ON tankvorgang (fahrzeug, datum)",
      "CREATE INDEX idx_tank_updated ON tankvorgang (updated)",
    ],
  });
  app.save(tank);

  // ---------- kosten ----------
  const kosten = new Collection({
    type: "base",
    name: "kosten",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH,
    updateRule: AUTH,
    deleteRule: null,
    fields: [
      { name: "fahrzeug", type: "relation", required: true, collectionId: fahrzeug.id, maxSelect: 1, cascadeDelete: false },
      { name: "bezahlt_von", type: "relation", collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "datum", type: "date", required: true },
      { name: "art", type: "select", maxSelect: 1, values: ["werkstatt", "versicherung", "steuer", "waesche", "parken", "sonstiges"] },
      { name: "betrag", type: "number", required: true },
      { name: "notiz", type: "text", max: 2000 },
      { name: "geloescht", type: "bool" },
      ...zeitstempel(),
    ],
  });
  app.save(kosten);

  // ---------- Startdaten: die drei Autos ----------
  const autos = [
    { name: "E-Auto", farbe: "#1565C0" },
    { name: "Kombi", farbe: "#2E7D32" },
    { name: "Bus", farbe: "#EF6C00" },
  ];
  for (const a of autos) {
    const r = new Record(fahrzeug);
    r.set("name", a.name);
    r.set("farbe", a.farbe);
    r.set("aktiv", true);
    app.save(r);
  }
}, (app) => {
  for (const name of ["kosten", "tankvorgang", "fahrt", "fahrzeug"]) {
    try {
      app.delete(app.findCollectionByNameOrId(name));
    } catch (_) {}
  }
});
