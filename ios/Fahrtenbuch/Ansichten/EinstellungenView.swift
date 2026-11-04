import CoreLocation
import SwiftData
import SwiftUI
import UIKit

struct EinstellungenView: View {
    @Query(sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    @State private var konto = Konto.shared
    @State private var sync = SyncManager.shared
    @State private var recorder = FahrtRecorder.shared
    @State private var bearbeiten: Fahrzeug?
    @AppStorage("einrichtungFertig") private var einrichtungFertig = true
    @State private var adressenZeigen = false
    @State private var hauptEingabe = ""
    @State private var tailscaleEingabe = ""
    @State private var testLoeschenFragen = false
    @State private var testMeldung: String?
    @State private var passwortZeigen = false
    @State private var abmeldenFragen = false
    @State private var einladungText: String?
    @State private var einladungZeigen = false
    @State private var pruefung: [(name: String, ok: Bool?, text: String)] = []
    @State private var prueftGerade = false
    @State private var problemZeigen = false
    @State private var problemText = ""
    @State private var problemMeldung: String?
    @State private var offeneEintraege = 0
    @Environment(\.modelContext) private var context

    var body: some View {
        Form {
            if let a = konto.anmeldung {
                Section("Konto") {
                    LabeledContent("Angemeldet als", value: a.name.isEmpty ? a.email : a.name)
                    Button {
                        hauptEingabe = a.serverUrl
                        tailscaleEingabe = konto.tailscaleUrl
                        adressenZeigen = true
                    } label: {
                        LabeledContent("Server-Adressen", value: adressenText(a))
                    }
                    // Roadmap Nr. 1/2: QR mit Adressen und Einladungscode (24 h, einmal benutzbar)
                    Button("Familienmitglied einladen") {
                        einladungText = nil
                        einladungZeigen = true
                        Task { einladungText = await einladungErstellen() ?? "" }
                    }
                    Button("Problem melden") { problemText = ""; problemZeigen = true }
                    if let problemMeldung { Text(problemMeldung).font(.footnote).foregroundStyle(.secondary) }
                    Button("Passwort ändern") { passwortZeigen = true }
                    Button("Abmelden", role: .destructive) {
                        // Vorher fragen und nennen, was noch nicht auf dem Server ist (Nachtplan 5b Fall 20).
                        let fahrten = (try? context.fetchCount(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.dirty }))) ?? 0
                        let tank = (try? context.fetchCount(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.dirty }))) ?? 0
                        offeneEintraege = fahrten + tank
                        abmeldenFragen = true
                    }
                }
                if konto.anmeldung != nil { PlanungAbschnitte() }
                // Verbindungstest (Roadmap Nr. 5): Ampel mit Hinweis, was zu tun ist.
                Section("Verbindungstest") {
                    Button(prueftGerade ? "Prüfe ..." : "Verbindung testen") {
                        prueftGerade = true
                        Task { pruefung = await verbindungPruefen(); prueftGerade = false }
                    }
                    .disabled(prueftGerade)
                    ForEach(Array(pruefung.enumerated()), id: \.offset) { _, p in
                        HStack(alignment: .top) {
                            Circle().fill(p.ok == true ? Color.green : p.ok == false ? Color.red : Color.gray).frame(width: 10, height: 10).padding(.top, 5)
                            Text("\(p.name): \(p.text)").font(.subheadline)
                        }
                    }
                }
                Section("Synchronisation") {
                    Button {
                        Task { await sync.sync() }
                    } label: {
                        LabeledContent("Jetzt synchronisieren", value: syncText)
                    }
                    if offenZaehlen > 0 {
                        Text("\(offenZaehlen) \(offenZaehlen == 1 ? "Eintrag" : "Einträge") noch nicht hochgeladen")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                }
            } else {
                Section {
                    TextField("Dein Name", text: $konto.lokalerName)
                    NavigationLink {
                        LoginView()
                    } label: {
                        Label("Mit Server verbinden", systemImage: "icloud.and.arrow.up")
                    }
                } header: {
                    Text("Nur auf diesem iPhone")
                } footer: {
                    Text("Ohne Server bleiben alle Fahrten auf diesem Gerät. Nach dem Verbinden werden sie dem Konto zugeordnet und mit der Familie geteilt.")
                }
            }

            Section {
                Stepper("Nach \(konto.autoStopMinuten) min Stillstand", value: $konto.autoStopMinuten, in: 3...30)
                Toggle("Beim Kurzbefehl vorläufig aufzeichnen", isOn: $konto.autoVorlaeufig)
            } header: {
                Text("Automatisches Fahrtende")
            } footer: {
                Text("Gilt ab der nächsten Fahrt. Das Fahrtende wird auf den Beginn des Stillstands gesetzt. Vorläufig aufzeichnen: Die Bluetooth-Automation („Fragen: Fährst du?“) startet gleich eine vorläufige Fahrt; sie zählt erst nach „Ich fahre“, „Nein“ löscht sie.")
            }

