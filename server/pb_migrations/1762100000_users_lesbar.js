/// <reference path="../pb_data/types.d.ts" />
// Alle angemeldeten Familienmitglieder dürfen die Namen der anderen sehen
// (sonst zeigt die App bei fremden Fahrten nur "Fahrer").
// E-Mail-Adressen bleiben verborgen, solange emailVisibility aus ist.

migrate((app) => {
  const users = app.findCollectionByNameOrId("users");
  users.listRule = '@request.auth.id != ""';
  users.viewRule = '@request.auth.id != ""';
  app.save(users);
}, (app) => {
  const users = app.findCollectionByNameOrId("users");
  users.listRule = "id = @request.auth.id";
  users.viewRule = "id = @request.auth.id";
  app.save(users);
});
