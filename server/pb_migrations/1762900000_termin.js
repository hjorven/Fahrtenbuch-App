/// <reference path="../pb_data/types.d.ts" />
// Termine pro Auto (Roadmap Nr. 16): TÜV, Inspektion, Reifenwechsel, Versicherung, Sonstiges.
// Alle aus der Familie dürfen sie sehen und pflegen; erledigt = true statt löschen.
// Die Bridge zeigt fällige Termine (30 Tage vorher) am Display, die Apps erinnern.

const AUTH = '@request.auth.id != ""';

migrate((app) => {
  const fahrzeug = app.findCollectionByNameOrId("fahrzeug");
  const termin = new Collection({
    type: "base",
    name: "termin",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH,
    updateRule: AUTH,
    deleteRule: null,
    fields: [
      { name: "fahrzeug", type: "relation", required: true, collectionId: fahrzeug.id, maxSelect: 1, cascadeDelete: false },
      { name: "art", type: "select", required: true, maxSelect: 1, values: ["tuev", "inspektion", "reifen", "versicherung", "sonstiges"] },
      { name: "faellig", type: "date", required: true },
      { name: "notiz", type: "text", max: 2000 },
      { name: "erledigt", type: "bool" },
      { name: "geloescht", type: "bool" },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
      { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE INDEX idx_termin_faellig ON termin (faellig)"],
  });
  app.save(termin);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("termin"));
});
