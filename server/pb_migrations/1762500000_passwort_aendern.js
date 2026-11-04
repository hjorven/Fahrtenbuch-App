/// <reference path="../pb_data/types.d.ts" />
// users.passwort_aendern: Konto hat noch das gemeinsame Start-Passwort.
// Die Apps bitten dann per Hinweis, ein eigenes festzulegen. Sobald ein Benutzer sein
// Passwort ändert, setzt der Hook passwort.pb.js das Feld zurück.
//
// Alle bestehenden Konten bekommen true, weil sie mit dem Start-Passwort angelegt wurden.
// Neue Konten: in der Admin-Oberfläche den Haken "passwort_aendern" setzen.

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.fields.add(new BoolField({ name: "passwort_aendern" }));
  app.save(users);

  for (const u of app.findAllRecords("users")) {
    u.set("passwort_aendern", true);
    app.saveNoValidate(u);
  }
}, (app) => {
  const users = app.findCollectionByNameOrId("users");
  users.fields.removeByName("passwort_aendern");
  app.save(users);
});
