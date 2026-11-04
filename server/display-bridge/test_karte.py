import base64
import tempfile
import unittest

import karte


def polyline(punkte):
    """Encoder wie in den Apps (Genauigkeit 1e5), nur für die Tests."""
    aus, plat, plon = [], 0, 0
    for lat, lon in punkte:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for d in (ilat - plat, ilon - plon):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                aus.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            aus.append(chr(v + 63))
        plat, plon = ilat, ilon
    return "".join(aus)


ROUTE = [(53.55, 9.99), (53.56, 9.995), (53.57, 10.01)]


class TestKarte(unittest.TestCase):
    def test_polyline_hin_und_zurueck(self):
        zurueck = karte.polyline_lesen(polyline(ROUTE))
        self.assertEqual(len(zurueck), 3)
        for (a, b), (c, d) in zip(ROUTE, zurueck):
            self.assertAlmostEqual(a, c, places=5)
            self.assertAlmostEqual(b, d, places=5)
        self.assertEqual(karte.polyline_lesen(""), [])

    def test_projektion_innerhalb_des_bildes(self):
        p = karte.Projektion(karte.ausschnitt(ROUTE))
        for lat, lon in ROUTE:
            x, y = p(lat, lon)
            self.assertTrue(0 <= x <= karte.BREITE and 0 <= y <= karte.HOEHE, (x, y))
        # Norden oben
        self.assertLess(p(53.57, 10.0)[1], p(53.55, 10.0)[1])

    def test_ausschnitt_mindestgroesse(self):
        s, w, n, o = karte.ausschnitt([(53.5, 10.0), (53.5, 10.0)])
        self.assertGreater(n - s, 0.004)
        self.assertGreater(o - w, 0.006)

    def test_packen_und_entpacken(self):
        pixel = [(x + y) % 4 for y in range(3) for x in range(10)]
        daten = karte.i2_packen(pixel, 10, 3)
        self.assertEqual(len(daten), 3 * 3)  # 10 Pixel = 20 Bit = 3 Bytes pro Zeile
        self.assertEqual(karte.i2_entpacken(daten, 10, 3), pixel)
        self.assertEqual(karte.i2_packen([3, 0, 0, 0], 4, 1), bytes([0b11000000]))

    def test_teile(self):
        daten = bytes(range(256)) * 63  # 16.128 Bytes
        n = karte.teile("abc", daten, ["#000000"] * 4)
        self.assertEqual(len(n), 17)
        self.assertEqual({m["von"] for m in n}, {17})
        self.assertEqual(b"".join(base64.b64decode(m["daten"]) for m in n), daten)
        self.assertTrue(all(len(base64.b64decode(m["daten"])) <= karte.TEIL_BYTES for m in n))

    def test_overpass_antwort(self):
        antwort = {"elements": [
            {"tags": {"highway": "motorway"}, "geometry": [{"lat": 53.55, "lon": 9.99}, {"lat": 53.56, "lon": 10.0}]},
            {"tags": {"highway": "secondary_link"}, "geometry": [{"lat": 53.55, "lon": 9.99}, {"lat": 53.56, "lon": 10.0}]},
            {"tags": {"highway": "residential"}, "geometry": [{"lat": 53.55, "lon": 9.99}, {"lat": 53.56, "lon": 10.0}]},
        ]}
        s = karte.strassen_aus_overpass(antwort)
        self.assertEqual([g for g, _ in s], [True, False])
        self.assertIn("motorway|trunk|primary|secondary", karte.overpass_abfrage((1, 2, 3, 4)))

    def test_rendern_ohne_netz(self):
        def kein_netz(_):
            raise OSError("kein Netz")
        with tempfile.TemporaryDirectory() as ordner:
            m = karte.Kartenmacher(cache=ordner, holen=kein_netz)
            nachr, voll = m.nachrichten("f1", polyline(ROUTE), "#ff3b30")
        self.assertFalse(voll)
        daten = b"".join(base64.b64decode(x["daten"]) for x in nachr)
        self.assertEqual(len(daten), 80 * 200)
        pixel = karte.i2_entpacken(daten, 320, 200)
        self.assertIn(3, pixel)          # Route
        self.assertNotIn(1, pixel)       # keine Straßen
        self.assertEqual(nachr[0]["farben"][3], "#ff3b30")

    def test_cache_ohne_zweite_abfrage(self):
        aufrufe = []

        def holen(abfrage):
            aufrufe.append(abfrage)
            return {"elements": [{"tags": {"highway": "primary"},
                                  "geometry": [{"lat": 53.551, "lon": 9.98}, {"lat": 53.569, "lon": 10.02}]}]}
        with tempfile.TemporaryDirectory() as ordner:
            m = karte.Kartenmacher(cache=ordner, holen=holen)
            n1, voll = m.nachrichten("f1", polyline(ROUTE), "#ff3b30")
            karte.Kartenmacher(cache=ordner, holen=holen).nachrichten("f1", polyline(ROUTE), "#ff3b30")
        self.assertTrue(voll)
        self.assertEqual(len(aufrufe), 1)
        pixel = karte.i2_entpacken(b"".join(base64.b64decode(x["daten"]) for x in n1), 320, 200)
        self.assertIn(1, pixel)

    def test_fehlschlag_nicht_sofort_wiederholt(self):
        aufrufe = []

        def kaputt(abfrage):
            aufrufe.append(abfrage)
            raise OSError("weg")
        with tempfile.TemporaryDirectory() as ordner:
            m = karte.Kartenmacher(cache=ordner, holen=kaputt)
            m.nachrichten("f1", polyline(ROUTE), "#fff")
            m.nachrichten("f1", polyline(ROUTE), "#fff")
        self.assertEqual(len(aufrufe), 1)

    def test_ohne_route_keine_karte(self):
        self.assertEqual(karte.Kartenmacher(cache="/nicht/da").nachrichten("f1", "", "#fff"), (None, True))


if __name__ == "__main__":
    unittest.main()
