import json
import unittest
from unittest import mock
from datetime import datetime, timezone

import bridge
import karte


def fahrt(id_, auto, fahrer, start, km, ende=True, **kw):
    f = {"id": id_, "fahrzeug": auto, "fahrer": fahrer, "start": start,
         "ende": start if ende else "", "strecke_km": km, "geloescht": False}
    f.update(kw)
    return f


class TestDaten(unittest.TestCase):
    def setUp(self):
        self.tz = timezone.utc
        self.jetzt = datetime(2026, 11, 15, 12, 0, tzinfo=self.tz)
        self.autos = [
            {"id": "bus", "name": "Bus", "farbe": "#EF6C00", "km_stand": 182340, "kraftstoff": "diesel", "aktiv": True},
            {"id": "leap", "name": "E-Auto", "farbe": "#1565C0", "km_stand": 8902, "kraftstoff": "elektro", "aktiv": True},
        ]
        self.benutzer = [{"id": "v", "name": "Ben"}, {"id": "j", "name": "Admin"}]
        self.fahrten = [
            fahrt("a", "bus", "v", "2026-11-02 10:00:00.000Z", 0, km_start=1000, km_ende=1312),
            fahrt("b", "bus", "j", "2026-10-20 10:00:00.000Z", 50, max_kmh=130),
            fahrt("c", "leap", "j", "2026-11-14 08:00:00.000Z", 23.4),
            fahrt("d", "leap", "v", "2025-12-30 08:00:00.000Z", 999),
            fahrt("e", "bus", "v", "2026-11-10 08:00:00.000Z", 80, geloescht=True),
            fahrt("f", "bus", "v", "2026-11-15 11:00:00.000Z", 0, ende=False),
        ]
        self.tank = [
            {"id": "1", "fahrzeug": "bus", "datum": "2026-10-01 10:00:00.000Z", "km_stand": 1000, "liter": 60, "gesamtpreis": 100, "vollgetankt": True},
            {"id": "2", "fahrzeug": "bus", "datum": "2026-11-01 10:00:00.000Z", "km_stand": 1500, "liter": 20, "preis_pro_liter": 1.5, "vollgetankt": False},
            {"id": "3", "fahrzeug": "bus", "datum": "2026-11-05 10:00:00.000Z", "km_stand": 2000, "liter": 70, "gesamtpreis": 120, "vollgetankt": True},
            {"id": "4", "fahrzeug": "leap", "datum": "2026-11-03 10:00:00.000Z", "km_stand": 8800, "liter": 20, "preis_pro_liter": 0.3, "vollgetankt": True, "ladeort": "zuhause"},
        ]
        self.kosten = [{"id": "k", "fahrzeug": "bus", "datum": "2026-03-01 10:00:00.000Z", "betrag": 300}]
        self.d = bridge.Daten(self.autos, self.fahrten, self.tank, self.kosten, self.benutzer, self.jetzt)

    def test_km_pro_auto(self):
        autos = {a["id"]: a for a in self.d.autos()}
        self.assertEqual(autos["bus"]["km_monat"], 312)        # km_ende - km_start, gelöschte zählt nicht
        self.assertEqual(autos["bus"]["km_jahr"], 362)
        self.assertEqual(autos["leap"]["km_monat"], 23)
        self.assertEqual(autos["leap"]["km_jahr"], 23)         # Fahrt aus 2025 zählt nicht
        self.assertTrue(autos["leap"]["elektro"])

    def test_laufende_fahrt(self):
        self.assertEqual(self.d.laeuft(), [{"fahrer": "Ben", "auto": "Bus", "seit": "11:00"}])

    def test_letzte_fahrten_ohne_laufende_und_geloeschte(self):
        ids = [f["id"] for f in self.d.letzte_fahrten()]
        self.assertEqual(ids, ["c", "a", "b", "d"])

    def test_rekorde(self):
        r = self.d.rekorde("jahr")
        self.assertEqual(r["d"][0]["km"], 312.0)
        self.assertEqual(r["schnellste"]["kmh"], 130)
        self.assertEqual(r["meiste_km"], {"fahrer": "Ben", "km": 312})
        self.assertEqual(self.d.rekorde("gesamt")["d"][0]["km"], 999.0)
        self.assertEqual(self.d.rekorde("quatsch")["zeitraum"], "gesamt")

    def test_verbrauch(self):
        # zwischen den vollen Tankungen 1000 -> 2000 km: 20 + 70 Liter
        self.assertEqual(self.d.verbrauch("bus"), 9.0)
        self.assertIsNone(self.d.verbrauch("leap"))           # nur eine volle Ladung

    def test_kosten(self):
        k = {x["auto"]: x for x in self.d.kosten_nachricht()}
        self.assertEqual(k["Bus"]["euro_monat"], 150.0)  # 20*1.5 + 120
        self.assertEqual(k["Bus"]["euro_jahr"], 250.0)
        self.assertEqual(k["Bus"]["sonst_jahr"], 300.0)
        self.assertEqual(k["Bus"]["euro_km"], round(550 / 362, 2))
        self.assertEqual(k["E-Auto"]["einheit"], "kWh")
        self.assertEqual(k["E-Auto"]["euro_monat"], 0.0)   # zuhause geladen = kostenlos
        self.assertEqual(k["E-Auto"]["kwh_zuhause_monat"], 20.0)
        self.assertEqual(k["E-Auto"]["kwh_oeffentlich_monat"], 0.0)
        self.assertEqual(k["E-Auto"]["anteil_zuhause"], 100)
        self.assertNotIn("kwh_zuhause_monat", k["Bus"])

    def test_elektro_oeffentlich_kostet_und_jede_ladung_zaehlt(self):
        self.tank.append({"id": "5", "fahrzeug": "leap", "datum": "2026-11-10 10:00:00.000Z", "km_stand": 8900,
                          "liter": 15, "preis_pro_liter": 0.6, "vollgetankt": False, "ladeort": "oeffentlich"})
        d = bridge.Daten(self.autos, self.fahrten, self.tank, self.kosten, self.benutzer, self.jetzt)
        k = {x["auto"]: x for x in d.kosten_nachricht()}
        self.assertEqual(k["E-Auto"]["euro_monat"], 9.0)
        self.assertEqual(k["E-Auto"]["anteil_zuhause"], 57)
        self.assertEqual(d.verbrauch("leap"), 15.0)           # 15 kWh auf 100 km, ohne "voll"

    def test_haengende_fahrt_nicht_unterwegs(self):
        self.fahrten.append(fahrt("alt", "leap", "j", "2026-11-13 08:00:00.000Z", 0, ende=False))
        d = bridge.Daten(self.autos, self.fahrten, self.tank, self.kosten, self.benutzer, self.jetzt)
        self.assertEqual([x["auto"] for x in d.laeuft()], ["Bus"])

    def test_details(self):
        det = self.d.details("bus")
        self.assertEqual(len(det["monate"]), 12)
        self.assertEqual(det["monate"][-1], {"m": "11.26", "km": 312})
        self.assertEqual(det["monate"][-2], {"m": "10.26", "km": 50})
        self.assertEqual(det["fahrer"][0], {"name": "Ben", "km": 312})

    def test_test_modus_zaehlt_nicht(self):
        fahrten = self.fahrten + [fahrt("t", "bus", "v", "2026-11-12 08:00:00.000Z", 900, test=True)]
        tank = self.tank + [{"id": "9", "fahrzeug": "bus", "datum": "2026-11-12 10:00:00.000Z", "km_stand": 3000,
                             "liter": 80, "gesamtpreis": 500, "vollgetankt": True, "test": True}]
        d = bridge.Daten(self.autos, fahrten, tank, self.kosten, self.benutzer, self.jetzt)
        autos = {a["id"]: a for a in d.autos()}
        self.assertEqual(autos["bus"]["km_monat"], 312)
        self.assertNotIn("t", [f["id"] for f in d.letzte_fahrten()])
        self.assertEqual(d.rekorde("jahr")["d"][0]["km"], 312.0)
        k = {x["auto"]: x for x in d.kosten_nachricht()}
        self.assertEqual(k["Bus"]["euro_monat"], 150.0)
        self.assertEqual(d.verbrauch("bus"), 9.0)

    def test_nachrichten_sind_json(self):
        for n in (self.d.autos(), self.d.letzte_fahrten(), self.d.rekorde(), self.d.kosten_nachricht(), self.d.details("leap")):
            json.dumps(n)


