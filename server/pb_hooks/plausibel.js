// Regeln für pb_hooks/plausibel.pb.js (keine Hook-Datei, wird per require() geladen).
// Grenzen: dieselben Werte wie in den Apps (Android Plausibel.kt, iOS Logik.swift).

const MAX_MENGE = 200;        // Liter bzw. kWh pro Tank-/Ladevorgang
const MAX_PREIS_EINHEIT = 5;  // Euro pro Liter bzw. kWh
const MAX_KMH = 250;          // darüber ist es ein GPS-Ausreißer
const MAX_KM_STAND = 2000000;
const MAX_KOSTEN = 50000;     // Euro pro Posten (sonstige Kosten)

function fehler(text) {
  throw new BadRequestError(text);
}

// Datum im PocketBase-Format ("2026-11-03 21:00:00.000Z") mehr als eine Stunde in der Zukunft?
// Eine Stunde Spielraum für Handys, deren Uhr vorgeht; die Apps prüfen strenger (10 Minuten).
function inZukunft(wert) {
  if (!wert) return false;
  const grenze = new Date(Date.now() + 3600 * 1000).toISOString().replace("T", " ");
  return String(wert).replace("T", " ") > grenze;
}

function runde2(x) {
  return Math.round(x * 100) / 100;
}

module.exports = {
  tankvorgang(r) {
    const menge = r.getFloat("liter");
    if (r.getBool("geloescht")) return;
    if (!(menge > 0) || menge > MAX_MENGE) fehler("Menge muss zwischen 0 und " + MAX_MENGE + " liegen.");
    if (r.getFloat("km_stand") > MAX_KM_STAND) fehler("km-Stand ist unplausibel hoch.");
    if (inZukunft(r.getString("datum"))) fehler("Datum liegt in der Zukunft.");
    if (r.getString("ladeort") === "zuhause") {
      r.set("preis_pro_liter", 0);
      r.set("gesamtpreis", 0);
      return;
    }
    const ppl = r.getFloat("preis_pro_liter");
    if (ppl > MAX_PREIS_EINHEIT) fehler("Preis pro Einheit über " + MAX_PREIS_EINHEIT + " Euro.");
    if (r.getFloat("gesamtpreis") <= 0 && ppl > 0) r.set("gesamtpreis", runde2(menge * ppl));
  },

  fahrt(r) {
    const start = r.getString("start");
    const ende = r.getString("ende");
    if (ende && start && ende < start) fehler("Fahrtende liegt vor dem Start.");
    if (!r.getBool("geloescht") && (inZukunft(start) || inZukunft(ende))) fehler("Fahrt liegt in der Zukunft.");
    const kmStart = r.getFloat("km_start");
    const kmEnde = r.getFloat("km_ende");
    if (kmEnde > 0 && kmEnde < kmStart) fehler("km am Ende kleiner als am Start.");
    if (kmEnde > MAX_KM_STAND) fehler("km-Stand ist unplausibel hoch.");
    if (r.getFloat("max_kmh") > MAX_KMH) r.set("max_kmh", 0);
    if (r.getFloat("schnitt_kmh") > MAX_KMH) r.set("schnitt_kmh", 0);
  },

  kosten(r) {
    if (r.getBool("geloescht")) return;
    const b = r.getFloat("betrag");
    if (!(b > 0) || b > MAX_KOSTEN) fehler("Betrag muss zwischen 0 und " + MAX_KOSTEN + " Euro liegen.");
    if (inZukunft(r.getString("datum"))) fehler("Datum liegt in der Zukunft.");
    r.set("betrag", runde2(b));
  },

  fahrzeug(r) {
    if (r.getFloat("km_stand") > MAX_KM_STAND) fehler("km-Stand ist unplausibel hoch.");
  },

  ausgleich(r) {
    if (r.getBool("geloescht")) return;
    if (!(r.getFloat("betrag") > 0)) fehler("Betrag muss größer als 0 sein.");
    if (r.getString("von") === r.getString("an")) fehler("Ausgleich an sich selbst geht nicht.");
  },
};
