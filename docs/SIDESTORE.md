# iPhone-Installation mit SideStore

Ohne bezahlten Apple-Developer-Account wird die IPA über SideStore installiert. Ein Computer wird nur einmal für die Ersteinrichtung gebraucht, danach läuft das Erneuern der 7-Tage-Signatur auf dem iPhone selbst.

## Einmalige Einrichtung (pro iPhone)

1. Gerätecode am iPhone setzen (Pflicht, sonst lässt sich das VPN-Profil nicht installieren).
2. **LocalDevVPN** aus dem App Store installieren und einmal verbinden.
3. **iloader** auf einem PC installieren (Linux-AppImage läuft auf Bazzite, sonst einer der Windows-Rechner).
4. iPhone per USB anschließen, entsperren, dem Computer vertrauen.
5. Mit iloader SideStore installieren und die Pairing-Datei übertragen.
6. In SideStore mit der eigenen Apple-ID anmelden (Ben und Admin jeweils mit ihrer eigenen).
7. Im Tab "My Apps" einmal refreshen.

## Fahrtenbuch installieren

1. IPA aus dem GitHub-Release herunterladen (oder direkt aus SideStore per URL).
2. LocalDevVPN verbinden.
3. SideStore öffnen, `+` tippen, IPA auswählen.

## Updates (ein Tipp in SideStore)

Neue Versionen kommen ohne Kabel und ohne Datei-Auswahl:

1. Jeder iOS-Build legt die IPA in den Branch `downloads`. Der Pi holt ihn alle 10 Minuten ab (`server/downloads/`) und baut daraus die SideStore-Quelle `http://fahrtenbuch.local:8091/apps.json`.
2. Die App zeigt oben einen Hinweis "Neue Version der App ist da" mit dem Knopf **SideStore öffnen**.
3. In SideStore bei Fahrtenbuch auf **Update** tippen (LocalDevVPN muss an sein). Alle Fahrten bleiben erhalten.

Die Quelle wird einmal pro iPhone hinzugefügt: SideStore > Sources > `+` > `http://fahrtenbuch.local:8091/apps.json`. Danach steht das Fahrtenbuch in "My Apps" mit Update-Knopf.

Ab Build 1.0.N trägt jede IPA eine eigene Versionsnummer (früher immer `1.0`, deshalb hat SideStore kein Update angezeigt). Das erste Update von einem alten Build klappt über die Quelle. Falls SideStore trotzdem nichts anbietet: in "My Apps" nach unten ziehen zum Aktualisieren, sonst einmal über `+` die Datei `http://fahrtenbuch.local:8091/fahrtenbuch.ipa` installieren (Daten bleiben).

Android läuft über denselben Weg: Die App zeigt "Neue Version der App ist da", ein Tipp auf **Jetzt aktualisieren** lädt und prüft die APK, danach fragt Android einmal "Aktualisieren?". Beim allerersten Update fragt Android zusätzlich nach der Erlaubnis "Unbekannte Apps installieren", die App führt dorthin.

## Automatisch erneuern

Kurzbefehle-App:

1. Neuer Kurzbefehl "Apps erneuern" mit der SideStore-Aktion "Refresh All Apps", "Beim Ausführen anzeigen" ausschalten.
2. Automation anlegen, z. B. täglich um 3 Uhr oder "Wenn Ladegerät verbunden", "Sofort ausführen".
3. Kurzbefehl einmal manuell ausführen und alle Berechtigungen erlauben.

LocalDevVPN muss beim Erneuern verbunden sein.

## Einschränkungen mit kostenloser Apple-ID

- Maximal 3 aktive sideloaded Apps (SideStore selbst zählt mit).
- Maximal 10 App-IDs pro 7 Tage. Die App, das Widget und die Live Activity brauchen jeweils eine eigene App-ID. Deshalb sollen Widget, Control und Live Activity in **einer** Extension liegen.
- App Groups (für den Datenaustausch App/Widget) funktionieren, SideStore benennt sie beim Signieren um. Die App liest den Gruppennamen daher zur Laufzeit aus dem Entitlement statt ihn fest einzutragen.
- Kein Push, kein iCloud.

## Was realistisch klappt und was nicht

- **Kein Kabel im Alltag:** Das Erneuern läuft komplett auf dem iPhone. Voraussetzung ist nur, dass LocalDevVPN verbunden ist und das iPhone Internet hat (SideStore spricht beim Signieren mit Apple).
- **SideStore läuft selbst auch nach 7 Tagen ab.** "Refresh All Apps" erneuert SideStore mit. Sind SideStore *und* die App abgelaufen, braucht es wieder einen Computer mit iloader.
- **Abgelaufene App:** Startet nicht mehr, die Daten auf dem iPhone bleiben aber erhalten. Ein Refresh in SideStore reicht, dann geht sie wieder.
- **Pairing-Datei kann ungültig werden**, z. B. nach "Netzwerkeinstellungen zurücksetzen", manchmal nach einem iOS-Update. Dann einmal per USB mit iloader neu übertragen.
- **Apple-ID-Passwort geändert:** In SideStore neu anmelden.
- **Datenverlust ausgeschlossen:** Alles, was einmal synchronisiert wurde, liegt auf dem Server. Auch eine Neuinstallation holt die Fahrten wieder ab.

## Alternative

Apple Developer Program (99 Euro pro Jahr): Verteilung über TestFlight oder Ad-hoc, Signatur ein Jahr gültig, keine App-Grenzen.