class TestMitternacht(unittest.TestCase):
    """Nachtplan 5b Fall 10: Fahrt über Mitternacht und Monatswechsel zählt einmal, im Startmonat."""

    def test_monatswechsel(self):
        tz = timezone.utc
        jetzt = datetime(2026, 11, 15, 12, 0, tzinfo=tz)
        f = fahrt("x", "g", "j", "2026-10-31 23:50:00.000Z", 30)
        f["ende"] = "2026-11-01 00:20:00.000Z"
        d = bridge.Daten([{"id": "g", "name": "Kombi", "aktiv": True}], [f], [], [], [], jetzt)
        a = d.autos()[0]
        self.assertEqual((a["km_monat"], a["km_jahr"]), (0, 30))
        self.assertEqual(d.rekorde("jahr")["fahrten"], 1)
        self.assertEqual(d.rekorde("monat")["fahrten"], 0)


class TestVorlaeufig(unittest.TestCase):
    def test_zaehlt_nicht_aber_unterwegs(self):
        tz = timezone.utc
        jetzt = datetime(2026, 11, 15, 12, 0, tzinfo=tz)
        fahrten = [dict(fahrt("v1", "g", "j", "2026-11-15 09:00:00.000Z", 40), vorlaeufig=True),
                   dict(fahrt("v2", "g", "j", "2026-11-15 11:30:00.000Z", 3, ende=False), vorlaeufig=True),
                   fahrt("n1", "g", "j", "2026-11-14 09:00:00.000Z", 10)]
        d = bridge.Daten([{"id": "g", "name": "Kombi", "aktiv": True}], fahrten, [], [], [{"id": "j", "name": "Admin"}], jetzt)
        self.assertEqual(d.autos()[0]["km_monat"], 10)
        self.assertEqual(d.rekorde("monat")["fahrten"], 1)
        self.assertEqual(d.laeuft(), [{"fahrer": "Admin", "auto": "Kombi", "seit": "11:30", "unbestaetigt": True}])
        self.assertEqual(d.letzte()["id"], "n1")


