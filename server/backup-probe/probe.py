#!/usr/bin/env python3
"""Monatliche Backup-Probe (Roadmap Nr. 19).

Öffnet das neueste PocketBase-Backup (ZIP mit data.db), prüft die Datenbank darin
(PRAGMA integrity_check), zählt Autos, Fahrten, Tankungen und Benutzer und vergleicht
mit der laufenden Datenbank. Ergebnis als JSON in ERGEBNIS (für die Bridge/Startseite).
Nichts wird zurückgespielt oder verändert: Die Probe arbeitet in einem Temp-Ordner.

Nur Python-Standardbibliothek. Aufruf: python3 probe.py [--ausgabe datei]
"""
import glob
import json
import os
import sqlite3
import sys
import tempfile
import time
import zipfile

PB_DATA = os.environ.get("FB_PB_DATA", "/home/admin/Fahrtenbuch-App/server/pb_data")
ERGEBNIS = os.environ.get("FB_PROBE_ERGEBNIS", "/var/lib/fahrtenbuch-backup-probe/ergebnis.json")
TABELLEN = ("fahrzeug", "fahrt", "tankvorgang", "users")
MAX_ALTER_H = 48


def zaehlen(db_pfad):
    con = sqlite3.connect(f"file:{db_pfad}?mode=ro", uri=True)
    try:
        ok = con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        anzahl = {}
        for t in TABELLEN:
            try:
                anzahl[t] = con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            except sqlite3.Error:
                anzahl[t] = None
        return ok, anzahl
    finally:
        con.close()


def probe(pb_data=PB_DATA, jetzt=None):
    jetzt = jetzt or time.time()
    backups = sorted(glob.glob(os.path.join(pb_data, "backups", "*.zip")), key=os.path.getmtime)
    if not backups:
        return {"ok": False, "text": "Kein Backup gefunden", "zeit": int(jetzt)}
    neuestes = backups[-1]
    alter_h = int((jetzt - os.path.getmtime(neuestes)) // 3600)
    with tempfile.TemporaryDirectory() as tmp:
        try:
            with zipfile.ZipFile(neuestes) as z:
                if "data.db" not in z.namelist():
                    return {"ok": False, "text": "Backup ohne data.db", "datei": os.path.basename(neuestes), "zeit": int(jetzt)}
                z.extract("data.db", tmp)
        except zipfile.BadZipFile:
            return {"ok": False, "text": "Backup-Datei kaputt", "datei": os.path.basename(neuestes), "zeit": int(jetzt)}
        heil, im_backup = zaehlen(os.path.join(tmp, "data.db"))
    live = {}
    live_db = os.path.join(pb_data, "data.db")
    if os.path.exists(live_db):
        _, live = zaehlen(live_db)
    # Im Backup dürfen höchstens die Einträge seit dem Backup fehlen, nicht mehr als im Betrieb.
    fehlt = [t for t in TABELLEN if live.get(t) is not None and im_backup.get(t) is not None and im_backup[t] > live[t]]
    ok = heil and not fehlt and alter_h <= MAX_ALTER_H and all(v is not None for v in im_backup.values())
    if not heil:
        text = "Backup-Datenbank beschädigt"
    elif alter_h > MAX_ALTER_H:
        text = f"Neuestes Backup ist {alter_h} h alt"
    elif fehlt:
        text = "Backup passt nicht zur Datenbank: " + ", ".join(fehlt)
    else:
        text = "Backup lesbar und vollständig"
    return {"ok": ok, "text": text, "datei": os.path.basename(neuestes), "alter_h": alter_h,
            "im_backup": im_backup, "live": live, "zeit": int(jetzt)}


def main(argv):
    ziel = argv[argv.index("--ausgabe") + 1] if "--ausgabe" in argv else ERGEBNIS
    e = probe()
    print(json.dumps(e, ensure_ascii=False))
    os.makedirs(os.path.dirname(ziel), exist_ok=True)
    tmp = ziel + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(e, f, ensure_ascii=False)
    os.replace(tmp, ziel)
    return 0 if e["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
