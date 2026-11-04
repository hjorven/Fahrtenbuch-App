#!/usr/bin/env python3
"""Spielt eine neue Display-Firmware vom Pi aus über die serielle Leitung ein, ohne Knöpfe.

    sudo python3 display_update.py fahrtenbuch-display-cyd-app.bin
    sudo python3 display_update.py fahrtenbuch-display-cyd-app.bin --port /dev/ttyUSB0

Wichtig: die *-app.bin aus dem Release nehmen, nicht die Datei für esptool (ab 0x0).
Das Display muss schon eine Firmware mit Update-Empfang haben (siehe README, "Update vom Pi").
Die Firmware landet im zweiten App-Slot, erst nach erfolgreicher MD5-Prüfung wird umgeschaltet.
Bricht etwas ab, läuft die alte Firmware einfach weiter.

Die display-bridge belegt die Schnittstelle, deshalb wird sie für das Update kurz gestoppt.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import time

import serial

DIENST = "fahrtenbuch-display.service"
BLOCK = 1024
BLOCK_VERSUCHE = 6     # so oft wird ein einzelner Block neu geschickt
DURCHLAEUFE = 3        # so oft wird das ganze Update versucht


def nachricht(ser, timeout):
    """Nächste {"t":"update",...}-Zeile vom Display, andere Zeilen werden übersprungen."""
    ende = time.monotonic() + timeout
    while time.monotonic() < ende:
        roh = ser.readline()
        if not roh:
            continue
        zeile = roh.decode(errors="replace").strip()
        if not zeile.startswith("{"):
            continue
        try:
            d = json.loads(zeile)
        except ValueError:
            continue
        if d.get("t") != "update":
            continue
        if d.get("status") == "fehler":
            raise RuntimeError(f"Display meldet Fehler: {d.get('text')}")
        return d
    raise TimeoutError("keine Antwort vom Display")


def warte(ser, status, timeout):
    """Wartet auf eine der erwarteten Statusmeldungen (str oder Tupel)."""
    erwartet = (status,) if isinstance(status, str) else tuple(status)
    ende = time.monotonic() + timeout
    while True:
        d = nachricht(ser, max(0.1, ende - time.monotonic()))
        if d.get("status") in erwartet:
            return d


def warte_hallo(ser, timeout):
    ende = time.monotonic() + timeout
    while time.monotonic() < ende:
        zeile = ser.readline().decode(errors="replace").strip()
        if zeile.startswith("{"):
            try:
                d = json.loads(zeile)
            except ValueError:
                continue
            if d.get("t") == "hallo":
                return d
    return None


def uebertragen(ser, daten, md5):
    """Ein Update-Durchlauf. Wirft bei Fehler, das Display behält dann die alte Firmware."""
    ser.reset_input_buffer()
    kopf = {"t": "update", "groesse": len(daten), "md5": md5}
    ser.write(b"\n" + json.dumps(kopf).encode() + b"\n")
    warte(ser, "bereit", 15)
    start = time.monotonic()
    wiederholt = 0
    for off in range(0, len(daten), BLOCK):
        block = daten[off:off + BLOCK]
        soll = off + len(block)
        for _ in range(BLOCK_VERSUCHE):
            ser.write(block)
            antwort = warte(ser, ("ack", "nochmal"), 15)
            stand = int(antwort.get("bytes", -1))
            if antwort["status"] == "ack" or stand == soll:
                # "nochmal" mit stand == soll: Block kam an, nur unser ack ging verloren
                if stand != soll:
                    raise RuntimeError(f"falsche Bestätigung: {antwort} statt {soll}")
                break
            if stand != off:
                raise RuntimeError(f"Display ist bei {stand}, erwartet {off}")
            wiederholt += 1  # Display hat Bytes vermisst und will denselben Block noch einmal
        else:
            raise RuntimeError(f"Block bei {off} auch nach {BLOCK_VERSUCHE} Versuchen nicht angekommen")
        print(f"\r{soll * 100 // len(daten):3d} %", end="", flush=True)
    print(f"  ({time.monotonic() - start:.0f} s, {wiederholt} Blöcke wiederholt)")
    warte(ser, "ok", 30)


def dienst(aktion):
    subprocess.run(["systemctl", aktion, DIENST], check=False,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("datei", help="App-Image, z. B. fahrtenbuch-display-cyd-app.bin")
    ap.add_argument("--port", default="/dev/serial0")
    ap.add_argument("--build", type=int, help="erwartete Build-Nummer (N aus display-N) zur Kontrolle")
    args = ap.parse_args()

    daten = open(args.datei, "rb").read()
    if not daten or daten[0] != 0xE9:
        sys.exit("FEHLER: kein ESP32-App-Image (erstes Byte nicht 0xE9). "
                 "Bitte die *-app.bin nehmen, nicht die Datei für esptool ab 0x0.")
    md5 = hashlib.md5(daten).hexdigest()
    print(f"{args.datei}: {len(daten)} Byte, MD5 {md5}")

    war_aktiv = subprocess.run(["systemctl", "is-active", "--quiet", DIENST]).returncode == 0
    if war_aktiv:
        dienst("stop")
        time.sleep(1)
    try:
        with serial.Serial(args.port, 115200, timeout=1) as ser:
            time.sleep(0.3)
            for durchlauf in range(1, DURCHLAEUFE + 1):
                try:
                    uebertragen(ser, daten, md5)
                    break
                except (RuntimeError, TimeoutError) as e:
                    print(f"\nDurchlauf {durchlauf} fehlgeschlagen: {e}")
                    if durchlauf == DURCHLAEUFE:
                        raise
                    print("Alte Firmware läuft weiter, neuer Versuch in 8 s ...")
                    time.sleep(8)  # Display zeigt 4 s den Fehler, dann wieder bereit
            print("Update angenommen, Display startet neu ...")
            hallo = warte_hallo(ser, 25)
            if not hallo:
                print("WARNUNG: nach dem Neustart kein hallo gesehen (Display prüfen)")
            else:
                build = hallo.get("build")
                print(f"Display meldet sich: display-{build if build is not None else '?'}, "
                      f"Variante {hallo.get('variante')}, Drehung {hallo.get('drehung', '?')}")
                if args.build is not None and build != args.build:
                    raise RuntimeError(f"Display meldet Build {build}, erwartet {args.build}")
    finally:
        if war_aktiv:
            dienst("start")
    print("FERTIG")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, TimeoutError, OSError, serial.SerialException) as e:
        print(f"\nFEHLER: {e}")
        sys.exit(1)