class TestTermine(unittest.TestCase):
    def test_naechste_und_bald(self):
        tz = timezone.utc
        jetzt = datetime(2026, 11, 15, 12, 0, tzinfo=tz)
        termine = [
            {"id": "a", "fahrzeug": "g", "art": "tuev", "faellig": "2026-12-01 00:00:00.000Z"},
            {"id": "b", "fahrzeug": "g", "art": "reifen", "faellig": "2027-04-01 00:00:00.000Z"},
            {"id": "c", "fahrzeug": "g", "art": "inspektion", "faellig": "2026-11-01 00:00:00.000Z", "erledigt": True},
            {"id": "d", "fahrzeug": "g", "art": "versicherung", "faellig": "2026-11-10 00:00:00.000Z"},
        ]
        d = bridge.Daten([{"id": "g", "name": "Kombi", "aktiv": True}], [], [], [], [], jetzt, termine=termine)
        n = d.termine_nachricht()["d"]
        self.assertEqual([x["art"] for x in n], ["Versicherung", "TÜV", "Reifenwechsel"])
        self.assertEqual([x["bald"] for x in n], [True, True, False])
        self.assertEqual(n[0]["tage"], -5)

    def test_reservierungen(self):
        tz = timezone.utc
        jetzt = datetime(2026, 11, 15, 12, 0, tzinfo=tz)
        res = [
            {"fahrzeug": "g", "wer": "j", "von": "2026-11-15 10:00:00.000Z", "bis": "2026-11-15 14:00:00.000Z"},
            {"fahrzeug": "g", "wer": "j", "von": "2026-11-20 08:00:00.000Z", "bis": "2026-11-22 18:00:00.000Z"},
            {"fahrzeug": "g", "wer": "j", "von": "2026-11-10 08:00:00.000Z", "bis": "2026-11-10 09:00:00.000Z"},
            {"fahrzeug": "g", "wer": "j", "von": "2026-11-16 08:00:00.000Z", "bis": "2026-11-16 09:00:00.000Z", "geloescht": True},
        ]
        d = bridge.Daten([{"id": "g", "name": "Kombi", "aktiv": True}], [], [], [], [{"id": "j", "name": "Admin"}], jetzt,
                         reservierungen=res)
        n = d.reservierungen_nachricht()["d"]
        self.assertEqual([(x["von"], x["bis"], x["jetzt"]) for x in n],
                         [("15.11. 10:00", "14:00", True), ("20.11. 08:00", "22.11. 18:00", False)])

    def test_ohne_collection(self):
        class PB(FalschesPB):
            def alle(self, sammlung):
                if sammlung == "termin":
                    raise OSError("404")
                return []
        aus = FalscheAusgabe()
        b = bridge.Bridge(PB(), aus, "http://x:8090", tz=timezone.utc)
        b.alles_senden()
        self.assertIn({"t": "termine", "d": []}, aus.gesendet)