            Section("Fahrzeuge") {
                ForEach(fahrzeuge) { fz in
                    Button {
                        bearbeiten = fz
                    } label: {
                        HStack {
                            Image(systemName: "car.fill").foregroundStyle(Color(hex: fz.farbe))
                            VStack(alignment: .leading) {
                                Text(fz.name).foregroundStyle(.primary)
                                Text(F.kmStand(fz.kmStand) + (fz.preisProKm > 0 ? " · \(F.zahl(fz.preisProKm, 2)) €/km fest" : ""))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            Section {
                Toggle("Test-Modus", isOn: $konto.testModus)
                if konto.testModus {
                    Picker("Aufnahme-Rate", selection: $konto.testAufnahmeMeter) {
                        Text("Normal").tag(0)
                        ForEach([10, 25, 50, 100], id: \.self) { Text("\($0) m").tag($0) }
                    }
                }
                Button("Alle Test-Fahrten löschen", role: .destructive) { testLoeschenFragen = true }
                if let testMeldung { Text(testMeldung).font(.caption).foregroundStyle(.secondary) }
            } header: {
                Text("Test-Modus")
            } footer: {
                Text("Neue Fahrten und Tankungen werden als Test markiert: sie zählen nicht in Statistik, km-Stand und Display und bekommen eine Diagnose (Akku, GPS, Hintergrund, Sync). Die Aufnahme-Rate nimmt für die Strecke nur Punkte im eingestellten Abstand.")
            }

            Section {
                Button("Berechtigungen") { einrichtungFertig = false }
                LabeledContent("Standort", value: standortText)
                if recorder.berechtigung != .authorizedAlways {
                    Button("Standort \"Immer\" erlauben") { recorder.immerErfragen() }
                }
                Button("iOS-Einstellungen öffnen") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            } header: {
                Text("Hintergrund")
            } footer: {
                Text("Mit \"Immer\" können Widget, Kontrollzentrum und Kurzbefehle eine Fahrt auch ohne geöffnete App starten. Für den Start per Bluetooth: Kurzbefehle > Automation > Bluetooth > Auto wählen > „Sofort ausführen“ > Aktion „Fragen: Fährst du?“ (fragt erst, startet nie still) oder „Fahrt starten“.")
            }

            Section("Hilfe") {
                ForEach(Fehlertext.hilfe, id: \.0) { frage, antwort in
                    DisclosureGroup(frage) { Text(antwort).font(.callout).foregroundStyle(.secondary) }
                }
            }

            Section {
                LabeledContent("Installiert", value: "Version \(UpdatePruefer.installierteVersion)")
                Button("Nach Updates suchen") { Task { await UpdatePruefer.shared.pruefen(erzwingen: true) } }
                if let neu = UpdatePruefer.shared.neu {
                    Button("Neue Version \(neu.version): SideStore öffnen") { UpdatePruefer.shared.sideStoreOeffnen() }
                } else if let m = UpdatePruefer.shared.meldung {
                    Text(m).foregroundStyle(.secondary)
                }
            } header: {
                Text("App")
            } footer: {
                Text("Updates kommen über SideStore. LocalDevVPN muss dabei eingeschaltet sein. Alle Fahrten bleiben beim Aktualisieren erhalten.")
            }
        }
        .alert("Problem melden", isPresented: $problemZeigen) {
            TextField("Was ist passiert?", text: $problemText)
            Button("Senden") { Task { problemMeldung = await problemSenden(problemText) } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Der Admin sieht die Meldung mit App-Version und Gerät.")
        }
        .sheet(isPresented: $einladungZeigen) {
            VStack(spacing: 14) {
                Text("Familienmitglied einladen").font(.title2.bold())
                if let t = einladungText {
                    if t.isEmpty {
                        Text("Einladung ging nicht. Mit dem Heim-Server verbunden?")
                    } else {
                        QrBild(text: t)
                        let c = Verbinden.lesen(t)?.einladung ?? ""
                        Text("Im Login „QR-Code scannen“ oder den Code eintippen:").font(.footnote)
                        Text(stride(from: 0, to: c.count, by: 4).map { i in
                            String(c[c.index(c.startIndex, offsetBy: i)..<c.index(c.startIndex, offsetBy: min(i + 4, c.count))])
                        }.joined(separator: "-")).font(.title.monospaced())
                        Text("Gilt 24 Stunden und nur einmal.").font(.footnote).foregroundStyle(.secondary)
                    }
                } else {
                    ProgressView()
                }
                Button("Fertig") { einladungZeigen = false }.buttonStyle(.borderedProminent)
            }
            .padding()
            .presentationDetents([.medium, .large])
        }
        .confirmationDialog("Abmelden?", isPresented: $abmeldenFragen, titleVisibility: .visible) {
            if offeneEintraege > 0 {
                Button("Erst synchronisieren") { Task { await sync.sync() } }
                Button("Trotzdem abmelden", role: .destructive) { konto.abmelden() }
            } else {
                Button("Abmelden", role: .destructive) { konto.abmelden() }
            }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text(offeneEintraege > 0
                 ? "\(offeneEintraege) \(offeneEintraege == 1 ? "Eintrag ist" : "Einträge sind") noch nicht auf dem Server. Sie bleiben auf dem iPhone und werden nach der nächsten Anmeldung hochgeladen."
                 : "Alles ist auf dem Server. Zum Anmelden brauchst du danach wieder E-Mail und Passwort.")
        }
        .confirmationDialog("Alle Test-Fahrten löschen?", isPresented: $testLoeschenFragen, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) { testLoeschen() }
        } message: {
            Text("Deine Test-Fahrten und Test-Tankungen werden gelöscht. Normale Einträge bleiben.")
        }
        .navigationTitle("Einstellungen")
        .sheet(isPresented: $passwortZeigen) { PasswortView() }
        .sheet(item: $bearbeiten) { fz in
            FahrzeugBearbeiten(fahrzeug: fz)
        }
        .alert("Server-Adressen", isPresented: $adressenZeigen) {
            TextField("Heimnetz, z. B. http://fahrtenbuch.local:8090", text: $hauptEingabe)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Tailscale (optional)", text: $tailscaleEingabe)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Abbrechen", role: .cancel) {}
            Button("Speichern") {
                let h = Konto.normalisieren(hauptEingabe)
                if !h.isEmpty { konto.serverUrlAendern(h) }
                konto.tailscaleUrl = Konto.normalisieren(tailscaleEingabe)
                Task { await sync.sync() }
            }
        } message: {
            Text("Zuerst wird die Heimnetz-Adresse versucht, danach Tailscale.")
        }
    }

    /// Eigene Test-Einträge weich löschen (geloescht), damit kein Gerät sie wieder hochlädt.
    private func testLoeschen() {
        let ich = konto.userId
        let fahrten = (try? context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate {
            $0.test && !$0.geloescht && $0.ende != nil && $0.fahrerId == ich
        }))) ?? []
        let tank = (try? context.fetch(FetchDescriptor<Tankvorgang>(predicate: #Predicate {
            $0.test && !$0.geloescht && $0.fahrerId == ich
        }))) ?? []
        for f in fahrten { f.geloescht = true; f.dirty = true }
        for t in tank { t.geloescht = true; t.dirty = true }
        try? context.save()
        let n = fahrten.count + tank.count
        testMeldung = n == 0 ? "Keine Test-Einträge vorhanden" : "\(n) Test-Einträge gelöscht"
        Task { await sync.sync() }
    }

    private func adressenText(_ a: Anmeldung) -> String {
        guard let aktiv = sync.aktiveAdresse else {
            return sync.status == .offline ? "nicht erreichbar" : a.serverUrl
        }
        if aktiv == Konto.normalisieren(a.serverUrl) { return "Heimnetz" }
        if aktiv == Konto.normalisieren(konto.tailscaleUrl) { return "Tailscale" }
        return aktiv
    }

    /// Noch nicht hochgeladene Fahrten und Tankungen (Nachtplan 5c: sichtbar, was fehlt).
    private var offenZaehlen: Int {
        _ = sync.status  // bei jedem Sync neu zählen
        let f = (try? context.fetchCount(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.dirty }))) ?? 0
        let t = (try? context.fetchCount(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.dirty }))) ?? 0
        return f + t
    }

    private func problemSenden(_ text: String) async -> String {
        guard !text.isEmpty, let a = konto.anmeldung,
              let url = await PocketBase.ersteErreichbare([a.serverUrl, konto.tailscaleUrl]) else { return "Senden ging nicht." }
        do {
            try await PocketBase.bericht(server: url, token: a.token, userId: a.userId, text: text,
                                         version: UpdatePruefer.installierteVersion,
                                         geraet: "\(UIDevice.current.model), iOS \(UIDevice.current.systemVersion)")
            return "Gesendet, danke!"
        } catch {
            return "Senden ging nicht."
        }
    }

    private func einladungErstellen() async -> String? {
        guard let a = konto.anmeldung,
              let url = await PocketBase.ersteErreichbare([a.serverUrl, konto.tailscaleUrl]),
              let code = try? await PocketBase.einladung(server: url, token: a.token) else { return nil }
        let dl = URL(string: a.serverUrl).flatMap { $0.host }.map { "http://\($0):8091" } ?? ""
        return Verbinden.bauen(.init(heim: a.serverUrl, tail: konto.tailscaleUrl, dl: dl, einladung: code))
    }

    private func verbindungPruefen() async -> [(name: String, ok: Bool?, text: String)] {
        var aus: [(name: String, ok: Bool?, text: String)] = []
        let heim = Konto.normalisieren(konto.anmeldung?.serverUrl ?? konto.letzteServerUrl)
        if heim.isEmpty {
            aus.append(("Heimnetz", nil, "keine Adresse. Unter Server-Adressen eintragen."))
        } else {
            let ok = await PocketBase.erreichbar(heim)
            aus.append(("Heimnetz", ok, ok ? heim : "\(heim) antwortet nicht. Im WLAN zuhause? Pi an?"))
        }
        let ts = Konto.normalisieren(konto.tailscaleUrl)
        if ts.isEmpty {
            aus.append(("Tailscale", nil, "nicht eingetragen. Für unterwegs Tailscale-App installieren."))
        } else {
            let ok = await PocketBase.erreichbar(ts)
            aus.append(("Tailscale", ok, ok ? ts : "\(ts) antwortet nicht. Tailscale-App öffnen und verbinden."))
        }
        let host = URL(string: heim)?.host ?? "fahrtenbuch.local"
        var dlOk = false
        if let u = URL(string: "http://\(host):8091/apps.json"), let (_, r) = try? await URLSession.shared.data(from: u) {
            dlOk = (r as? HTTPURLResponse)?.statusCode == 200
        }
        aus.append(("SideStore-Quelle", dlOk, dlOk ? "http://\(host):8091/apps.json" : "Port 8091 antwortet nicht, Updates gehen dann nicht."))
        let standort = CLLocationManager().authorizationStatus
        aus.append(("Standort", standort == .authorizedAlways,
                    standort == .authorizedAlways ? "immer erlaubt" : "Einstellungen → Fahrtenbuch → Standort → „Immer“."))
        return aus
    }

    private var syncText: String {
        switch sync.status {
        case .laeuft: return "läuft ..."
        case .fehler(let t): return t
        case .abgelaufen: return "Anmeldung abgelaufen. Oben auf den roten Hinweis tippen, um dich neu anzumelden."
        case .offline: return "Server nicht erreichbar"
        case .bereit:
            guard let d = konto.letzterSync else { return "noch nie" }
            return "\(F.datum(d)), \(F.uhrzeit(d))"
        }
    }

    private var standortText: String {
        switch recorder.berechtigung {
        case .authorizedAlways: return "Immer"
        case .authorizedWhenInUse: return "Beim Verwenden"
        case .denied, .restricted: return "Verboten"
        default: return "Nicht gefragt"
        }
    }
}

