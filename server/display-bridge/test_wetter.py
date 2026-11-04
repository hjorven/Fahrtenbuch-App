import unittest

import wetter


def antwort(temps, nied=None, tau=None, codes=None, akt=3.2, code=3):
    n = len(temps)
    return {"current": {"temperature_2m": akt, "weather_code": code},
            "hourly": {"temperature_2m": temps, "precipitation": nied or [0] * n, "dew_point_2m": tau or [-10] * n,
                       "weather_code": codes or [3] * n}}


class WetterTest(unittest.TestCase):
    def test_ort(self):
        self.assertEqual(wetter.ort_lesen("53.5511,9.9937"), (53.55, 9.99))
        self.assertIsNone(wetter.ort_lesen(""))
        self.assertIsNone(wetter.ort_lesen("abc"))
        self.assertIsNone(wetter.ort_lesen("95,10"))

    def test_kein_glatt_bei_kaelte_ohne_naesse(self):
        n = wetter.auswerten(antwort([-3, -4, -2]))
        self.assertFalse(n["glatt"])
        self.assertEqual(n["min12"], -4)
        self.assertEqual(n["text"], "bedeckt")

    def test_glatt_bei_frost_und_regen(self):
        self.assertTrue(wetter.auswerten(antwort([2, 0.5, 0], nied=[0, 0.4, 0]))["glatt"])

    def test_glatt_bei_reif(self):
        self.assertTrue(wetter.auswerten(antwort([0, -1], tau=[-0.5, -1.5]))["glatt"])

    def test_glatt_bei_schnee(self):
        self.assertTrue(wetter.auswerten(antwort([3, 3], codes=[3, 71]))["glatt"])

    def test_ohne_ort_keine_abfrage(self):
        aufrufe = []
        w = wetter.Wetter(None, holen=lambda a, b: aufrufe.append(1))
        self.assertIsNone(w.nachricht())
        self.assertEqual(aufrufe, [])

    def test_hoechstens_alle_30_min(self):
        aufrufe = []

        def holen(lat, lon):
            aufrufe.append((lat, lon))
            return antwort([5, 6])
        w = wetter.Wetter((53.55, 9.99), holen=holen)
        w.nachricht()
        w.nachricht()
        self.assertEqual(len(aufrufe), 1)


if __name__ == "__main__":
    unittest.main()
