/// <reference path="../pb_data/types.d.ts" />
// Ausgleichszahlungen für die Kostenaufteilung.
//
// Die Statistik rechnet pro Monat aus, wer wem etwas schuldet. Zahlt jemand den Betrag
// (z. B. Admin überweist dem Ben 48 Euro für Oktober), wird das hier eingetragen:
// "von" hat bezahlt, "an" hat das Geld bekommen, "zeitraum" ist der Monat der Abrechnung
// ("2026-10"). Der Saldo des Monats rechnet den Ausgleich mit ein.
//
// Eintragen und ändern darf, wer selbst beteiligt ist (von oder an). Gelöscht wird wie
// überall nur über geloescht = true.

const AUTH = '@request.auth.id != ""';

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const ausgleich = new Collection({
    type: "base",
    name: "ausgleich",
    listRule: AUTH,
    viewRule: AUTH,
    createRule: AUTH + " && (@request.body.von = @request.auth.id || @request.body.an = @request.auth.id)",
    updateRule: "von = @request.auth.id || an = @request.auth.id",
    deleteRule: null,
    fields: [
      { name: "von", type: "relation", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "an", type: "relation", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: false },
      { name: "betrag", type: "number", required: true, min: 0 },
      { name: "zeitraum", type: "text", required: true, pattern: "^\\d{4}-\\d{2}$", max: 7 },
      { name: "datum", type: "date", required: true },
      { name: "notiz", type: "text", max: 2000 },
      { name: "geloescht", type: "bool" },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
      { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
    ],
    indexes: [
      "CREATE INDEX idx_ausgleich_zeitraum ON ausgleich (zeitraum)",
      "CREATE INDEX idx_ausgleich_updated ON ausgleich (updated)",
    ],
  });
  app.save(ausgleich);
}, (app) => {
  try {
    app.delete(app.findCollectionByNameOrId("ausgleich"));
  } catch (_) {}
});