class TestBackupWarnung(unittest.TestCase):
    def test_regeln(self):
        self.assertEqual(bridge.backup_warnung(None, "", None), "Kein Backup gefunden")
        self.assertEqual(bridge.backup_warnung(80, "", None), "Letztes Backup vor 3 Tagen")
        self.assertEqual(bridge.backup_warnung(10, "", None), "")
        self.assertEqual(bridge.backup_warnung(10, "/mnt/backup", None), "Backup-Kopie fehlt (USB-Stick?)")
        self.assertEqual(bridge.backup_warnung(10, "/mnt/backup", 100), "Backup-Kopie vor 4 Tagen")
        self.assertEqual(bridge.backup_warnung(10, "/mnt/backup", 5), "")

    def test_kopie_alter(self):
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as ordner:
            self.assertIsNone(bridge.kopie_alter_stunden(ordner))
            datei = os.path.join(ordner, "pb_backup.zip")
            open(datei, "w").close()
            jetzt = os.path.getmtime(datei)
            self.assertEqual(bridge.kopie_alter_stunden(ordner, jetzt + 3 * 3600 + 5), 3)
        self.assertIsNone(bridge.kopie_alter_stunden("/gibt/es/nicht"))
        self.assertIsNone(bridge.kopie_alter_stunden(""))


class FalschesPB:
    def __init__(self):
        self.backup = None

    def gesund(self):
        return True

    def backups(self):
        return []

    def backup_anlegen(self, name):
        self.backup = name

    def alle(self, sammlung):
        return []


class FalscheAusgabe:
    def __init__(self):
        self.gesendet = []

    def senden(self, n):
        self.gesendet.append(n)


class PBMitFahrt(FalschesPB):
    """Eine beendete Fahrt mit Route, eine Test-Fahrt (zählt nicht)."""
    ROUTE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"  # Beispiel aus der Polyline-Doku

    def alle(self, sammlung):
        if sammlung == "fahrzeug":
            return [{"id": "g", "name": "Kombi", "farbe": "#ff3b30", "aktiv": True, "kraftstoff": "benzin"}]
        if sammlung == "fahrt":
            return [fahrt("f1", "g", "j", "2026-11-03 20:00:00.000Z", 12.5),
                    dict(fahrt("t1", "g", "j", "2026-11-03 21:00:00.000Z", 3), test=True)]
        if sammlung == "users":
            return [{"id": "j", "name": "Admin"}]
        return []

    def anfrage(self, methode, pfad, daten=None, timeout=15):
        return {"id": "f1", "route": self.ROUTE}


