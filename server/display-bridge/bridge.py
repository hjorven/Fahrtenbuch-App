#!/usr/bin/env python3
"""display-bridge: liest das Fahrtenbuch aus PocketBase und schickt es ans ESP32-Display.

Läuft als systemd-Dienst direkt auf dem Raspberry Pi (nicht im Docker-Container), weil
er den Pi neu starten und ausschalten können muss. Er liest die Datenbank nur und
schreibt nichts hinein (Ausnahme: Backup anlegen über die PocketBase-API).

Protokoll: JSON, eine Nachricht pro Zeile, 115200 Baud. Siehe docs/RASPBERRY-PI.md 2.5.

Start ohne Display zum Testen:
    python3 bridge.py --trocken          # schreibt alle Nachrichten ins Log
    python3 bridge.py --trocken --einmal # nur einmal, dann Ende
"""

import argparse
import json
import logging
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone

import karte
import wetter

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None

LOG = logging.getLogger("display-bridge")

FW_PROTOKOLL = 1
ABFRAGE_SEKUNDEN = 30
CONTAINER = "fahrtenbuch"
DOCKER = ["sudo", "-n", "/usr/bin/docker"]  # nur restart/inspect dieses Containers, siehe sudoers
WIEDERVERSUCH_SEKUNDEN = 10
MAX_FAHRTEN = 20
MAX_REKORDE = 5
BACKUP_WARNUNG_STUNDEN = 48
LAUFEND_MAX_STUNDEN = 12
KARTE_PAUSE_S = 0.15
KARTE_MAX_OHNE_OK = 3


# ---------------------------------------------------------------------------
# Hilfen
# ---------------------------------------------------------------------------

def zeitzone():
    name = os.environ.get("TZ_NAME", "Europe/Berlin")
    if ZoneInfo is not None:
        try:
            return ZoneInfo(name)
        except Exception:  # noqa: BLE001
            pass
    return timezone(timedelta(hours=1))


def parse_datum(wert, tz):
    """PocketBase-Datum ("2026-11-02 13:50:00.000Z") in lokale Zeit umwandeln."""
    if not wert:
        return None
    s = str(wert).strip().replace(" ", "T")
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        d = datetime.fromisoformat(s)
    except ValueError:
        return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=timezone.utc)
    return d.astimezone(tz)


def zahl(wert):
    try:
        return float(wert or 0)
    except (TypeError, ValueError):
        return 0.0


def runde(wert, stellen=1):
    return round(float(wert), stellen)


def km_der_fahrt(f):
    """km einer Fahrt: km_ende - km_start, falls leer oder unplausibel strecke_km."""
    start, ende = zahl(f.get("km_start")), zahl(f.get("km_ende"))
    if start > 0 and ende >= start:
        return ende - start
    return zahl(f.get("strecke_km"))


def preis_des_tankens(t):
    # Laden zuhause kostet nichts (Wunsch der Familie, der Server setzt den Preis auch auf 0).
    if t.get("ladeort") == "zuhause":
        return 0.0
    gesamt = zahl(t.get("gesamtpreis"))
    if gesamt > 0:
        return gesamt
    return zahl(t.get("liter")) * zahl(t.get("preis_pro_liter"))


def aktiv(r):
    """Zählt mit: nicht gelöscht und nicht aus dem Test-Modus der Apps."""
    return not r.get("geloescht") and not r.get("test")


# ---------------------------------------------------------------------------
# Berechnungen (reine Funktionen, getestet in test_bridge.py)
# ---------------------------------------------------------------------------

