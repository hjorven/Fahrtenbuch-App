"""Karte der letzten Fahrt für das Display (Nachtplan Phase 6b).

Gerendert wird auf dem Pi, nicht auf dem ESP32: Route als helle Linie in der Farbe des Autos
auf fast schwarzem Grund, darunter nur die wichtigen Straßen (Autobahn, Bundes-, Landstraße)
in zwei Grautönen. Ergebnis ist ein Bild mit 4 Farben, gepackt mit 2 Bit pro Pixel im Format
LV_COLOR_FORMAT_I2 von LVGL (MSB zuerst, Zeilen auf volle Bytes aufgefüllt).

Straßen kommen einmal pro Fahrt von der Overpass-API und werden unter CACHE zwischengespeichert,
geschickt wird dabei nur der Kartenausschnitt (Bounding-Box), keine Fahrtdaten. Ohne Netz oder
ohne Pillow gibt es nur die Route bzw. gar keine Karte; das Display zeigt dann nur den Text.
"""

import base64
import hashlib
import json
import logging
import math
import os
import time
import urllib.parse
import urllib.request

LOG = logging.getLogger("karte")

BREITE = 320
HOEHE = 200
HINTERGRUND = "#0b0f14"
STRASSE_KLEIN = "#2a3340"   # Landes- und Kreisstraßen (primary, secondary)
STRASSE_GROSS = "#4a5566"   # Autobahn und Bundesstraße (motorway, trunk)
TEIL_BYTES = 1000           # Rohdaten pro Nachricht (Base64 gut 1,3 KB pro Zeile)
OVERPASS = os.environ.get("OVERPASS_URL", "https://overpass-api.de/api/interpreter")
CACHE = os.environ.get("KARTE_CACHE", "/var/cache/fahrtenbuch-display")
RAND = 0.10                 # 10 % Rand um die Route
NEU_VERSUCH_S = 1800        # Overpass nach einem Fehler frühestens nach 30 min wieder fragen

GROSS = ("motorway", "trunk")
KLEIN = ("primary", "secondary")


# ---------------------------------------------------------------------------
# Reine Funktionen (getestet in test_karte.py)
# ---------------------------------------------------------------------------

def polyline_lesen(text):
    """Google-Polyline (Genauigkeit 1e5, wie Android und iOS) in [(lat, lon), ...]."""
    punkte, i, lat, lon = [], 0, 0, 0
    n = len(text or "")
    while i < n:
        werte = []
        for _ in range(2):
            ergebnis, verschiebung = 0, 0
            while True:
                if i >= n:
                    return punkte
                b = ord(text[i]) - 63
                i += 1
                ergebnis |= (b & 0x1F) << verschiebung
                verschiebung += 5
                if b < 0x20:
                    break
            werte.append(~(ergebnis >> 1) if ergebnis & 1 else ergebnis >> 1)
        lat += werte[0]
        lon += werte[1]
        punkte.append((lat / 1e5, lon / 1e5))
    return punkte


def _merc(lat, lon):
    """Web-Mercator in Einheiten 0..1 (x nach rechts, y nach unten)."""
    lat = max(-85.0, min(85.0, lat))
    x = (lon + 180.0) / 360.0
    s = math.sin(math.radians(lat))
    y = 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    return x, y


def ausschnitt(punkte, rand=RAND):
    """Bounding-Box (süd, west, nord, ost) der Route plus Rand, mindestens ca. 500 m."""
    lats = [p[0] for p in punkte]
    lons = [p[1] for p in punkte]
    s, n, w, o = min(lats), max(lats), min(lons), max(lons)
    dlat = max(n - s, 0.0045) * rand + max(0.0, 0.0045 - (n - s)) / 2
    dlon = max(o - w, 0.007) * rand + max(0.0, 0.007 - (o - w)) / 2
    return s - dlat, w - dlon, n + dlat, o + dlon


class Projektion:
    """Bildet lat/lon in Pixel ab, Route mittig, Seitenverhältnis bleibt erhalten."""

    def __init__(self, box, breite=BREITE, hoehe=HOEHE):
        s, w, n, o = box
        x1, y1 = _merc(n, w)
        x2, y2 = _merc(s, o)
        bx, by = max(x2 - x1, 1e-9), max(y2 - y1, 1e-9)
        self.massstab = min(breite / bx, hoehe / by)
        self.x0 = x1 - (breite / self.massstab - bx) / 2
        self.y0 = y1 - (hoehe / self.massstab - by) / 2

    def __call__(self, lat, lon):
        x, y = _merc(lat, lon)
        return (x - self.x0) * self.massstab, (y - self.y0) * self.massstab


def overpass_abfrage(box):
    s, w, n, o = box
    typen = "|".join(GROSS + KLEIN)
    return (f'[out:json][timeout:25];way["highway"~"^({typen})(_link)?$"]'
            f"({s:.5f},{w:.5f},{n:.5f},{o:.5f});out geom;")


def strassen_aus_overpass(antwort):
    """Overpass-JSON in [(gross: bool, [(lat, lon), ...]), ...]."""
    aus = []
    for el in (antwort or {}).get("elements", []):
        geo = el.get("geometry") or []
        typ = (el.get("tags") or {}).get("highway", "").replace("_link", "")
        if len(geo) >= 2 and typ in GROSS + KLEIN:
            aus.append((typ in GROSS, [(g["lat"], g["lon"]) for g in geo]))
    return aus


