// Regeln für pb_hooks/reservierung.pb.js (keine Hook-Datei, per require() geladen).
// Ende nach Beginn, höchstens 14 Tage, keine Überschneidung mit einer anderen (nicht
// gelöschten) Reservierung desselben Autos.

module.exports = {
  pruefen(app, r) {
    if (r.getBool("geloescht")) return;
    const von = r.getString("von");
    const bis = r.getString("bis");
    if (!(bis > von)) throw new BadRequestError("Ende muss nach dem Beginn liegen.");
    if (new Date(bis.replace(" ", "T")) - new Date(von.replace(" ", "T")) > 14 * 24 * 3600 * 1000) {
      throw new BadRequestError("Höchstens 14 Tage am Stück.");
    }
    const andere = app.findRecordsByFilter(
      "reservierung",
      "fahrzeug = {:f} && geloescht = false && id != {:id} && von < {:bis} && bis > {:von}",
      "von", 1, 0,
      { f: r.getString("fahrzeug"), id: r.id, von: von, bis: bis },
    );
    if (andere.length > 0) throw new BadRequestError("Das Auto ist in der Zeit schon reserviert.");
  },
};
