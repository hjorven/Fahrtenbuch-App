/// <reference path="../pb_data/types.d.ts" />
// Belegfoto am Tank- bzw. Ladevorgang.
//
// Die Apps fotografieren den Kassenbon, lesen Menge, Preis, Datum und Tankstelle auf dem
// Handy aus (ohne Cloud) und laden das Foto als Beleg hoch. Das Foto ist "protected":
// Abrufen geht nur angemeldet mit einem kurzlebigen Datei-Token (/api/files/token),
// nicht über eine offene Adresse. Die Apps verkleinern das Foto vorher auf etwa 2000 px,
// deshalb reichen 10 MB als Grenze. Hochladen darf wie beim Ändern nur der Fahrer selbst.

migrate((app) => {
  const tank = app.findCollectionByNameOrId("tankvorgang");
  tank.fields.add(new FileField({
    name: "beleg",
    maxSelect: 1,
    maxSize: 10 * 1024 * 1024,
    mimeTypes: ["image/jpeg", "image/png", "image/heic", "image/heif"],
    thumbs: ["400x0"],
    protected: true,
  }));
  app.save(tank);
}, (app) => {
  const tank = app.findCollectionByNameOrId("tankvorgang");
  tank.fields.removeByName("beleg");
  app.save(tank);
});
