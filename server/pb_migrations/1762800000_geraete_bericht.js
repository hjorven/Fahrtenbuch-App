/// <reference path="../pb_data/types.d.ts" />
// Roadmap Nr. 15 und 21.
//
// users.app_version, users.geraet, users.letzter_sync: Die Apps tragen bei jedem Abgleich
// ihre Version, das Gerät und die Uhrzeit ein (eigener Datensatz, wie beim Namen). Daraus
// entsteht die Geräte-Übersicht ("Anna hat noch 1.0.31, letzter Sync vor 3 Tagen").
//
// bericht: "Problem melden" aus der App mit Text und Diagnose. Anlegen darf jeder für sich,
// lesen nur der Admin (Admin-Oberfläche), weil Diagnosen Orte und Zeiten enthalten können.

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.fields.add(new TextField({ name: "app_version", max: 40 }));
  users.fields.add(new TextField({ name: "geraet", max: 80 }));
  users.fields.add(new DateField({ name: "letzter_sync" }));
  app.save(users);

  const bericht = new Collection({
    type: "base",
    name: "bericht",
    listRule: null,
    viewRule: null,
    createRule: '@request.auth.id != "" && @request.body.von = @request.auth.id',
    updateRule: null,
    deleteRule: null,
    fields: [
      { name: "von", type: "relation", required: true, collectionId: users.id, maxSelect: 1, cascadeDelete: true },
      { name: "text", type: "text", max: 4000 },
      { name: "diagnose", type: "json", maxSize: 200000 },
      { name: "app_version", type: "text", max: 40 },
      { name: "geraet", type: "text", max: 80 },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
    ],
  });
  app.save(bericht);
}, (app) => {
  app.delete(app.findCollectionByNameOrId("bericht"));
  const users = app.findCollectionByNameOrId("users");
  for (const n of ["app_version", "geraet", "letzter_sync"]) users.fields.removeByName(n);
  app.save(users);
});