class TestKarteInBridge(unittest.TestCase):
    def setUp(self):
        self.aus = FalscheAusgabe()
        self.b = bridge.Bridge(PBMitFahrt(), self.aus, "http://x:8090", tz=timezone.utc)
        self.b.karten = karte.Kartenmacher(cache="/nicht/da", holen=lambda q: {"elements": []})
        self.schlaf = mock.patch.object(bridge.time, "sleep", lambda s: None)
        self.schlaf.start()

    def tearDown(self):
        self.schlaf.stop()

    def karten(self):
        return [n for n in self.aus.gesendet if n["t"] == "karte"]

    def test_letzte_fahrt_ohne_test(self):
        self.b.alles_senden()
        letzte = next(n for n in self.aus.gesendet if n["t"] == "letzte")
        self.assertEqual((letzte["id"], letzte["farbe"], letzte["fahrer"]), ("f1", "#ff3b30", "Admin"))
        self.assertEqual(len(self.karten()), 16)
        self.assertEqual({n["id"] for n in self.karten()}, {"f1"})

    def test_nach_ok_nicht_nochmal(self):
        self.b.alles_senden()
        self.b.verarbeiten('{"t":"karte","id":"f1","status":"ok"}')
        self.aus.gesendet.clear()
        self.b.alles_senden()
        self.assertEqual(self.karten(), [])

    def test_ohne_ok_hoechstens_dreimal(self):
        for _ in range(5):
            self.b.alles_senden()
        self.assertEqual(len(self.karten()), 3 * 16)

    def test_nochmal_ab_teil(self):
        self.b.alles_senden()
        self.aus.gesendet.clear()
        self.b.verarbeiten('{"t":"karte","id":"f1","status":"nochmal","teil":10}')
        self.assertEqual([n["teil"] for n in self.karten()], list(range(10, 16)))

    def test_hallo_schickt_karte_neu(self):
        self.b.alles_senden()
        self.b.verarbeiten('{"t":"karte","id":"f1","status":"ok"}')
        self.aus.gesendet.clear()
        self.b.verarbeiten('{"t":"hallo","fw":20}')
        self.assertEqual(len(self.karten()), 16)

    def test_ohne_fahrten_keine_karte(self):
        b = bridge.Bridge(FalschesPB(), self.aus, "http://x:8090", tz=timezone.utc)
        b.alles_senden()
        self.assertIn({"t": "letzte"}, self.aus.gesendet)
        self.assertEqual(self.karten(), [])


