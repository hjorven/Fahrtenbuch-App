#!/usr/bin/env python3
"""Holt neue App-Builds aus dem Branch "downloads" und bietet sie auf dem Pi an.

Die GitHub Actions legen bei jedem Build die neueste APK/IPA plus Beschreibung
(android.json, ios.json) in den Branch "downloads". Dieses Skript läuft alle
10 Minuten (systemd-Timer), holt den Branch über den vorhandenen Deploy-Key und
legt alles in den Ordner, den der nginx-Container auf Port 8091 ausliefert.

Daraus entsteht die SideStore-Quelle apps.json. Versionsnummer, Build-Nummer und
Größe stammen aus der IPA selbst (CI liest sie aus), deshalb passen Quelle und
Datei immer zusammen und SideStore zeigt "Aktualisieren" an.

Nur Python-Standardbibliothek. Aufruf: python3 abholen.py [--trocken]
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

HEIM = Path.home()
REPO = Path(os.environ.get("FB_REPO", HEIM / "Fahrtenbuch-App"))
ZIEL = Path(os.environ.get("FB_ZIEL", HEIM / "ipas"))
BASIS = os.environ.get("FB_BASIS", "http://fahrtenbuch.local:8091").rstrip("/")
CACHE = Path(os.environ.get("FB_CACHE", HEIM / ".cache" / "fahrtenbuch-downloads.git"))
TINT = "FF3B30"
BEHALTEN = 2  # so viele alte Builds pro Plattform bleiben liegen

BUILD = re.compile(r"^Fahrtenbuch-(\d+)\.(apk|ipa)$")


def git(*args, cwd=None):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def icon_stand(pfad):
    """Kurzer Fingerabdruck des Icons. SideStore merkt sich Bilder pro Adresse; ändert sich
    das Icon, ändert sich so auch die Adresse und SideStore lädt es neu."""
    try:
        return hashlib.sha256(Path(pfad).read_bytes()).hexdigest()[:10]
    except OSError:
        return None


def quelle_bauen(ios, basis, alt=None, icon=True):
    """SideStore-/AltStore-Quelle (Format 2.0, plus alte Felder für ältere SideStore-Versionen).

    Name, Kennung und Adresse der Quelle werden aus der alten apps.json übernommen, damit
    SideStore sie als dieselbe Quelle erkennt.
    """
    alt = alt if isinstance(alt, dict) else {}
    apps = alt.get("apps")
    alt_app = apps[0] if isinstance(apps, list) and apps and isinstance(apps[0], dict) else {}
    basis = basis.rstrip("/")
    url = f"{basis}/{ios['datei']}"
    # icon: False = kein Icon, True = icon.png, Text = icon.png mit Fingerabdruck (?v=...)
    icon_url = (f"{basis}/icon.png" + (f"?v={icon}" if isinstance(icon, str) else "")) if icon else alt_app.get("iconURL", "")
    build = str(ios["buildVersion"])
    version = {
        "version": str(ios["version"]),
        "buildVersion": build,
        "date": ios["datum"],
        "downloadURL": url,
        "size": int(ios["groesse"]),
        "minOSVersion": ios.get("minOSVersion", "26.0"),
        "localizedDescription": f"Build {build}",
    }
    app = {
        "name": alt_app.get("name") or "Fahrtenbuch",
        "bundleIdentifier": ios["bundleIdentifier"],
        "developerName": alt_app.get("developerName") or "Familie",
        "subtitle": alt_app.get("subtitle") or "Fahrten und Tanken für die Familie",
        "localizedDescription": alt_app.get("localizedDescription") or "Familien-Fahrtenbuch",
        "iconURL": icon_url,
        # Farbe passend zum App-Icon (roter Zielpunkt); bewusst nicht aus der alten Quelle übernommen.
        "tintColor": TINT,
        "category": "utilities",
        "versions": [version],
        # Ältere SideStore-Versionen lesen diese Felder direkt am App-Eintrag.
        "version": version["version"],
        "versionDate": version["date"],
        "downloadURL": url,
        "size": version["size"],
        # SideStore bricht ab, wenn die App Rechte nutzt, die hier nicht stehen.
        "appPermissions": {
            "entitlements": sorted(set(ios.get("entitlements", []))),
            "privacy": dict(sorted(ios.get("privacy", {}).items())),
        },
    }
    return {
        "name": alt.get("name") or "Fahrtenbuch",
        # Rundes Bild oben auf der Quellen-Seite; ohne Eintrag hält SideStore das alte fest.
        "iconURL": icon_url,
        "tintColor": TINT,
        "identifier": alt.get("identifier") or "de.hjorven.fahrtenbuch.quelle",
        "sourceURL": alt.get("sourceURL") or f"{basis}/apps.json",
        "apps": [app],
        "news": [],
    }


def aufraeumen(ziel, behalten=BEHALTEN):
    """Alte Fahrtenbuch-N.apk/.ipa löschen, die neuesten [behalten] pro Endung bleiben."""
    weg = []
    for endung in ("apk", "ipa"):
        builds = sorted(
            ((int(m.group(1)), p) for p in ziel.iterdir() if (m := BUILD.match(p.name)) and m.group(2) == endung),
            reverse=True,
        )
        for _, p in builds[behalten:]:
            p.unlink()
            weg.append(p.name)
    return weg


def kopieren(quelle, ziel):
    """Erst unter anderem Namen schreiben, dann umbenennen: nie halbe Dateien im Angebot."""
    tmp = ziel.with_name("." + ziel.name + ".neu")
    shutil.copyfile(quelle, tmp)
    os.chmod(tmp, 0o644)
    os.replace(tmp, ziel)


def json_schreiben(daten, ziel):
    tmp = ziel.with_name("." + ziel.name + ".neu")
    tmp.write_text(json.dumps(daten, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.chmod(tmp, 0o644)
    os.replace(tmp, ziel)


def lesen(p):
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def qr_svg(text):
    """QR-Code als SVG-Text, None ohne python3-qrcode (dann zeigt die Seite nur Links)."""
    try:
        import qrcode
        import qrcode.image.svg
    except ImportError:
        return None
    img = qrcode.make(text, image_factory=qrcode.image.svg.SvgPathImage, box_size=8, border=2)
    svg = img.to_string(encoding="unicode")
    return svg[svg.find("<svg"):]


def startseite(ios, android, basis, qr=qr_svg):
    """index.html für http://fahrtenbuch.local:8091/ (Nachtplan 6c Nr. 4).

    Links sind relativ bzw. werden per JavaScript aus der aufgerufenen Adresse gebaut, damit die
    Seite auch über die IP oder Tailscale funktioniert (fahrtenbuch.local löst nicht überall auf).
    """
    from html import escape as e
    quelle = f"{basis}/apps.json"
    teile = []
    if android:
        teile.append(f"""<section><h2>Android</h2>