def i2_packen(pixel, breite, hoehe):
    """Liste von Farbindizes 0..3 (Zeile für Zeile) in Bytes, 4 Pixel pro Byte, MSB zuerst."""
    zeile = (breite * 2 + 7) // 8
    aus = bytearray(zeile * hoehe)
    for y in range(hoehe):
        basis = y * breite
        for x in range(breite):
            v = pixel[basis + x] & 3
            if v:
                aus[y * zeile + x // 4] |= v << (6 - 2 * (x % 4))
    return bytes(aus)


def i2_entpacken(daten, breite, hoehe):
    zeile = (breite * 2 + 7) // 8
    return [(daten[y * zeile + x // 4] >> (6 - 2 * (x % 4))) & 3 for y in range(hoehe) for x in range(breite)]


def teile(fahrt_id, daten, farben, breite=BREITE, hoehe=HOEHE, groesse=TEIL_BYTES):
    """Nachrichten {"t":"karte",...} für die UART, je höchstens `groesse` Rohbytes."""
    stuecke = [daten[i:i + groesse] for i in range(0, len(daten), groesse)] or [b""]
    return [{"t": "karte", "id": fahrt_id, "w": breite, "h": hoehe, "farben": farben,
             "teil": i, "von": len(stuecke), "daten": base64.b64encode(s).decode("ascii")}
            for i, s in enumerate(stuecke)]


def zeichnen(route, strassen, farbe_route, breite=BREITE, hoehe=HOEHE):
    """Bild als Liste von Farbindizes (0 Grund, 1 kleine Straße, 2 große Straße, 3 Route)."""
    from PIL import Image, ImageDraw  # nur hier: ohne Pillow läuft der Rest der Bridge weiter

    proj = Projektion(ausschnitt(route), breite, hoehe)
    bild = Image.new("P", (breite, hoehe), 0)
    stift = ImageDraw.Draw(bild)
    for gross, linie in sorted(strassen, key=lambda s: s[0]):
        stift.line([proj(*p) for p in linie], fill=2 if gross else 1, width=2 if gross else 1)
    pfad = [proj(*p) for p in route]
    if len(pfad) >= 2:
        stift.line(pfad, fill=3, width=3, joint="curve")
    # Start als Ring, Ziel als gefüllter Punkt
    (sx, sy), (zx, zy) = pfad[0], pfad[-1]
    stift.ellipse((sx - 5, sy - 5, sx + 5, sy + 5), outline=3, width=2, fill=0)
    stift.ellipse((zx - 5, zy - 5, zx + 5, zy + 5), fill=3)
    return list(bild.tobytes()), [HINTERGRUND, STRASSE_KLEIN, STRASSE_GROSS, farbe_route]


# ---------------------------------------------------------------------------
# Mit Netz und Cache
# ---------------------------------------------------------------------------

class Kartenmacher:
    def __init__(self, cache=CACHE, url=OVERPASS, holen=None):
        self.cache = cache
        self.url = url
        self.holen = holen or self._holen
        self.fehler_seit = {}

    def _holen(self, abfrage):
        daten = urllib.parse.urlencode({"data": abfrage}).encode()
        r = urllib.request.Request(self.url, data=daten, headers={"User-Agent": "fahrtenbuch-display/1"})
        with urllib.request.urlopen(r, timeout=30) as a:
            return json.loads(a.read())

    def _datei(self, box):
        schluessel = hashlib.sha1(",".join(f"{v:.4f}" for v in box).encode()).hexdigest()[:16]
        return os.path.join(self.cache, f"strassen_{schluessel}.json")

    def strassen(self, box):
        """(Straßen im Ausschnitt, vollständig). Zwischengespeichert; ohne Netz ([], False)."""
        pfad = self._datei(box)
        try:
            with open(pfad, encoding="utf-8") as f:
                return strassen_aus_overpass(json.load(f)), True
        except (OSError, ValueError):
            pass
        if time.monotonic() - self.fehler_seit.get(pfad, -1e9) < NEU_VERSUCH_S:
            return [], False
        try:
            antwort = self.holen(overpass_abfrage(box))
        except Exception as e:  # noqa: BLE001
            LOG.warning("Overpass nicht erreichbar, Karte ohne Straßen: %s", e)
            self.fehler_seit[pfad] = time.monotonic()
            return [], False
        try:
            os.makedirs(self.cache, exist_ok=True)
            with open(pfad, "w", encoding="utf-8") as f:
                json.dump(antwort, f)
        except OSError as e:
            LOG.warning("Straßen nicht zwischengespeichert: %s", e)
        return strassen_aus_overpass(antwort), True

    def nachrichten(self, fahrt_id, route_text, farbe):
        """(Karten-Nachrichten, mit Straßen) für eine Fahrt; (None, True) ohne Route oder Pillow."""
        route = polyline_lesen(route_text)
        if len(route) < 2:
            return None, True
        strassen, vollstaendig = self.strassen(ausschnitt(route))
        try:
            pixel, farben = zeichnen(route, strassen, farbe or "#ff3b30")
        except ImportError:
            LOG.warning("Pillow fehlt (apt-get install python3-pil), keine Karte")
            return None, True
        return teile(fahrt_id, i2_packen(pixel, BREITE, HOEHE), farben), vollstaendig
