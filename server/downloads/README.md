# Updates für die Apps über den Pi

Das Repo ist privat. Handys kommen ohne Anmeldung nicht an die GitHub-Releases. Deshalb:

1. Jeder Build auf `android` bzw. `ios` legt die neueste APK/IPA plus `android.json` bzw. `ios.json` in den Branch **`downloads`** (immer nur ein Commit, kein Verlauf).
2. Auf dem Pi holt `abholen.py` diesen Branch alle 10 Minuten über den vorhandenen Deploy-Key ab und legt die Dateien in `~/ipas`. Der nginx-Container `fahrtenbuch-ipas` liefert den Ordner auf Port **8091** aus.
3. `abholen.py` erzeugt daraus die SideStore-Quelle `apps.json`. Version, Build-Nummer, Größe und Rechte stammen aus der IPA selbst, deshalb passen Quelle und Datei immer zusammen. Name und Kennung einer schon vorhandenen `apps.json` bleiben erhalten, SideStore sieht sie als dieselbe Quelle.
4. Die Android-App fragt `http://<Server>:8091/android.json` und aktualisiert sich mit einem Tipp. Die iOS-App fragt `apps.json` und zeigt einen Knopf, der SideStore öffnet.

## Einrichten (einmal)

```bash
ssh admin@fahrtenbuch.local
cd ~/Fahrtenbuch-App && git pull
cd server/downloads && sudo ./install.sh
```

Prüfen:

```bash
systemctl list-timers fahrtenbuch-downloads.timer --no-pager
curl -s http://localhost:8091/apps.json | python3 -m json.tool | head -40
curl -s http://localhost:8091/android.json
```

## Wenn etwas nicht geht

```bash
journalctl -u fahrtenbuch-downloads -n 50 --no-pager
sudo -u admin python3 /opt/fahrtenbuch-downloads/abholen.py --trocken
```

`--trocken` zeigt nur, was angeboten würde, und ändert nichts.

Einstellungen stehen in `/etc/fahrtenbuch-downloads.env` (`FB_ZIEL`, `FB_REPO`, `FB_BASIS`).
