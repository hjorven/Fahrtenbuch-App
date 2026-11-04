"""Wetter mit Glättewarnung fürs Display (Roadmap Nr. 18).

Nur wenn in /etc/fahrtenbuch-display.env WETTER_ORT=lat,lon gesetzt ist (sonst geht kein Ort
nach außen). Quelle: Open-Meteo (ohne Konto), höchstens alle 30 Minuten abgefragt.
Glätte: Temperatur oder gefühlte Temperatur der nächsten 12 Stunden bei höchstens 1 °C und
Niederschlag oder Taupunkt nahe der Temperatur.
"""
import json
import logging
import time
import urllib.parse
import urllib.request

LOG = logging.getLogger("wetter")
URL = "https://api.open-meteo.com/v1/forecast"
ABSTAND_S = 1800

# WMO-Wettercodes, grob auf Deutsch
CODES = {0: "klar", 1: "heiter", 2: "wolkig", 3: "bedeckt", 45: "Nebel", 48: "Reifnebel", 51: "Niesel", 53: "Niesel",
         55: "Niesel", 56: "gefrierender Niesel", 57: "gefrierender Niesel", 61: "Regen", 63: "Regen", 65: "starker Regen",
         66: "gefrierender Regen", 67: "gefrierender Regen", 71: "Schnee", 73: "Schnee", 75: "starker Schnee", 77: "Schneegriesel",
         80: "Schauer", 81: "Schauer", 82: "starke Schauer", 85: "Schneeschauer", 86: "Schneeschauer", 95: "Gewitter",
         96: "Gewitter mit Hagel", 99: "Gewitter mit Hagel"}
GLATT_CODES = {48, 56, 57, 66, 67, 71, 73, 75, 77, 85, 86}


def ort_lesen(text):
    """"53.55,9.99" -> (53.55, 9.99), sonst None."""
    try:
        lat, lon = (float(x) for x in (text or "").split(","))
    except ValueError:
        return None
    if -90 <= lat <= 90 and -180 <= lon <= 180:
        return round(lat, 2), round(lon, 2)  # auf ~1 km gerundet: Wetter reicht, Adresse nicht nötig
    return None


def auswerten(antwort, stunden=12):
    """Open-Meteo-Antwort in die Display-Nachricht."""
    akt = antwort.get("current", {})
    h = antwort.get("hourly", {})
    temp = h.get("temperature_2m", [])[:stunden]
    nied = h.get("precipitation", [])[:stunden]
    tau = h.get("dew_point_2m", [])[:stunden]
    codes = h.get("weather_code", [])[:stunden]
    glatt = any(c in GLATT_CODES for c in codes)
    for i, t in enumerate(temp):
        if t is None:
            continue
        nass = (i < len(nied) and (nied[i] or 0) > 0) or (i < len(tau) and tau[i] is not None and t - tau[i] < 1.0)
        if t <= 1.0 and nass:
            glatt = True
    code = akt.get("weather_code")
    return {"t": "wetter", "temp": round(akt.get("temperature_2m", 0), 1), "text": CODES.get(code, ""),
            "min12": round(min((t for t in temp if t is not None), default=0), 1), "glatt": glatt}


class Wetter:
    def __init__(self, ort, holen=None):
        self.ort = ort
        self.holen = holen or self._holen
        self.letzte = None
        self.zeit = -1e9

    def _holen(self, lat, lon):
        q = urllib.parse.urlencode({"latitude": lat, "longitude": lon, "timezone": "Europe/Berlin", "forecast_days": 1,
                                    "current": "temperature_2m,weather_code",
                                    "hourly": "temperature_2m,precipitation,dew_point_2m,weather_code"})
        with urllib.request.urlopen(f"{URL}?{q}", timeout=15) as a:
            return json.loads(a.read())

    def nachricht(self):
        """Wetter-Nachricht oder None (kein Ort, oder noch nie erfolgreich geholt)."""
        if not self.ort:
            return None
        if time.monotonic() - self.zeit >= ABSTAND_S:
            self.zeit = time.monotonic()
            try:
                self.letzte = auswerten(self.holen(*self.ort))
            except Exception as e:  # noqa: BLE001
                LOG.warning("Wetter nicht abrufbar: %s", e)
        return self.letzte