<a class="knopf" href="fahrtenbuch.apk">Android-App laden</a>
<p>Version {e(str(android.get("versionName", "?")))}. Nach dem Laden öffnen und „Installieren“ tippen. Beim ersten Mal fragt Android, ob der Browser Apps installieren darf: erlauben. Danach kommen Updates mit einem Tipp in der App.</p>
{qr(basis + "/fahrtenbuch.apk") or ""}</section>""")
    if ios:
        teile.append(f"""<section><h2>iPhone (SideStore)</h2>
<a class="knopf" id="sidestore" href="sidestore://source?url={e(quelle)}">SideStore-Quelle hinzufügen</a>
<p>Oder in SideStore unter „Quellen“ → „+“ diese Adresse eintragen:</p>
<p><code id="quelle">{e(quelle)}</code> <button onclick="navigator.clipboard.writeText(document.getElementById('quelle').textContent)">Kopieren</button></p>
<p>Version {e(str(ios.get("version", "?")))}. Hakt die Quelle, geht es auch direkt: <a href="fahrtenbuch.ipa">IPA laden</a> und in SideStore unter „Meine Apps“ → „+“ öffnen.</p>
{qr(quelle) or ""}</section>""")
    if not teile:
        teile.append("<section><p>Noch keine App-Version abgeholt. Der Pi schaut alle 10 Minuten nach.</p></section>")
    return f"""<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fahrtenbuch</title>
