/// <reference path="../pb_data/types.d.ts" />
// Einladungs- und Reset-Codes (Roadmap Nr. 1/2, Nachtplan 5c "Passwort vergessen").
//
// typ "einladung": ein angemeldetes Familienmitglied lädt jemanden ein (24 h gültig).
// typ "reset": der Admin (Superuser, z. B. das Display) erzeugt für ein Konto einen
// Code zum Passwort-Zurücksetzen (15 min gültig). Jeder Code ist einmal benutzbar.
//
// Kein direkter Zugriff über die API (alle Regeln null): nur die Hooks in
// pb_hooks/einladung.pb.js lesen und schreiben die Codes.

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const einladung = new Collection({
    type: "base",
    name: "einladung",
    listRule: null,
    viewRule: null,
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      { name: "code", type: "text", required: true, min: 12, max: 12 },
      { name: "typ", type: "select", required: true, maxSelect: 1, values: ["einladung", "reset"] },
      { name: "erstellt_von", type: "relation", collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "fuer", type: "relation", collectionId: users.id, maxSelect: 1, cascadeDelete: true },
      { name: "gueltig_bis", type: "date", required: true },
      { name: "benutzt", type: "bool" },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
    ],
    indexes: ["CREATE UNIQUE INDEX idx_einladung_code ON einladung (code)"],
  });
  app.save(einladung);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("einladung"));
});
