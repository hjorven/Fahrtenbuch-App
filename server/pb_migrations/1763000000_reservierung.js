/// <reference path="../pb_data/types.d.ts" />
// Auto reservieren (Roadmap Nr. 17): "Ich brauche den Kombi am Samstag 9 bis 14 Uhr".
// Alle sehen alle Reservierungen; anlegen nur für sich selbst, ändern nur die eigenen.
// Überschneidungen mit einer anderen Reservierung desselben Autos lehnt pb_hooks/reservierung.pb.js ab.

const AUTH = '@request.auth.id != ""';

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const fahrzeug = app.findCollectionByNameOrId("fahrzeug");
  const r = new Collection({
    type: "base",
    name: "reservierung",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH + " && @request.body.wer = @request.auth.id",
    updateRule: "wer = @request.auth.id",
    deleteRule: null,
    fields: [
      { name: "fahrzeug", type: "relation", required: true, collectionId: fahrzeug.id, maxSelect: 1, cascadeDelete: false },
      { name: "wer", type: "relation", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "von", type: "date", required: true },
      { name: "bis", type: "date", required: true },
      { name: "notiz", type: "text", max: 2000 },
      { name: "geloescht", type: "bool" },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
      { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE INDEX idx_reservierung_von ON reservierung (fahrzeug, von)"],
  });
  app.save(r);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("reservierung"));
});
