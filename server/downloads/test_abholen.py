import tempfile
import unittest
from pathlib import Path

import abholen

IOS = {
    "version": "1.0.19", "buildVersion": "19", "datum": "2026-11-03T20:00:00Z",
    "datei": "Fahrtenbuch-19.ipa", "groesse": 911121, "minOSVersion": "26.0",
    "bundleIdentifier": "de.hjorven.fahrtenbuch",
    "entitlements": ["com.apple.security.application-groups", "com.apple.security.application-groups"],
    "privacy": {"NSMotionUsageDescription": "x", "NSLocationWhenInUseUsageDescription": "y"},
}


class StartseiteTest(unittest.TestCase):
    ANDROID = {"versionCode": 48, "versionName": "1.0.48", "datei": "Fahrtenbuch-48.apk", "sha256": "x"}

    def test_mit_beiden_apps(self):
        html = abholen.startseite(IOS, self.ANDROID, "http://fahrtenbuch.local:8091", qr=lambda t: "<svg>" + t + "</svg>")
        self.assertIn('href="fahrtenbuch.apk"', html)
        self.assertIn("sidestore://source?url=http://fahrtenbuch.local:8091/apps.json", html)
        self.assertIn('href="fahrtenbuch.ipa"', html)
        self.assertIn("1.0.48", html)
        self.assertIn("1.0.19", html)
        self.assertIn("<svg>http://fahrtenbuch.local:8091/apps.json</svg>", html)

    def test_ohne_qrcode_und_ohne_apps(self):
        html = abholen.startseite(None, None, "http://x:8091", qr=lambda t: None)
        self.assertIn("Noch keine App-Version", html)
        self.assertNotIn("<svg", html)

    def test_schreiben(self):
        with tempfile.TemporaryDirectory() as t:
            (Path(t) / "android.json").write_text('{"versionName": "1.0.48"}')
            abholen.startseite_schreiben(t, "http://x:8091")
            self.assertIn("1.0.48", (Path(t) / "index.html").read_text())


class QuelleTest(unittest.TestCase):
    def test_neue_quelle(self):
        q = abholen.quelle_bauen(IOS, "http://fahrtenbuch.local:8091/")
        app = q["apps"][0]
        v = app["versions"][0]
        self.assertEqual(v["version"], "1.0.19")
        self.assertEqual(v["buildVersion"], "19")
        self.assertEqual(v["size"], 911121)
        self.assertEqual(v["downloadURL"], "http://fahrtenbuch.local:8091/Fahrtenbuch-19.ipa")
        self.assertEqual(app["version"], "1.0.19")
        self.assertEqual(app["downloadURL"], v["downloadURL"])
        self.assertEqual(app["appPermissions"]["entitlements"], ["com.apple.security.application-groups"])
        self.assertEqual(list(app["appPermissions"]["privacy"]), ["NSLocationWhenInUseUsageDescription", "NSMotionUsageDescription"])
        self.assertEqual(q["sourceURL"], "http://fahrtenbuch.local:8091/apps.json")

    def test_alte_kennung_bleibt(self):
        alt = {"name": "Familie", "identifier": "pi.fahrtenbuch", "sourceURL": "http://192.168.1.10:8091/apps.json",
               "apps": [{"name": "Fahrtenbuch", "developerName": "Admin", "iconURL": "http://x/i.png"}]}
        q = abholen.quelle_bauen(IOS, "http://fahrtenbuch.local:8091", alt, icon=False)
        self.assertEqual(q["identifier"], "pi.fahrtenbuch")
        self.assertEqual(q["name"], "Familie")
        self.assertEqual(q["sourceURL"], "http://192.168.1.10:8091/apps.json")
        self.assertEqual(q["apps"][0]["developerName"], "Admin")
        self.assertEqual(q["apps"][0]["iconURL"], "http://x/i.png")

    def test_icon_mit_fingerabdruck(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "icon.png"
            p.write_bytes(b"alt")
            alt_stand = abholen.icon_stand(p)
            p.write_bytes(b"neu")
            neu_stand = abholen.icon_stand(p)
            self.assertNotEqual(alt_stand, neu_stand)
            self.assertIsNone(abholen.icon_stand(Path(d) / "fehlt.png"))
        q = abholen.quelle_bauen(IOS, "http://a:8091", icon=neu_stand)
        self.assertEqual(q["apps"][0]["iconURL"], f"http://a:8091/icon.png?v={neu_stand}")
        self.assertEqual(q["iconURL"], q["apps"][0]["iconURL"])
        self.assertEqual(q["tintColor"], "FF3B30")
        self.assertEqual(q["apps"][0]["tintColor"], "FF3B30")
        q = abholen.quelle_bauen(IOS, "http://a:8091", icon=True)
        self.assertEqual(q["apps"][0]["iconURL"], "http://a:8091/icon.png")

    def test_kaputte_alte_quelle(self):
        q = abholen.quelle_bauen(IOS, "http://a:8091", {"apps": "kaputt"})
        self.assertEqual(q["identifier"], "de.hjorven.fahrtenbuch.quelle")


class AufraeumenTest(unittest.TestCase):
    def test_behaelt_die_neuesten(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            for n in ("Fahrtenbuch-9.ipa", "Fahrtenbuch-11.ipa", "Fahrtenbuch-19.ipa",
                      "Fahrtenbuch-26.apk", "Fahrtenbuch-27.apk", "fahrtenbuch.ipa", "apps.json"):
                (d / n).write_text("x")
            weg = abholen.aufraeumen(d, behalten=2)
            self.assertEqual(weg, ["Fahrtenbuch-9.ipa"])
            self.assertTrue((d / "fahrtenbuch.ipa").exists())
            self.assertTrue((d / "Fahrtenbuch-11.ipa").exists())


if __name__ == "__main__":
    unittest.main()
