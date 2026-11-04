/// <reference path="../pb_data/types.d.ts" />
// Roadmap Nr. 6: Mitfahrer und vorläufige Fahrten.
//
// fahrt.mitfahrer: wer mitgefahren ist (Kosten teilen, km zählen weiter nur beim Fahrer).
//   Eintragen bzw. austragen kann sich jeder selbst über POST /api/fahrtenbuch/mitfahren
//   (pb_hooks/mitfahren.pb.js); den Rest der Fahrt ändert weiter nur der Fahrer.
// fahrt.vorlaeufig: automatisch gestartet (Bluetooth/Bewegung), noch nicht bestätigt.
//   Zählt nirgends, bis der Fahrer bestätigt; am Display "unbestätigt".

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  const fahrt = app.findCollectionByNameOrId("fahrt");
  fahrt.fields.add(new RelationField({ name: "mitfahrer", collectionId: users.id, maxSelect: 10, cascadeDelete: false }));
  fahrt.fields.add(new BoolField({ name: "vorlaeufig" }));
  app.save(fahrt);
}, (app) => {
  const fahrt = app.findCollectionByNameOrId("fahrt");
  fahrt.fields.removeByName("mitfahrer");
  fahrt.fields.removeByName("vorlaeufig");
  app.save(fahrt);
});
