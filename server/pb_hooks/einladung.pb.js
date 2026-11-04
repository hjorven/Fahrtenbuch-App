/// <reference path="../pb_data/types.d.ts" />
// Einladen und Passwort zurücksetzen per Code (Roadmap Nr. 1/2, Nachtplan 5c).
// Codes: 12 Zeichen ohne verwechselbare (0/O, 1/I), einmal benutzbar, Collection "einladung".
//
//   POST /api/fahrtenbuch/einladung      (angemeldet)   -> {code, gueltig_bis}         24 h
//   POST /api/fahrtenbuch/reset          (Superuser)    {email} -> {code, gueltig_bis} 15 min
//   POST /api/fahrtenbuch/beitreten      {code, name, email, passwort} -> Auth wie Login
//   POST /api/fahrtenbuch/zuruecksetzen  {code, passwort}              -> Auth wie Login

routerAdd("POST", "/api/fahrtenbuch/einladung", (e) => {
  const h = require(`${__hooks}/einladung.js`);
  return e.json(200, h.fbCodeAnlegen("einladung", e.auth.id, "", 24 * 3600 * 1000));
}, $apis.requireAuth());

routerAdd("POST", "/api/fahrtenbuch/reset", (e) => {
  const h = require(`${__hooks}/einladung.js`);
  const body = e.requestInfo().body || {};
  let u;
  try {
    u = $app.findAuthRecordByEmail("users", String(body.email || ""));
  } catch (err) {
    throw new BadRequestError("Kein Konto mit dieser E-Mail.");
  }
  return e.json(200, h.fbCodeAnlegen("reset", "", u.id, 15 * 60 * 1000));
}, $apis.requireSuperuserAuth());

routerAdd("POST", "/api/fahrtenbuch/beitreten", (e) => {
  const h = require(`${__hooks}/einladung.js`);
  const body = e.requestInfo().body || {};
  let neu;
  $app.runInTransaction((tx) => {
    const code = h.fbCodePruefen(tx, body.code, "einladung");
    const users = tx.findCollectionByNameOrId("users");
    neu = new Record(users);
    neu.setEmail(String(body.email || "").trim());
    neu.setPassword(String(body.passwort || ""));
    neu.set("name", String(body.name || "").trim());
    neu.setVerified(true);
    neu.set("passwort_aendern", false);
    tx.save(neu);  // Prüft E-Mail (eindeutig) und Passwortlänge, sonst 400
    code.set("benutzt", true);
    tx.save(code);
  });
  return $apis.recordAuthResponse(e, neu, "password");
});

routerAdd("POST", "/api/fahrtenbuch/zuruecksetzen", (e) => {
  const h = require(`${__hooks}/einladung.js`);
  const body = e.requestInfo().body || {};
  let u;
  $app.runInTransaction((tx) => {
    const code = h.fbCodePruefen(tx, body.code, "reset");
    u = tx.findRecordById("users", code.getString("fuer"));
    u.setPassword(String(body.passwort || ""));
    u.set("passwort_aendern", false);
    tx.save(u);
    code.set("benutzt", true);
    tx.save(code);
  });
  return $apis.recordAuthResponse(e, u, "password");
});