class TestQr(unittest.TestCase):
    def test_text(self):
        t = bridge.verbinden_text({"heimnetz": "http://fahrtenbuch.local:8090", "tailscale_ip": "http://100.1.2.3:8090",
                                   "downloads": "http://fahrtenbuch.local:8091"}, "http://x:8090")
        self.assertEqual(t, "fahrtenbuch://verbinden?heim=http%3A%2F%2Ffahrtenbuch.local%3A8090"
                            "&tail=http%3A%2F%2F100.1.2.3%3A8090&dl=http%3A%2F%2Ffahrtenbuch.local%3A8091")
        self.assertEqual(bridge.verbinden_text({}, "http://x:8090"), "fahrtenbuch://verbinden?heim=http%3A%2F%2Fx%3A8090")

    def test_reset_code_mit_pin(self):
        class PB(FalschesPB):
            def anfrage(self, methode, pfad, daten=None, timeout=15):
                if pfad.endswith("/reset"):
                    self.reset = daten
                    return {"code": "ABCD2345EFGH", "gueltig_bis": "2026-11-04 02:00:00.000Z"}
                return {"heimnetz": "http://h:8090"}
        pb, aus = PB(), FalscheAusgabe()
        b = bridge.Bridge(pb, aus, "http://x:8090", pin="1234", tz=timezone.utc)
        b.verarbeiten('{"t":"befehl","was":"reset_code","pin":"1234","email":"anna@x.de"}')
        self.assertEqual(pb.reset, {"email": "anna@x.de"})
        r = next(n for n in aus.gesendet if n["t"] == "reset_code")
        self.assertTrue(r["text"].endswith("&reset=ABCD2345EFGH"))
        self.assertIn({"t": "ok", "was": "reset_code"}, aus.gesendet)
        aus.gesendet.clear()
        b.verarbeiten('{"t":"befehl","was":"reset_code","pin":"0000","email":"anna@x.de"}')
        self.assertEqual(aus.gesendet[-1]["t"], "fehler")

    def test_reset_code_ohne_pin_verboten(self):
        aus = FalscheAusgabe()
        b = bridge.Bridge(FalschesPB(), aus, "http://x:8090", pin="", tz=timezone.utc)
        b.verarbeiten('{"t":"befehl","was":"reset_code","pin":"","email":"anna@x.de"}')
        self.assertEqual(aus.gesendet[-1]["t"], "fehler")
        self.assertIn("PIN", aus.gesendet[-1]["text"])

    def test_konten_nur_mit_pin(self):
        class PB(FalschesPB):
            def alle(self, sammlung):
                return [{"id": "a", "name": "Anna", "email": "anna@x.de"}, {"id": "b", "name": "", "email": "b@x.de"}] \
                    if sammlung == "users" else []
        aus = FalscheAusgabe()
        b = bridge.Bridge(PB(), aus, "http://x:8090", pin="1234", tz=timezone.utc)
        b.verarbeiten('{"t":"befehl","was":"konten","pin":"1234"}')
        k = next(n for n in aus.gesendet if n["t"] == "konten")
        self.assertEqual([x["name"] for x in k["d"]], ["Anna", "b@x.de"])
        aus.gesendet.clear()
        bridge.Bridge(PB(), aus, "http://x:8090", pin="", tz=timezone.utc).verarbeiten('{"t":"befehl","was":"konten","pin":""}')
        self.assertEqual(aus.gesendet[-1]["t"], "fehler")

    def test_qr_in_alles(self):
        aus = FalscheAusgabe()
        bridge.Bridge(FalschesPB(), aus, "http://x:8090", tz=timezone.utc).alles_senden()
        self.assertTrue(any(n["t"] == "qr" for n in aus.gesendet))


