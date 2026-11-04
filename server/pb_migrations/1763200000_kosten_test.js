/// <reference path="../pb_data/types.d.ts" />
// Sonstige Kosten (Roadmap Nr. 25): Werkstatt, Versicherung, Steuer, Wäsche, Parken.
// Die Collection `kosten` gibt es seit der ersten Migration; neu sind wie bei tankvorgang
// das Feld `test` (Test-Modus, zählt nirgends mit) und `erfasst_von` (wer es eingetragen hat).

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const kosten = app.findCollectionByNameOrId("kosten");
  kosten.fields.add(new BoolField({ name: "test" }));
  kosten.fields.add(new RelationField({ name: "erfasst_von", collectionId: users.id, maxSelect: 1, cascadeDelete: false }));
  // Anlegen nur im eigenen Namen; ändern dürfen alle aus der Familie (wie bisher).
  kosten.createRule = '@request.auth.id != "" && @request.body.erfasst_von = @request.auth.id';
  kosten.indexes = ["CREATE INDEX idx_kosten_fahrzeug_datum ON kosten (fahrzeug, datum)"];
  app.save(kosten);
}, (app) => {
  const kosten = app.findCollectionByNameOrId("kosten");
  kosten.fields.removeByName("test");
  kosten.fields.removeByName("erfasst_von");
  kosten.createRule = '@request.auth.id != ""';
  kosten.indexes = [];
  app.save(kosten);
});