<style>
:root {{ color-scheme: light dark; --rot: #ff3b30; }}
body {{ font-family: system-ui, sans-serif; max-width: 34rem; margin: 0 auto; padding: 1rem; line-height: 1.45; }}
h1 {{ margin: .2rem 0 1rem; }}
section {{ border: 1px solid #8884; border-radius: 12px; padding: 1rem; margin-bottom: 1rem; }}
.knopf {{ display: block; text-align: center; background: var(--rot); color: #fff; text-decoration: none; font-weight: 600; padding: .9rem; border-radius: 10px; font-size: 1.1rem; }}
code {{ word-break: break-all; }}
svg {{ width: 160px; height: 160px; display: block; margin: .5rem auto 0; background: #fff; }}
</style></head><body>
<h1>Fahrtenbuch</h1>
{"".join(teile)}
<p><small>Adressen für die Apps: Heimnetz <code>http://fahrtenbuch.local:8090</code>. Klappt das nicht, die IP-Adresse dieser Seite mit Port 8090 nehmen.</small></p>
<script>
// Über die IP oder Tailscale geöffnet: SideStore-Adresse an diese Adresse anpassen.
if (location.host && !location.host.startsWith("fahrtenbuch.local")) {{
  var q = location.origin + "/apps.json";
  var a = document.getElementById("sidestore"); if (a) a.href = "sidestore://source?url=" + encodeURIComponent(q);
  var c = document.getElementById("quelle"); if (c) c.textContent = q;
}}
</script>
</body></html>
"""


def startseite_schreiben(ziel=None, basis=None):
    ziel = Path(ziel or ZIEL)
    if not ziel.exists():
        return
    html = startseite(lesen(ziel / "ios.json"), lesen(ziel / "android.json"), basis or BASIS)
    tmp = ziel / ".index.html.tmp"
    tmp.write_text(html, encoding="utf-8")
    tmp.replace(ziel / "index.html")


def main(trocken=False):
    url = git("-C", str(REPO), "remote", "get-url", "origin")
    if not CACHE.exists():
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        git("init", "-q", "--bare", str(CACHE))
    git("-C", str(CACHE), "fetch", "-q", "--depth", "1", "--force", url, "+downloads:refs/heads/downloads")
    stand = git("-C", str(CACHE), "rev-parse", "downloads")
    stand_datei = ZIEL / ".stand"
    if not trocken and stand_datei.exists() and stand_datei.read_text().strip() == stand and (ZIEL / "apps.json").exists():
        startseite_schreiben()  # z. B. nach einem Update von abholen.py
        return 0  # nichts Neues

    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        tar = tmp / "d.tar"
        with open(tar, "wb") as f:
            subprocess.run(["git", "-C", str(CACHE), "archive", "--format=tar", "downloads"], stdout=f, check=True)
        neu = tmp / "neu"
        neu.mkdir()
        with tarfile.open(tar) as tf:
            tf.extractall(neu, filter="data")

        ios = lesen(neu / "ios.json")
        android = lesen(neu / "android.json")
        if trocken:
            print("Stand", stand)
            print("android.json:", android)
            if ios:
                print(json.dumps(quelle_bauen(ios, BASIS, lesen(ZIEL / "apps.json"), icon_stand(neu / "icon.png") or False), indent=2))
            return 0

        ZIEL.mkdir(parents=True, exist_ok=True)
        # Erst die Dateien, dann die Beschreibungen: Die Apps sehen eine neue Version
        # erst, wenn die Datei schon vollständig daliegt.
        for p in sorted(neu.iterdir()):
            if BUILD.match(p.name) or p.name == "icon.png":
                kopieren(p, ZIEL / p.name)
        if ios and (neu / ios["datei"]).exists():
            kopieren(neu / ios["datei"], ZIEL / "fahrtenbuch.ipa")  # feste Adresse als Notlösung
            json_schreiben(ios, ZIEL / "ios.json")
            json_schreiben(quelle_bauen(ios, BASIS, lesen(ZIEL / "apps.json"), icon_stand(ZIEL / "icon.png") or False), ZIEL / "apps.json")
            print(f"iOS: Build {ios['buildVersion']} (Version {ios['version']}) angeboten")
        if android and (neu / android["datei"]).exists():
            kopieren(neu / android["datei"], ZIEL / "fahrtenbuch.apk")
            json_schreiben(android, ZIEL / "android.json")
            print(f"Android: Version {android['versionCode']} angeboten")
        weg = aufraeumen(ZIEL)
        if weg:
            print("Alte Builds gelöscht:", ", ".join(weg))
        startseite_schreiben()
        stand_datei.write_text(stand + "\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(trocken="--trocken" in sys.argv))
    except subprocess.CalledProcessError as e:
        print("git fehlgeschlagen:", " ".join(e.cmd), "\n", (e.stderr or "").strip(), file=sys.stderr)
        sys.exit(1)
