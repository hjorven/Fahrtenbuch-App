/// <reference path="../pb_data/types.d.ts" />
// E-Auto: Das E-Auto wird geladen statt getankt.
// - fahrzeug.kraftstoff gibt die Antriebsart pro Auto an (benzin, diesel, elektro, sonstiges).
// - tankvorgang bekommt "ladeort" (zuhause/oeffentlich). Bei Elektro-Autos steht
//   "liter" für kWh und "preis_pro_liter" für den Preis pro kWh.

migrate((app) => {
  const tank = app.findCollectionByNameOrId("tankvorgang");
  tank.fields.add(new SelectField({
    name: "ladeort",
    maxSelect: 1,
    values: ["zuhause", "oeffentlich"],
  }));
  app.save(tank);

  const antrieb = {
    "e-auto": "elektro",
    "kombi": "diesel",
    "bus": "diesel",
  };
  for (const r of app.findAllRecords("fahrzeug")) {
    const name = (r.getString("name") || "").trim().toLowerCase();
    let geaendert = false;
    if (!r.getString("kraftstoff") && antrieb[name]) {
      r.set("kraftstoff", antrieb[name]);
      geaendert = true;
    }
    if (geaendert) app.save(r);
  }
}, (app) => {
  const tank = app.findCollectionByNameOrId("tankvorgang");
  tank.fields.removeByName("ladeort");
  app.save(tank);
});
