# Aufgaben für den PC (Linux, Claude Code im Terminal)

Diese Datei ist die Aufgabenliste für Claude Code auf des Admins Linux-PC (Bazzite). Ablauf:

```bash
cd ~/Fahrtenbuch-App      # bzw. wo das Repo liegt
git checkout main && git pull
claude
```

Dann in Claude Code: *"Lies docs/PC-AUFGABEN.md und arbeite die offenen Punkte ab."*

## Regeln für Claude Code

- Punkte der Reihe nach abarbeiten, erledigte mit `[x]` abhaken, committen und nach `main` pushen.
- Bei Schritten, die Admin am iPhone oder Handy machen muss (anstecken, "Vertrauen" tippen, Passwort eingeben), anhalten und genau sagen, was zu tun ist.
- Keine Passwörter, Schlüssel oder Pairing-Dateien committen.
- Bazzite ist ein unveränderliches System: Programme bevorzugt als AppImage, Flatpak oder in einer `distrobox` nutzen, `rpm-ostree install` nur wenn nötig (braucht Neustart).
- Wenn etwas nicht klappt: Fehlermeldung zeigen, nicht raten. Offene Probleme unten unter "Notizen" eintragen.

---

## 1. SideStore auf dem iPhone einrichten (Pairing-Datei per USB)

Passend zu [SIDESTORE.md](SIDESTORE.md). Einmal pro iPhone (Admin, danach Ben). Danach erneuert SideStore die Apps alle 7 Tage selbst über WLAN, ohne Kabel.

- [ ] **Vorbereitung am iPhone (Admin macht das):** Gerätecode ist gesetzt, **LocalDevVPN** aus dem App Store installiert und einmal verbunden.
- [ ] **USB-Dienst prüfen:** iPhones brauchen `usbmuxd`.
  ```bash
  systemctl status usbmuxd --no-pager || true
  rpm -q usbmuxd libimobiledevice || true
  ```
  Fehlt etwas, Werkzeuge in einer Distrobox nutzen (kein Neustart nötig):
  ```bash
  distrobox create -n ios -i registry.fedoraproject.org/fedora:latest
  distrobox enter ios -- sudo dnf install -y usbmuxd libimobiledevice-utils
  ```
  Hinweis: `usbmuxd` muss auf dem Host laufen, damit das Gerät erkannt wird. Läuft er dort nicht, mit `rpm-ostree install usbmuxd` nachinstallieren und neu starten.
- [ ] **iPhone anstecken und koppeln:** Admin steckt das iPhone per USB an, entsperrt es und tippt auf "Vertrauen".
  ```bash
  idevice_id -l          # muss eine Geräte-ID zeigen
  idevicepair pair       # danach am iPhone "Vertrauen" bestätigen
  idevicepair validate
  ```
- [ ] **iloader holen:** Neueste Linux-AppImage von der iloader-Release-Seite auf GitHub (Projekt `iloader`, siehe SideStore-Doku) nach `~/Apps/` laden, ausführbar machen, starten.
  ```bash
  mkdir -p ~/Apps && cd ~/Apps
  chmod +x iloader*.AppImage
  ./iloader*.AppImage
  ```
- [ ] **In iloader:** mit des Admins Apple-ID anmelden, SideStore installieren, Pairing-Datei auf das iPhone übertragen lassen. Bei Fragen am iPhone: Profil/Entwickler unter Einstellungen > Allgemein > VPN und Geräteverwaltung vertrauen.
- [ ] **Am iPhone:** SideStore öffnen, mit Apple-ID anmelden, LocalDevVPN verbinden, unter "My Apps" einmal refreshen.
- [ ] **Fahrtenbuch installieren:** Neueste IPA aus den Releases (`ios-<nummer>`) aufs iPhone laden (z. B. Safari > Release-Seite) und in SideStore über `+` installieren.
  ```bash
  gh release list --repo hjorven/Fahrtenbuch-App --limit 5
  ```
- [ ] **Automatisch erneuern:** Kurzbefehl "Refresh All Apps" plus tägliche Automation anlegen (Schritte in SIDESTORE.md, Abschnitt "Automatisch erneuern").
- [ ] **Dasselbe für das iPhone von Ben** (mit seiner eigenen Apple-ID).

## 2. Server aktualisieren (HP ProBook)

**Erledigt bzw. überholt (2026-11-02):** Der Server läuft jetzt auf dem Raspberry Pi, der HP ist aus. Abschnitt bleibt nur zur Nachvollziehbarkeit.

Neue Migration: Namen aller Benutzer sind für alle Angemeldeten sichtbar.