class TestBridge(unittest.TestCase):
    def setUp(self):
        self.pb = FalschesPB()
        self.aus = FalscheAusgabe()
        self.b = bridge.Bridge(self.pb, self.aus, "http://x:8090", pin="1234", tz=timezone.utc)

    def test_hallo_schickt_alles(self):
        self.b.verarbeiten('{"t":"hallo","fw":1}')
        typen = [n["t"] for n in self.aus.gesendet]
        for t in ("zeit", "autos", "laeuft", "fahrten", "rekorde", "kosten", "server"):
            self.assertIn(t, typen)

    def test_falsche_pin(self):
        self.b.verarbeiten('{"t":"befehl","was":"poweroff","pin":"0000"}')
        self.assertEqual(self.aus.gesendet[-1]["t"], "fehler")

    def test_backup_mit_pin(self):
        with mock.patch.object(bridge.time, "sleep", lambda s: None):
            self.b.verarbeiten('{"t":"befehl","was":"backup","pin":"1234"}')
        self.assertTrue(self.pb.backup.startswith("display_"))
        self.assertIn({"t": "ok", "was": "backup"}, self.aus.gesendet)

    def test_server_ohne_backup_warnt(self):
        with mock.patch.object(bridge, "container_version", lambda: "0.40.6"):
            info = bridge.server_info(self.pb, "http://x:8090", timezone.utc)
        self.assertEqual(info["backup_warnung"], "Kein Backup gefunden")

    def test_details_vor_dem_ersten_laden(self):
        # Display fragt Details an, bevor die Bridge zum ersten Mal geladen hat: trotzdem antworten.
        self.assertIsNone(self.b.daten)
        self.b.verarbeiten('{"t":"details","auto":"bus"}')
        self.assertEqual(self.aus.gesendet[-1]["t"], "details")

    def test_details_ohne_server_meldet_fehler(self):
        with mock.patch.object(self.b, "laden", side_effect=OSError("weg")):
            self.b.verarbeiten('{"t":"details","auto":"bus"}')
        self.assertEqual(self.aus.gesendet[-1], {"t": "fehler", "was": "details", "text": "Server nicht erreichbar"})

    def test_container_version_wird_zwischengespeichert(self):
        aufrufe = []

        def falsch(cmd, timeout=None):
            aufrufe.append(cmd)
            return 0, "ghcr.io/muchobien/pocketbase:0.40.6"
        with mock.patch.dict(bridge._VERSION, {"wert": None, "zeit": 0.0}), \
                mock.patch.object(bridge, "befehl", falsch):
            self.assertEqual(bridge.container_version(), "0.40.6")
            self.assertEqual(bridge.container_version(), "0.40.6")
            self.assertEqual(len(aufrufe), 1)
            bridge.container_version(neu=True)  # nach "PocketBase neu starten"
            self.assertEqual(len(aufrufe), 2)

    def test_container_version_fehlschlag_nicht_jedes_mal(self):
        aufrufe = []

        def kaputt(cmd, timeout=None):
            aufrufe.append(cmd)
            return 1, "sudo: a password is required"
        with mock.patch.dict(bridge._VERSION, {"wert": None, "zeit": 0.0}), \
                mock.patch.object(bridge, "befehl", kaputt):
            self.assertEqual(bridge.container_version(), "?")
            self.assertEqual(bridge.container_version(), "?")
            self.assertEqual(len(aufrufe), 1)

    def test_drehung_wird_gesetzt_wenn_abweichend(self):
        b = bridge.Bridge(self.pb, self.aus, "http://x:8090", tz=timezone.utc, drehung=180)
        b.verarbeiten('{"t":"hallo","fw":1,"drehung":0}')
        self.assertEqual(self.aus.gesendet[0], {"t": "einstellung", "drehung": 180})

    def test_drehung_nicht_gesendet_wenn_gleich_oder_leer(self):
        b = bridge.Bridge(self.pb, self.aus, "http://x:8090", tz=timezone.utc, drehung=180)
        b.verarbeiten('{"t":"hallo","fw":1,"drehung":180}')
        self.b.verarbeiten('{"t":"hallo","fw":1,"drehung":0}')
        self.assertNotIn("einstellung", [n["t"] for n in self.aus.gesendet])

    def test_lies_drehung(self):
        self.assertEqual(bridge.lies_drehung("180"), 180)
        self.assertEqual(bridge.lies_drehung(" 0 "), 0)
        self.assertIsNone(bridge.lies_drehung(""))
        self.assertIsNone(bridge.lies_drehung("90"))

    def test_fehlende_schnittstelle_flutet_nicht(self):
        a = bridge.Ausgabe("/dev/gibtsnicht", trocken=False)
        with mock.patch.object(bridge.LOG, "warning") as w:
            for _ in range(5):
                self.assertFalse(a.offen())
        self.assertEqual(w.call_count, 1)

    def test_kaputte_zeile(self):
        self.b.verarbeiten("rauschen{")
        self.assertEqual(self.aus.gesendet, [])


if __name__ == "__main__":
    unittest.main()