class Daten:
    def __init__(self, fahrzeuge, fahrten, tankvorgaenge, kosten, benutzer, jetzt, termine=None, reservierungen=None):
        self.tz = jetzt.tzinfo
        self.jetzt = jetzt
        self.fahrzeuge = [f for f in fahrzeuge if f.get("aktiv", True) is not False]
        self.alle_fahrzeuge = {f["id"]: f for f in fahrzeuge}
        # Vorläufige Fahrten (Roadmap Nr. 6) zählen nirgends, erscheinen aber laufend als "unbestätigt".
        self.vorlaeufig = [f for f in fahrten if aktiv(f) and f.get("vorlaeufig")]
        for f in self.vorlaeufig:
            f["_start"] = parse_datum(f.get("start"), self.tz)
            f["_ende"] = parse_datum(f.get("ende"), self.tz)
        self.fahrten = [f for f in fahrten if aktiv(f) and not f.get("vorlaeufig")]
        for f in self.fahrten:
            f["_start"] = parse_datum(f.get("start"), self.tz)
            f["_ende"] = parse_datum(f.get("ende"), self.tz)
            f["_km"] = km_der_fahrt(f)
        self.fahrten = [f for f in self.fahrten if f["_start"] is not None]
        self.tank = [t for t in tankvorgaenge if aktiv(t)]
        for t in self.tank:
            t["_datum"] = parse_datum(t.get("datum"), self.tz)
        self.tank = [t for t in self.tank if t["_datum"] is not None]
        self.kosten = [k for k in kosten if aktiv(k)]
        for k in self.kosten:
            k["_datum"] = parse_datum(k.get("datum"), self.tz)
        self.kosten = [k for k in self.kosten if k["_datum"] is not None]
        self.benutzer = {b["id"]: (b.get("name") or b.get("username") or "?") for b in benutzer}
        self.termine = [t for t in (termine or []) if not t.get("geloescht") and not t.get("erledigt")]
        for t in self.termine:
            t["_faellig"] = parse_datum(t.get("faellig"), self.tz)
        self.termine = [t for t in self.termine if t["_faellig"] is not None]
        self.reservierungen = []
        for r in reservierungen or []:
            if r.get("geloescht"):
                continue
            r["_von"], r["_bis"] = parse_datum(r.get("von"), self.tz), parse_datum(r.get("bis"), self.tz)
            if r["_von"] and r["_bis"] and r["_bis"] > jetzt:
                self.reservierungen.append(r)

    # Zeiträume -----------------------------------------------------------
    def im_monat(self, d):
        return d.year == self.jetzt.year and d.month == self.jetzt.month

    def im_jahr(self, d):
        return d.year == self.jetzt.year

    def name_fahrer(self, fid):
        return self.benutzer.get(fid, "?")

    def name_auto(self, aid):
        a = self.alle_fahrzeuge.get(aid)
        return a.get("name", "?") if a else "?"

    def ist_elektro(self, aid):
        a = self.alle_fahrzeuge.get(aid)
        return bool(a) and a.get("kraftstoff") == "elektro"

    def beendet(self):
        return [f for f in self.fahrten if f["_ende"] is not None]

    # Nachrichten ---------------------------------------------------------
    def autos(self):
        aus = []
        for a in sorted(self.fahrzeuge, key=lambda x: x.get("name", "")):
            eigene = [f for f in self.beendet() if f.get("fahrzeug") == a["id"]]
            aus.append({
                "id": a["id"],
                "name": a.get("name", "?"),
                "farbe": a.get("farbe") or "#888888",
                "km": int(round(zahl(a.get("km_stand")))),
                "km_monat": int(round(sum(f["_km"] for f in eigene if self.im_monat(f["_start"])))),
                "km_jahr": int(round(sum(f["_km"] for f in eigene if self.im_jahr(f["_start"])))),
                "elektro": a.get("kraftstoff") == "elektro",
            })
        return aus

    def laeuft(self):
        aus = []
        grenze = self.jetzt - timedelta(hours=LAUFEND_MAX_STUNDEN)
        laufend = [f for f in self.fahrten + self.vorlaeufig if f.get("_start") is not None]
        for f in sorted(laufend, key=lambda x: x["_start"]):
            # Ältere offene Fahrten sind hängen geblieben (Handy aus, App beendet), nicht "unterwegs".
            if f["_ende"] is None and f["_start"] >= grenze:
                eintrag = {
                    "fahrer": self.name_fahrer(f.get("fahrer")),
                    "auto": self.name_auto(f.get("fahrzeug")),
                    "seit": f["_start"].strftime("%H:%M"),
                }
                if f.get("vorlaeufig"):
                    eintrag["unbestaetigt"] = True
                aus.append(eintrag)
        return aus

    def _fahrt_kurz(self, f):
        dauer = 0
        if f["_ende"] is not None:
            dauer = int((f["_ende"] - f["_start"]).total_seconds() // 60)
        return {
            "id": f.get("id", ""),
            "datum": f["_start"].strftime("%d.%m."),
            "zeit": f["_start"].strftime("%H:%M"),
            "fahrer": self.name_fahrer(f.get("fahrer")),
            "auto": self.name_auto(f.get("fahrzeug")),
            "km": runde(f["_km"]),
            "min": max(dauer, 0),
            "von": (f.get("start_ort") or "")[:40],
            "nach": (f.get("ziel_ort") or "")[:40],
            "max": int(round(zahl(f.get("max_kmh")))),
            "schnitt": int(round(zahl(f.get("schnitt_kmh")))),
            "zweck": f.get("zweck") or "",
        }

    def letzte_fahrten(self):
        fahrten = sorted(self.beendet(), key=lambda x: x["_start"], reverse=True)[:MAX_FAHRTEN]
        return [self._fahrt_kurz(f) for f in fahrten]

    def rekorde(self, zeitraum="monat"):
        if zeitraum == "monat":
            auswahl = [f for f in self.beendet() if self.im_monat(f["_start"])]
        elif zeitraum == "jahr":
            auswahl = [f for f in self.beendet() if self.im_jahr(f["_start"])]
        else:
            zeitraum = "gesamt"
            auswahl = self.beendet()
        top = sorted(auswahl, key=lambda x: x["_km"], reverse=True)[:MAX_REKORDE]
        schnellste = max(auswahl, key=lambda x: zahl(x.get("max_kmh")), default=None)
        pro_fahrer = defaultdict(float)
        for f in auswahl:
            pro_fahrer[self.name_fahrer(f.get("fahrer"))] += f["_km"]
        bester = max(pro_fahrer.items(), key=lambda x: x[1], default=None)
        pro_auto = {}
        for f in auswahl:
            a = f.get("fahrzeug")
            if a not in pro_auto or f["_km"] > pro_auto[a]["_km"]:
                pro_auto[a] = f
        return {
            "t": "rekorde",
            "zeitraum": zeitraum,
            "d": [{"datum": f["_start"].strftime("%d.%m.%y"), "fahrer": self.name_fahrer(f.get("fahrer")),
                   "auto": self.name_auto(f.get("fahrzeug")), "km": runde(f["_km"])} for f in top],
            "pro_auto": [{"auto": self.name_auto(a), "km": runde(f["_km"]), "datum": f["_start"].strftime("%d.%m.%y")}
                         for a, f in sorted(pro_auto.items(), key=lambda x: self.name_auto(x[0]))],
            "schnellste": None if not schnellste or zahl(schnellste.get("max_kmh")) <= 0 else {
                "kmh": int(round(zahl(schnellste.get("max_kmh")))),
                "fahrer": self.name_fahrer(schnellste.get("fahrer")),
                "auto": self.name_auto(schnellste.get("fahrzeug")),
            },
            "meiste_km": None if not bester else {"fahrer": bester[0], "km": int(round(bester[1]))},
            "laengste_dauer": self._laengste_dauer(auswahl),
            "tanken": self._tank_rekorde(zeitraum, elektro=False),
            "laden": self._tank_rekorde(zeitraum, elektro=True),
            "fahrten": len(auswahl),
            "km": int(round(sum(f["_km"] for f in auswahl))),
        }

    def _laengste_dauer(self, auswahl):
        f = max(auswahl, key=lambda x: (x["_ende"] - x["_start"]).total_seconds(), default=None)
        if not f:
            return None
        return {"min": int((f["_ende"] - f["_start"]).total_seconds() // 60), "fahrer": self.name_fahrer(f.get("fahrer")),
                "auto": self.name_auto(f.get("fahrzeug")), "datum": f["_start"].strftime("%d.%m.%y")}

    def _tank_rekorde(self, zeitraum, elektro):
        """Größte Menge sowie günstigster und teuerster Preis pro Liter bzw. kWh im Zeitraum.

        Laden zuhause hat keinen Preis und zählt nur bei der Menge mit.
        """
        if zeitraum == "monat":
            liste = [t for t in self.tank if self.im_monat(t["_datum"])]
        elif zeitraum == "jahr":
            liste = [t for t in self.tank if self.im_jahr(t["_datum"])]
        else:
            liste = list(self.tank)
        liste = [t for t in liste if self.ist_elektro(t.get("fahrzeug")) == elektro and zahl(t.get("liter")) > 0]
        if not liste:
            return None

        def eintrag(t, **mehr):
            return {"auto": self.name_auto(t.get("fahrzeug")), "datum": t["_datum"].strftime("%d.%m.%y"), **mehr}

        def preis(t):
            p = zahl(t.get("preis_pro_liter"))
            return p if p > 0 else preis_des_tankens(t) / zahl(t.get("liter"))

        groesste = max(liste, key=lambda t: zahl(t.get("liter")))
        mit_preis = [t for t in liste if t.get("ladeort") != "zuhause" and preis(t) > 0]
        aus = {"einheit": "kWh" if elektro else "L",
               "groesste": eintrag(groesste, menge=runde(zahl(groesste.get("liter")), 1)),
               "billigste": None, "teuerste": None}
        if mit_preis:
            b = min(mit_preis, key=preis)
            t = max(mit_preis, key=preis)
            aus["billigste"] = eintrag(b, preis=runde(preis(b), 3))
            aus["teuerste"] = eintrag(t, preis=runde(preis(t), 3))
        return aus

    TERMIN_NAMEN = {"tuev": "TÜV", "inspektion": "Inspektion", "reifen": "Reifenwechsel",
                    "versicherung": "Versicherung", "sonstiges": "Termin"}

    def termine_nachricht(self, tage=30, anzahl=5):
        """Nächste offene Termine (Roadmap Nr. 16); "bald" = in höchstens [tage] Tagen oder überfällig."""
        aus = []
        for t in sorted(self.termine, key=lambda x: x["_faellig"])[:anzahl]:
            rest = (t["_faellig"].date() - self.jetzt.date()).days
            aus.append({"auto": self.name_auto(t.get("fahrzeug")), "art": self.TERMIN_NAMEN.get(t.get("art"), "Termin"),
                        "datum": t["_faellig"].strftime("%d.%m.%y"), "tage": rest, "bald": rest <= tage})
        return {"t": "termine", "d": aus}

    def reservierungen_nachricht(self, anzahl=5):
        """Laufende und kommende Reservierungen (Roadmap Nr. 17), "jetzt" = gerade reserviert."""
        aus = []
        for r in sorted(self.reservierungen, key=lambda x: x["_von"])[:anzahl]:
            gleicher_tag = r["_von"].date() == r["_bis"].date()
            aus.append({"auto": self.name_auto(r.get("fahrzeug")), "wer": self.name_fahrer(r.get("wer")),
                        "von": r["_von"].strftime("%d.%m. %H:%M"),
                        "bis": r["_bis"].strftime("%H:%M" if gleicher_tag else "%d.%m. %H:%M"),
                        "jetzt": r["_von"] <= self.jetzt < r["_bis"]})
        return {"t": "reservierungen", "d": aus}

    def letzte(self):
        """Letzte beendete Fahrt für die Kartenansicht (ohne Route, die holt die Bridge einzeln)."""
        f = max(self.beendet(), key=lambda x: x["_start"], default=None)
        if not f:
            return {"t": "letzte"}
        a = self.alle_fahrzeuge.get(f.get("fahrzeug")) or {}
        return {"t": "letzte", **self._fahrt_kurz(f), "farbe": a.get("farbe") or "#888888"}

    def verbrauch(self, aid):
        """Liter (bzw. kWh) pro 100 km aus vollen Tankungen.

        Zwischen zwei vollen Tankungen zählen alle getankten Mengen nach der ersten bis
        einschließlich der zweiten, geteilt durch die gefahrenen km dazwischen.
        E-Autos werden selten voll geladen: dort zählt jeder Ladevorgang als Messpunkt
        (wie in den Apps).
        """
        elektro = self.ist_elektro(aid)
        eigene = sorted((t for t in self.tank if t.get("fahrzeug") == aid),
                        key=lambda t: (zahl(t.get("km_stand")), t["_datum"]))
        menge = 0.0
        strecke = 0.0
        letzte_volle_km = None
        seit_voll = 0.0
        for t in eigene:
            if letzte_volle_km is not None:
                seit_voll += zahl(t.get("liter"))
            if elektro or t.get("vollgetankt"):
                km = zahl(t.get("km_stand"))
                if letzte_volle_km is not None and km > letzte_volle_km:
                    menge += seit_voll
                    strecke += km - letzte_volle_km
                letzte_volle_km = km
                seit_voll = 0.0
        if strecke <= 0:
            return None
        return runde(menge / strecke * 100, 1)

    def kosten_nachricht(self):
        aus = []
        km_pro_auto = {a["id"]: a for a in self.autos()}
        for a in sorted(self.fahrzeuge, key=lambda x: x.get("name", "")):
            aid = a["id"]
            tank = [t for t in self.tank if t.get("fahrzeug") == aid]
            sonst = [k for k in self.kosten if k.get("fahrzeug") == aid]
            sprit_monat = sum(preis_des_tankens(t) for t in tank if self.im_monat(t["_datum"]))
            sprit_jahr = sum(preis_des_tankens(t) for t in tank if self.im_jahr(t["_datum"]))
            sonst_monat = sum(zahl(k.get("betrag")) for k in sonst if self.im_monat(k["_datum"]))
            sonst_jahr = sum(zahl(k.get("betrag")) for k in sonst if self.im_jahr(k["_datum"]))
            km_jahr = km_pro_auto[aid]["km_jahr"]
            gesamt_jahr = sprit_jahr + sonst_jahr
            eintrag = {
                "auto": a.get("name", "?"),
                "elektro": a.get("kraftstoff") == "elektro",
                "einheit": "kWh" if a.get("kraftstoff") == "elektro" else "L",
                "menge_monat": runde(sum(zahl(t.get("liter")) for t in tank if self.im_monat(t["_datum"]))),
                "euro_monat": runde(sprit_monat, 2),
                "euro_jahr": runde(sprit_jahr, 2),
                "sonst_monat": runde(sonst_monat, 2),
                "sonst_jahr": runde(sonst_jahr, 2),
                "l100": self.verbrauch(aid),
                "euro_km": runde(gesamt_jahr / km_jahr, 2) if km_jahr > 0 else None,
            }
            if eintrag["elektro"]:
                monat = [t for t in tank if self.im_monat(t["_datum"])]
                eintrag["kwh_zuhause_monat"] = runde(sum(zahl(t.get("liter")) for t in monat
                                                         if t.get("ladeort") == "zuhause"))
                eintrag["kwh_oeffentlich_monat"] = runde(sum(zahl(t.get("liter")) for t in monat
                                                             if t.get("ladeort") != "zuhause"))
                jahr = [t for t in tank if self.im_jahr(t["_datum"])]
                kwh_jahr = sum(zahl(t.get("liter")) for t in jahr)
                kwh_zuhause = sum(zahl(t.get("liter")) for t in jahr if t.get("ladeort") == "zuhause")
                eintrag["anteil_zuhause"] = int(round(kwh_zuhause / kwh_jahr * 100)) if kwh_jahr > 0 else None
            aus.append(eintrag)
        return aus

    def details(self, aid):
        """Detailseite eines Autos: km pro Monat (12 Monate), km pro Fahrer im Jahr."""
        eigene = [f for f in self.beendet() if f.get("fahrzeug") == aid]
        monate = []
        j, m = self.jetzt.year, self.jetzt.month
        for _ in range(12):
            km = sum(f["_km"] for f in eigene if f["_start"].year == j and f["_start"].month == m)
            monate.append({"m": f"{m:02d}.{j % 100:02d}", "km": int(round(km))})
            m -= 1
            if m == 0:
                m, j = 12, j - 1
        monate.reverse()
        pro_fahrer = defaultdict(float)
        for f in eigene:
            if self.im_jahr(f["_start"]):
                pro_fahrer[self.name_fahrer(f.get("fahrer"))] += f["_km"]
        kosten = next((k for k in self.kosten_nachricht() if k["auto"] == self.name_auto(aid)), None)
        return {
            "t": "details",
            "auto": aid,
            "name": self.name_auto(aid),
            "monate": monate,
            "fahrer": [{"name": n, "km": int(round(k))} for n, k in sorted(pro_fahrer.items(), key=lambda x: -x[1])],
            "kosten": kosten,
            "fahrten_jahr": sum(1 for f in eigene if self.im_jahr(f["_start"])),
        }


# ---------------------------------------------------------------------------
# PocketBase
# ---------------------------------------------------------------------------

class PocketBase:
    def __init__(self, url, email, passwort):
        self.url = url.rstrip("/")
        self.email = email
        self.passwort = passwort
        self.token = None

    def _anfrage(self, methode, pfad, daten=None, mit_token=True, timeout=15):
        kopf = {"Content-Type": "application/json"}
        if mit_token and self.token:
            kopf["Authorization"] = self.token
        body = json.dumps(daten).encode() if daten is not None else None
        req = urllib.request.Request(self.url + pfad, data=body, headers=kopf, method=methode)
        with urllib.request.urlopen(req, timeout=timeout) as antwort:
            inhalt = antwort.read()
            return json.loads(inhalt) if inhalt else {}

    def anfrage(self, methode, pfad, daten=None, timeout=15):
        if not self.token:
            self.anmelden()
        try:
            return self._anfrage(methode, pfad, daten, timeout=timeout)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                self.anmelden()
                return self._anfrage(methode, pfad, daten, timeout=timeout)
            raise

    def anmelden(self):
        antwort = self._anfrage("POST", "/api/collections/_superusers/auth-with-password",
                                {"identity": self.email, "password": self.passwort}, mit_token=False)
        self.token = antwort["token"]

    def gesund(self):
        try:
            self._anfrage("GET", "/api/health", mit_token=False, timeout=5)
            return True
        except Exception:  # noqa: BLE001
            return False

    def alle(self, sammlung):
        seite, aus = 1, []
        while True:
            q = urllib.parse.urlencode({"page": seite, "perPage": 500, "skipTotal": 1, "fields": "*,route:excerpt(1)"})
            antwort = self.anfrage("GET", f"/api/collections/{sammlung}/records?{q}")
            teil = antwort.get("items", [])
            aus.extend(teil)
            if len(teil) < 500:
                return aus
            seite += 1

    def backups(self):
        return self.anfrage("GET", "/api/backups")

    def backup_anlegen(self, name):
        return self.anfrage("POST", "/api/backups", {"name": name}, timeout=120)


# ---------------------------------------------------------------------------
# Server-Infos und Befehle
# ---------------------------------------------------------------------------

def befehl(args, timeout=20):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
        return r.returncode, (r.stdout or r.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)


def cpu_temperatur():
    code, aus = befehl(["vcgencmd", "measure_temp"], timeout=5)
    if code == 0 and "=" in aus:
        try:
            return float(aus.split("=")[1].split("'")[0])
        except ValueError:
            pass
    try:
        with open("/sys/class/thermal/thermal_zone0/temp", encoding="ascii") as f:
            return round(int(f.read().strip()) / 1000, 1)
    except OSError:
        return None


def uptime_stunden():
    try:
        with open("/proc/uptime", encoding="ascii") as f:
            return int(float(f.read().split()[0]) // 3600)
    except OSError:
        return None


_VERSION = {"wert": None, "zeit": 0.0}
VERSION_NEU_VERSUCH_S = 600


def container_version(neu=False):
    """Image-Version von PocketBase.

    Ändert sich nur mit einem neuen Container, daher zwischengespeichert: früher lief
    `sudo docker inspect` alle 30 s und füllte das Journal (rund 8.600 sudo-Zeilen am Tag).
    Neu gelesen wird nach "PocketBase neu starten" (neu=True) und nach einem Fehlschlag
    höchstens alle 10 Minuten.
    """
    jetzt = time.monotonic()
    w = _VERSION["wert"]
    if not neu and w is not None and (w != "?" or jetzt - _VERSION["zeit"] < VERSION_NEU_VERSUCH_S):
        return w
    code, aus = befehl(DOCKER + ["inspect", "-f", "{{.Config.Image}}", CONTAINER], timeout=10)
    _VERSION["wert"] = aus.rsplit(":", 1)[1] if code == 0 and ":" in aus else "?"
    _VERSION["zeit"] = jetzt
    return _VERSION["wert"]


def kopie_alter_stunden(pfad, jetzt_ts=None):
    """Alter der neuesten Datei in der Backup-Kopie (z. B. USB-Stick) in Stunden.

    None = Ordner fehlt oder ist leer (Stick nicht eingesteckt).
    """
    if not pfad or not os.path.isdir(pfad):
        return None
    neueste = 0.0
    try:
        for name in os.listdir(pfad):
            try:
                neueste = max(neueste, os.path.getmtime(os.path.join(pfad, name)))
            except OSError:
                pass
    except OSError:
        return None
    if neueste <= 0:
        return None
    jetzt_ts = time.time() if jetzt_ts is None else jetzt_ts
    return max(0, int((jetzt_ts - neueste) // 3600))


def backup_warnung(alter_h, kopie_pfad, kopie_h):
    """Text für das Display, wenn mit den Backups etwas nicht stimmt, sonst ""."""
    if alter_h is None:
        return "Kein Backup gefunden"
    if alter_h > BACKUP_WARNUNG_STUNDEN:
        return f"Letztes Backup vor {alter_h // 24} Tagen"
    if kopie_pfad:
        if kopie_h is None:
            return "Backup-Kopie fehlt (USB-Stick?)"
        if kopie_h > BACKUP_WARNUNG_STUNDEN:
            return f"Backup-Kopie vor {kopie_h // 24} Tagen"
    return ""


def verbinden_text(info, app_url):
    """QR-Text zum Verbinden der Apps (Roadmap Nr. 1/2), gleiches Format wie in den Apps."""
    info = info or {}
    teile = [("heim", info.get("heimnetz") or app_url), ("tail", info.get("tailscale_ip") or info.get("tailscale")),
             ("dl", info.get("downloads"))]
    q = "&".join(f"{k}={urllib.parse.quote(v, safe='')}" for k, v in teile if v)
    return "fahrtenbuch://verbinden" + ("?" + q if q else "")


def server_info(pb, app_url, tz, kopie_pfad=""):
    info = {"t": "server", "ok": pb.gesund(), "version": container_version(), "url": app_url,
            "temp": cpu_temperatur(), "uptime_h": uptime_stunden(), "backup": "", "frei_gb": None,
            "backup_alter_h": None, "backup_warnung": ""}
    try:
        info["frei_gb"] = int(shutil.disk_usage("/").free // (1024 ** 3))
    except OSError:
        pass
    if not info["ok"]:
        return info
    try:
        liste = pb.backups() or []
        alter_h = None
        if liste:
            neuestes = max(liste, key=lambda b: b.get("modified", ""))
            d = parse_datum(neuestes.get("modified"), tz)
            if d:
                info["backup"] = d.strftime("%d.%m. %H:%M")
                alter_h = max(0, int((datetime.now(tz) - d).total_seconds() // 3600))
        info["backup_alter_h"] = alter_h
        info["backup_warnung"] = backup_warnung(alter_h, kopie_pfad, kopie_alter_stunden(kopie_pfad))
    except Exception as e:  # noqa: BLE001
        LOG.warning("Backups nicht lesbar: %s", e)
    return info


# ---------------------------------------------------------------------------
# Bridge
# ---------------------------------------------------------------------------

class Ausgabe:
    """Schreibt Zeilen an die serielle Schnittstelle oder (Trockenlauf) ins Log."""

    def __init__(self, port=None, trocken=False):
        self.trocken = trocken
        self.port = port
        self.ser = None
        self.puffer = b""
        self.naechster_versuch = 0.0
        self.fehler_gemeldet = False
        self.neu_geoeffnet = False  # True nach jedem (Wieder-)Öffnen, die Bridge schickt dann alles

    def offen(self):
        """Öffnet die Schnittstelle. Fehlt sie (Display nicht angesteckt), wird alle
        10 Sekunden still neu versucht, statt den Dienst zu beenden oder das Log zu fluten."""
        if self.trocken:
            return True
        if self.ser is not None:
            return True
        if time.monotonic() < self.naechster_versuch:
            return False
        try:
            import serial  # pyserial
            self.ser = serial.Serial(self.port, 115200, timeout=0.2)
            LOG.info("Schnittstelle %s offen", self.port)
            self.fehler_gemeldet = False
            self.neu_geoeffnet = True
            return True
        except Exception as e:  # noqa: BLE001
            if not self.fehler_gemeldet:
                LOG.warning("Schnittstelle %s nicht offen (%s), versuche es alle %d s weiter",
                            self.port, e, WIEDERVERSUCH_SEKUNDEN)
                self.fehler_gemeldet = True
            self.ser = None
            self.naechster_versuch = time.monotonic() + WIEDERVERSUCH_SEKUNDEN
            return False

    def senden(self, nachricht):
        zeile = json.dumps(nachricht, ensure_ascii=False, separators=(",", ":"))
        if self.trocken:
            if nachricht.get("t") == "karte":  # Bilddaten nicht ins Log
                zeile = f'{{"t":"karte","id":"{nachricht.get("id")}","teil":{nachricht.get("teil")},"von":{nachricht.get("von")}}}'
            LOG.info("-> %s", zeile)
            return
        if not self.offen():
            return
        try:
            self.ser.write(zeile.encode("utf-8") + b"\n")
        except Exception as e:  # noqa: BLE001
            LOG.warning("Senden fehlgeschlagen: %s", e)
            self.ser = None

    def lesen(self):
        """Gibt eine vollständige empfangene Zeile zurück oder None."""
        if self.trocken or not self.offen():
            time.sleep(0.2)
            return None
        try:
            self.puffer += self.ser.read(256)
        except Exception as e:  # noqa: BLE001
            LOG.warning("Lesen fehlgeschlagen: %s", e)
            self.ser = None
            return None
        if len(self.puffer) > 4096:
            self.puffer = b""
        if b"\n" in self.puffer:
            zeile, self.puffer = self.puffer.split(b"\n", 1)
            return zeile.decode("utf-8", "replace").strip()
        return None


class Bridge:
    def __init__(self, pb, ausgabe, app_url, pin="", tz=None, kopie_pfad="", drehung=None, wetter_ort=None):
        self.pb = pb
        self.drehung = drehung
        self.kopie_pfad = kopie_pfad
        self.aus = ausgabe
        self.app_url = app_url
        self.pin = pin or ""
        self.tz = tz or zeitzone()
        self.daten = None
        self.rekord_zeitraum = "monat"
        self.karten = karte.Kartenmacher()
        self.karte = None           # (fahrt_id, nachrichten, mit_strassen)
        self.karte_gesendet = None  # fahrt_id, deren Karte das Display vollständig hat
        self.karte_versuch = 0.0
        self.letzter_befehl = {}
        self.wetter = wetter.Wetter(wetter_ort)
        self.karte_sendungen = 0    # ohne Bestätigung höchstens KARTE_MAX_OHNE_OK-mal (alte Firmware)

    def laden(self):
        jetzt = datetime.now(self.tz)
        self.daten = Daten(
            self.pb.alle("fahrzeug"), self.pb.alle("fahrt"), self.pb.alle("tankvorgang"),
            self.pb.alle("kosten"), self.pb.alle("users"), jetzt, termine=self.optional_laden("termin"),
            reservierungen=self.optional_laden("reservierung"))

    def optional_laden(self, sammlung):
        # Ältere Server ohne diese Collection (termin, reservierung): einfach leer.
        try:
            return self.pb.alle(sammlung)
        except Exception:  # noqa: BLE001
            return []

    def alles_senden(self):
        self.aus.senden({"t": "zeit", "iso": datetime.now(self.tz).isoformat(timespec="seconds")})
        try:
            self.laden()
        except Exception as e:  # noqa: BLE001
            LOG.warning("PocketBase nicht lesbar: %s", e)
            self.aus.senden(server_info(self.pb, self.app_url, self.tz, self.kopie_pfad))
            return
        d = self.daten
        self.aus.senden({"t": "autos", "d": d.autos()})
        self.aus.senden({"t": "laeuft", "d": d.laeuft()})
        self.aus.senden({"t": "fahrten", "d": d.letzte_fahrten()})
        self.aus.senden(d.rekorde(self.rekord_zeitraum))
        self.aus.senden({"t": "kosten", "d": d.kosten_nachricht()})
        self.aus.senden(server_info(self.pb, self.app_url, self.tz, self.kopie_pfad))
        self.aus.senden(d.termine_nachricht())
        self.aus.senden(d.reservierungen_nachricht())
        w = self.wetter.nachricht()
        if w:
            self.aus.senden(w)
        self.qr_senden()
        letzte = d.letzte()
        self.aus.senden(letzte)
        self.karte_senden(letzte)

    def qr_senden(self):
        """QR zum Verbinden fürs Display ("Mehr > Handy verbinden"); Adressen vom Server-Hook."""
        try:
            info = self.pb.anfrage("GET", "/api/fahrtenbuch/info")
        except Exception:  # noqa: BLE001  (älterer Server ohne Hook, Testumgebung)
            info = {}
        self.aus.senden({"t": "qr", "text": verbinden_text(info, self.app_url)})

    def karte_bauen(self, letzte):
        """Karte der letzten Fahrt, neu nur bei neuer Fahrt oder wenn die Straßen noch fehlten."""
        fid = letzte.get("id")
        if not fid:
            return None
        if self.karte and self.karte[0] == fid and (self.karte[2] or time.monotonic() - self.karte_versuch
                                                     < karte.NEU_VERSUCH_S):
            return self.karte[1]
        self.karte_versuch = time.monotonic()
        try:
            r = self.pb.anfrage("GET", f"/api/collections/fahrt/records/{fid}?fields=id,route")
            nachrichten, voll = self.karten.nachrichten(fid, (r or {}).get("route", ""), letzte.get("farbe"))
        except Exception as e:  # noqa: BLE001
            LOG.warning("Karte nicht erstellt: %s", e)
            return None
        neu = self.karte is None or self.karte[0] != fid or self.karte[1] != nachrichten
        self.karte = (fid, nachrichten, voll)
        if neu:
            self.karte_gesendet = None
            self.karte_sendungen = 0
        return nachrichten

    def karte_senden(self, letzte, ab=0, immer=False):
        nachrichten = self.karte_bauen(letzte)
        if not nachrichten:
            return
        if not immer and (self.karte_gesendet == letzte.get("id") or self.karte_sendungen >= KARTE_MAX_OHNE_OK):
            return
        self.karte_sendungen += 1
        for n in nachrichten[ab:]:
            self.aus.senden(n)
            time.sleep(KARTE_PAUSE_S)  # Display zeichnet zwischendurch, nicht die Leitung fluten
        # Bestätigt das Display nicht, kommt die Karte beim nächsten Durchlauf noch einmal.

    def verarbeiten(self, zeile):
        try:
            n = json.loads(zeile)
        except json.JSONDecodeError:
            LOG.debug("Keine JSON-Zeile vom Display: %r", zeile)
            return
        typ = n.get("t")
        LOG.info("<- %s", zeile)
        if typ == "karte":
            # Display meldet "ok" (vollständig) oder "nochmal" ab einem fehlenden Teil.
            fid = n.get("id")
            if n.get("status") == "ok":
                self.karte_gesendet = fid
            elif n.get("status") == "nochmal" and self.karte and self.karte[0] == fid and self.daten:
                self.karte_senden(self.daten.letzte(), ab=max(0, int(n.get("teil", 0))), immer=True)
            return
        if typ == "hallo":
            self.karte_gesendet = None  # Display neu gestartet: Karte ist weg
            self.karte_sendungen = 0
            # DREHUNG aus der Konfiguration setzt die Bilddrehung, falls sie abweicht
            # (die Firmware merkt sich den Wert, Schalter im Server-Menü geht weiterhin).
            if self.drehung is not None and n.get("drehung") != self.drehung:
                self.aus.senden({"t": "einstellung", "drehung": self.drehung})
            self.alles_senden()
        elif typ == "details":
            # Vor dem ersten erfolgreichen Laden blieb die Anfrage sonst ohne Antwort.
            if self.daten is None:
                try:
                    self.laden()
                except Exception as e:  # noqa: BLE001
                    LOG.warning("details: PocketBase nicht lesbar: %s", e)
            if self.daten is not None:
                self.aus.senden(self.daten.details(n.get("auto", "")))
            else:
                self.aus.senden({"t": "fehler", "was": "details", "text": "Server nicht erreichbar"})
        elif typ == "rekorde" and self.daten:
            self.rekord_zeitraum = n.get("zeitraum", "monat")
            self.aus.senden(self.daten.rekorde(self.rekord_zeitraum))
        elif typ == "befehl":
            self.letzter_befehl = n
            self.befehl_ausfuehren(n.get("was", ""), str(n.get("pin", "")))

    def befehl_ausfuehren(self, was, pin):
        if self.pin and pin != self.pin:
            self.aus.senden({"t": "fehler", "was": was, "text": "PIN falsch"})
            return
        LOG.warning("Befehl vom Display: %s", was)
        if was == "pb_neustart":
            code, text = befehl(DOCKER + ["restart", CONTAINER], timeout=60)
        elif was == "backup":
            try:
                name = "display_" + datetime.now(self.tz).strftime("%Y%m%d_%H%M%S") + ".zip"
                self.pb.backup_anlegen(name)
                code, text = 0, name
            except Exception as e:  # noqa: BLE001
                code, text = 1, str(e)
        elif was == "konten" and not self.pin:
            code, text = 1, "Erst eine PIN in /etc/fahrtenbuch-display.env setzen"
        elif was == "konten":
            # Für "Passwort vergessen" am Display: Namen und E-Mails zur Auswahl (nur mit PIN).
            try:
                konten = [{"name": u.get("name") or u.get("email", "?"), "email": u.get("email", "")}
                          for u in self.pb.alle("users") if u.get("email")]
                self.aus.senden({"t": "konten", "d": sorted(konten, key=lambda k: k["name"].lower())})
                code, text = 0, ""
            except Exception as e:  # noqa: BLE001
                code, text = 1, str(e)
        elif was == "reset_code" and not self.pin:
            # Ohne PIN könnte jeder am Display Passwörter zurücksetzen.
            code, text = 1, "Erst eine PIN in /etc/fahrtenbuch-display.env setzen"
        elif was == "reset_code":
            # Passwort vergessen (Nachtplan 5c): Code für ein Konto, 15 min, Display zeigt den QR.
            try:
                r = self.pb.anfrage("POST", "/api/fahrtenbuch/reset", {"email": str(self.letzter_befehl.get("email", ""))})
                try:
                    info = self.pb.anfrage("GET", "/api/fahrtenbuch/info")
                except Exception:  # noqa: BLE001
                    info = {}
                text = verbinden_text(info, self.app_url) + "&reset=" + urllib.parse.quote(r.get("code", ""))
                self.aus.senden({"t": "reset_code", "code": r.get("code", ""), "gueltig_bis": r.get("gueltig_bis", ""),
                                 "text": text})
                code, text = 0, ""
            except Exception as e:  # noqa: BLE001
                code, text = 1, "Kein Konto mit dieser E-Mail" if "400" in str(e) else str(e)
        elif was == "neu_laden":
            self.alles_senden()
            code, text = 0, ""
        elif was == "reboot":
            self.aus.senden({"t": "ok", "was": was})
            code, text = befehl(["sudo", "-n", "/usr/bin/systemctl", "reboot"])
            if code == 0:
                return
        elif was == "poweroff":
            self.aus.senden({"t": "aus"})
            code, text = befehl(["sudo", "-n", "/usr/bin/systemctl", "poweroff"])
            if code == 0:
                return
        else:
            code, text = 1, "unbekannter Befehl"
        if code == 0:
            self.aus.senden({"t": "ok", "was": was})
            if was in ("pb_neustart", "backup"):
                time.sleep(3)
                if was == "pb_neustart":
                    container_version(neu=True)
                self.aus.senden(server_info(self.pb, self.app_url, self.tz, self.kopie_pfad))
        else:
            self.aus.senden({"t": "fehler", "was": was, "text": text[:120]})

    def laufen(self, einmal=False):
        letzte = 0.0
        while True:
            if time.monotonic() - letzte >= ABFRAGE_SEKUNDEN:
                self.alles_senden()
                letzte = time.monotonic()
                if einmal:
                    return
            if getattr(self.aus, "neu_geoeffnet", False):
                self.aus.neu_geoeffnet = False
                letzte = 0.0  # Schnittstelle (wieder) da: sofort alles schicken
                continue
            zeile = self.aus.lesen()
            if zeile:
                self.verarbeiten(zeile)


def lies_drehung(wert):
    """DREHUNG aus der Konfiguration: 0 oder 180, leer = Display entscheidet selbst."""
    wert = (wert or "").strip()
    if wert in ("0", "180"):
        return int(wert)
    if wert:
        LOG.warning("DREHUNG=%s ungültig, erlaubt sind 0 und 180", wert)
    return None


def main(argv=None):
    p = argparse.ArgumentParser(description="Fahrtenbuch display-bridge")
    p.add_argument("--trocken", action="store_true", help="ohne Display, Nachrichten ins Log")
    p.add_argument("--einmal", action="store_true", help="einmal senden, dann beenden")
    p.add_argument("-v", "--ausfuehrlich", action="store_true")
    a = p.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if a.ausfuehrlich else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    fehlt = [k for k in ("PB_EMAIL", "PB_PASSWORD") if not os.environ.get(k)]
    if fehlt:
        LOG.error("Fehlende Einstellungen: %s (siehe /etc/fahrtenbuch-display.env)", ", ".join(fehlt))
        return 2
    pb = PocketBase(os.environ.get("PB_URL", "http://localhost:8090"),
                    os.environ["PB_EMAIL"], os.environ["PB_PASSWORD"])
    ausgabe = Ausgabe(os.environ.get("SERIAL_PORT", "/dev/serial0"), trocken=a.trocken)
    bridge = Bridge(pb, ausgabe, os.environ.get("APP_URL", "http://fahrtenbuch.local:8090"),
                    os.environ.get("PIN", ""), kopie_pfad=os.environ.get("BACKUP_KOPIE", ""),
                    drehung=lies_drehung(os.environ.get("DREHUNG", "")),
                    wetter_ort=wetter.ort_lesen(os.environ.get("WETTER_ORT", "")))
    try:
        bridge.laufen(einmal=a.einmal)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