struct FahrzeugBearbeiten: View {
    @Bindable var fahrzeug: Fahrzeug
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var km = ""
    @State private var preis = ""
    @State private var antrieb = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Antrieb") {
                    Picker("Antrieb", selection: $antrieb) {
                        ForEach(Antrieb.arten, id: \.wert) { Text($0.text).tag($0.wert) }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Kilometerstand") {
                    TextField("Kilometerstand", text: $km).keyboardType(.numberPad)
                }
                Section {
                    TextField("Fester Preis pro km (optional)", text: $preis).keyboardType(.decimalPad)
                } footer: {
                    Text(antrieb == "elektro" ? "Leer lassen, dann wird der Preis aus den Ladedaten berechnet."
                                              : "Leer lassen, dann wird der Preis aus den Tankdaten berechnet.")
                }
            }
            .navigationTitle(fahrzeug.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") {
                        fahrzeug.kmStand = F.zahl(aus: km.replacingOccurrences(of: ".", with: "")) ?? fahrzeug.kmStand
                        fahrzeug.preisProKm = F.zahl(aus: preis) ?? 0
                        fahrzeug.kraftstoff = antrieb
                        fahrzeug.dirty = true
                        try? context.save()
                        Task { await SyncManager.shared.sync() }
                        dismiss()
                    }
                }
            }
            .onAppear {
                km = F.zahl(fahrzeug.kmStand, 0)
                preis = fahrzeug.preisProKm > 0 ? F.zahl(fahrzeug.preisProKm, 2) : ""
                antrieb = fahrzeug.kraftstoff
            }
        }
        .presentationDetents([.medium, .large])
    }
}
