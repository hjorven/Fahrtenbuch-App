// Hilfen für pb_hooks/einladung.pb.js (keine Hook-Datei, wird per require() geladen,
// weil Hook-Handler in PocketBase keine Funktionen von außen sehen).

function fbJetzt(plusMs) {
  return new Date(Date.now() + (plusMs || 0)).toISOString().replace("T", " ");
}

function fbCodeAnlegen(typ, vonId, fuerId, dauerMs) {
  const col = $app.findCollectionByNameOrId("einladung");
  const r = new Record(col);
  const code = $security.randomStringWithAlphabet(12, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789");
  r.set("code", code);
  r.set("typ", typ);
  if (vonId) r.set("erstellt_von", vonId);
  if (fuerId) r.set("fuer", fuerId);
  r.set("gueltig_bis", fbJetzt(dauerMs));
  r.set("benutzt", false);
  $app.save(r);
  return { code: code, gueltig_bis: r.getString("gueltig_bis"), typ: typ };
}

// Gültigen, unbenutzten Code holen, sonst 400 mit verständlichem Text.
function fbCodePruefen(app, code, typ) {
  const c = String(code || "").trim().toUpperCase();
  if (c.length !== 12) throw new BadRequestError("Code ungültig.");
  let r;
  try {
    r = app.findFirstRecordByFilter("einladung", "code = {:c} && typ = {:t}", { c: c, t: typ });
  } catch (err) {
    throw new BadRequestError("Code ungültig.");
  }
  if (r.getBool("benutzt")) throw new BadRequestError("Code wurde schon benutzt.");
  if (r.getString("gueltig_bis").replace("T", " ") < fbJetzt(0)) throw new BadRequestError("Code ist abgelaufen.");
  return r;
}

module.exports = { fbJetzt, fbCodeAnlegen, fbCodePruefen };