- [ ] Auf den ProBook verbinden (Adresse von Admin erfragen) und aktualisieren:
  ```bash
  ssh <benutzer>@<probook-ip>
  cd ~/Fahrtenbuch-App/server     # Repo dort im Branch "server"
  git pull
  docker compose restart
  docker compose logs --tail=50   # Migration "users_lesbar" muss ohne Fehler durchlaufen
  curl -s http://localhost:8090/api/health
  ```
- [ ] Prüfen, ob die drei Benutzer (Ben, Anna, Admin) in der Admin-Oberfläche `http://<probook-ip>:8090/_/` unter Collections > users existieren, sonst anlegen (siehe `server/README.md` im Branch `server`).

## 3. Signierschlüssel für Android-Updates (einmalig)

**Erledigt (2026-11-02):** Alle vier `FB_*`-Secrets sind gesetzt, der signierte Android-Build ist grün.

Ohne diesen Schritt lassen sich neue APKs nicht über die alte installieren.

- [ ] Schlüssel erzeugen (Admin wählt das Passwort selbst und tippt es ein):
  ```bash
  cd ~ && keytool -genkeypair -keystore fahrtenbuch.jks -alias fahrtenbuch \
    -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Fahrtenbuch"
  ```
- [ ] Als GitHub-Secrets hinterlegen (Passwort nicht ins Terminal-Protokoll schreiben, `gh` fragt interaktiv):
  ```bash
  base64 -w0 ~/fahrtenbuch.jks | gh secret set FB_KEYSTORE_BASE64 --repo hjorven/Fahrtenbuch-App
  gh secret set FB_KEYSTORE_PASSWORD --repo hjorven/Fahrtenbuch-App
  gh secret set FB_KEY_PASSWORD --repo hjorven/Fahrtenbuch-App
  echo -n fahrtenbuch | gh secret set FB_KEY_ALIAS --repo hjorven/Fahrtenbuch-App
  ```
- [ ] `fahrtenbuch.jks` sicher aufbewahren (Passwortmanager oder Backup), **nicht** ins Repo.
- [ ] Neuen Android-Build anstoßen und prüfen, dass er grün ist:
  ```bash
  gh workflow run android.yml --repo hjorven/Fahrtenbuch-App --ref android
  gh run list --repo hjorven/Fahrtenbuch-App --workflow android.yml --limit 1
  ```
  Wichtig: Eine bereits mit dem alten Schlüssel installierte APK muss einmal deinstalliert werden, danach gehen Updates normal.

## 4. Erster Praxistest

- [ ] Alle drei Handys im Heim-WLAN in der App anmelden (Server-Adresse `http://<probook-ip>:8090`).
- [ ] iPhone: "Lokales Netzwerk" und Standort erlauben. Android: Standort, Benachrichtigungen, Akku-Optimierung aus.
- [ ] Kurze Testfahrt mit jedem Handy, danach prüfen, ob die Fahrt auf den anderen Geräten erscheint.
- [ ] Auffälligkeiten unten unter "Notizen" eintragen und pushen, damit sie in der nächsten Claude-Sitzung behoben werden.

## 5. Raspberry Pi als neuer Server (erst wenn Admin Bescheid gibt)

**Erledigt (2026-11-02):** Pi läuft von SD-Karte per LAN, Daten vom HP übernommen. Aktueller Stand und nächste Schritte stehen in [ABSTIMMUNG.md](ABSTIMMUNG.md).

Plan und Hintergründe: [RASPBERRY-PI.md](RASPBERRY-PI.md). Erst anfangen, wenn Pi, SSD und Netzteil da sind.

- [ ] Raspberry Pi Imager installieren und starten:
  ```bash
  flatpak install -y flathub org.raspberrypi.rpi-imager
  flatpak run org.raspberrypi.rpi-imager
  ```
- [ ] Im Imager (Admin klickt, Claude Code sagt an): Raspberry Pi 4, Raspberry Pi OS Lite (64-bit), Ziel = SSD. Einstellungen: Hostname `fahrtenbuch`, Benutzer `admin`, SSH nur mit Schlüssel. Öffentlichen Schlüssel anzeigen, falls nötig erst erzeugen:
  ```bash
  ls ~/.ssh/id_ed25519.pub || ssh-keygen -t ed25519
  cat ~/.ssh/id_ed25519.pub
  ```
- [ ] SSD an den Pi, LAN-Kabel, Strom. Verbindung testen:
  ```bash
  ssh admin@fahrtenbuch.local 'uname -a && vcgencmd measure_temp'
  ```
- [ ] Weiter mit RASPBERRY-PI.md ab Abschnitt 1.2 (Docker, Repo, Datenumzug vom HP). Den Datenumzug nur machen, wenn Admin sagt, dass gerade keiner fährt.

---

## Notizen

(Hier trägt Claude Code Probleme, Fehlermeldungen und offene Fragen ein.)
