/// <reference path="../pb_data/types.d.ts" />
// "Ich fahre mit" (Roadmap Nr. 6): sich selbst als Mitfahrer an einer Fahrt eintragen oder
// mit {"raus": true} austragen. Nur die eigene Person, nicht an eigenen Fahrten, keine
// gelöschten Fahrten. Body: {"fahrt": "<id>", "raus": false}
routerAdd("POST", "/api/fahrtenbuch/mitfahren", (e) => {
  const body = e.requestInfo().body || {};
  let f;
  try {
    f = $app.findRecordById("fahrt", String(body.fahrt || ""));
  } catch (err) {
    throw new NotFoundError("Fahrt nicht gefunden.");
  }
  if (f.getBool("geloescht")) throw new BadRequestError("Die Fahrt wurde gelöscht.");
  const ich = e.auth.id;
  if (f.getString("fahrer") === ich) throw new BadRequestError("Das ist deine eigene Fahrt.");
  const liste = f.getStringSlice("mitfahrer").filter((x) => x !== ich);
  if (!body.raus) liste.push(ich);
  f.set("mitfahrer", liste);
  $app.save(f);
  return e.json(200, { fahrt: f.id, mitfahrer: liste });
}, $apis.requireAuth());
