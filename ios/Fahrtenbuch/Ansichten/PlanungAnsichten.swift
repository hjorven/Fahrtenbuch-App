import SwiftUI
import SwiftData

/// Termine (Roadmap Nr. 16) und Reservierungen (Nr. 17). Liegen nur auf dem Server, ohne Verbindung
/// steht ein Hinweis da. Das Display erinnert über die Bridge.
struct PlanungAbschnitte: View {
    @Query(sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    @State private var termine: [JSON] = []
    @State private var reservierungen: [JSON] = []
    @State private var fehler: String?
    @State private var neuerTermin = false
    @State private var neueReservierung = false
    @State private var art = "tuev"
    @State private var autoId = ""
    @State private var datum = Date()
    @State private var von = Date()
    @State private var bis = Date().addingTimeInterval(3 * 3600)
    @State private var kosten: [JSON] = []
    @State private var neueKosten = false
    @State private var kostenArt = "werkstatt"
    @State private var betrag = ""
    @State private var notiz = ""

    static let kostenArten = [("werkstatt", "Werkstatt"), ("versicherung", "Versicherung"), ("steuer", "Steuer"),
                              ("waesche", "Wäsche"), ("parken", "Parken"), ("sonstiges", "Sonstiges")]

    /// Betrag aus der Eingabe ("189,90", "1.200,50 €", "45"), nil wenn unsinnig (wie der Server: 0 bis 50 000).
    nonisolated static func betragLesen(_ text: String) -> Double? {
        var t = text.replacingOccurrences(of: "€", with: "").trimmingCharacters(in: .whitespaces)
        if t.contains(",") { t = t.replacingOccurrences(of: ".", with: "").replacingOccurrences(of: ",", with: ".") }
        guard let v = Double(t), v > 0, v <= 50_000 else { return nil }
        return (v * 100).rounded() / 100
    }

    private static let arten = [("tuev", "TÜV"), ("inspektion", "Inspektion"), ("reifen", "Reifen"), ("versicherung", "Versicherung"), ("sonstiges", "Sonstiges")]

    private func auto(_ id: String) -> String { fahrzeuge.first { $0.id == id }?.name ?? "Auto" }

    var body: some View {
        Section("Auto reservieren") {
            if let fehler { Text(fehler).foregroundStyle(.red).font(.footnote) }
            ForEach(reservierungen.indices, id: \.self) { i in
                let r = reservierungen[i]
                let v = PbDatum.datum(r.s("von")) ?? Date(), b = PbDatum.datum(r.s("bis")) ?? Date()
                VStack(alignment: .leading) {
                    Text(auto(r.s("fahrzeug")))
                    Text("\(F.datum(v)) \(F.uhrzeit(v))–\(F.uhrzeit(b))").font(.footnote).foregroundStyle(.secondary)
                }
            }
            Button("Auto reservieren") { autoId = fahrzeuge.first?.id ?? ""; neueReservierung = true }
        }
        Section("Termine (TÜV, Inspektion …)") {
            ForEach(termine.indices, id: \.self) { i in
                let t = termine[i]
                let f = PbDatum.datum(t.s("faellig")) ?? Date()
                let tage = Int(f.timeIntervalSinceNow / 86400)
                HStack {
                    VStack(alignment: .leading) {
                        Text("\(Self.arten.first { $0.0 == t.s("art") }?.1 ?? "Termin") · \(auto(t.s("fahrzeug")))")
                        Text("\(F.datum(f)) · \(tage < 0 ? "seit \(-tage) Tagen fällig" : "in \(tage) Tagen")")
                            .font(.footnote).foregroundStyle(tage <= 30 ? .red : .secondary)
                    }
                    Spacer()
                    Button("Erledigt") { Task { await aendern("termin", t.s("id"), ["erledigt": true]) } }
                }
            }
            Button("Termin eintragen") { autoId = fahrzeuge.first?.id ?? ""; neuerTermin = true }
        }
        Section("Sonstige Kosten (Werkstatt, Parken …)") {
            ForEach(kosten.prefix(10).indices, id: \.self) { i in
                let k = kosten[i]
                HStack {
                    VStack(alignment: .leading) {
                        Text("\(k.b("test") ? "TEST · " : "")\(Self.kostenArten.first { $0.0 == k.s("art") }?.1 ?? "Kosten") · \(auto(k.s("fahrzeug")))")
                        Text(F.datum(PbDatum.datum(k.s("datum")) ?? Date()) + (k.s("notiz").isEmpty ? "" : " · \(k.s("notiz"))"))
                            .font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Text(F.euro(k.d("betrag")))
                }
                .swipeActions {
                    Button("Löschen", role: .destructive) { Task { await aendern("kosten", k.s("id"), ["geloescht": true]) } }
                }
            }
            Button("Kosten eintragen") { autoId = fahrzeuge.first?.id ?? ""; betrag = ""; notiz = ""; datum = Date(); neueKosten = true }
        }
        .task { await laden() }
        .sheet(isPresented: $neueKosten) {
            NavigationStack {
                Form {
                    Picker("Auto", selection: $autoId) { ForEach(fahrzeuge) { Text($0.name).tag($0.id) } }
                    Picker("Art", selection: $kostenArt) { ForEach(Self.kostenArten, id: \.0) { Text($0.1).tag($0.0) } }
                    TextField("Betrag in €", text: $betrag).keyboardType(.decimalPad)
                    if !betrag.isEmpty && Self.betragLesen(betrag) == nil {
                        Text("Bitte einen Betrag zwischen 0 und 50 000 € eingeben").font(.footnote).foregroundStyle(.red)
                    }
                    TextField("Notiz (optional)", text: $notiz)
                    DatePicker("Am", selection: $datum, in: ...Date(), displayedComponents: .date)
                }
                .navigationTitle("Kosten eintragen")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { neueKosten = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Sichern") {
                            neueKosten = false
                            let ich = Konto.shared.anmeldung?.userId ?? ""
                            Task {
                                await anlegen("kosten", ["fahrzeug": autoId, "art": kostenArt, "betrag": Self.betragLesen(betrag) ?? 0,
                                                         "datum": PbDatum.text(datum), "notiz": String(notiz.prefix(200)),
                                                         "erfasst_von": ich, "bezahlt_von": ich, "test": Konto.shared.testModus])
                            }
                        }
                        .disabled(autoId.isEmpty || Self.betragLesen(betrag) == nil)
                    }
                }
            }
        }
        .sheet(isPresented: $neuerTermin) {
            NavigationStack {
                Form {
                    Picker("Auto", selection: $autoId) { ForEach(fahrzeuge) { Text($0.name).tag($0.id) } }
                    Picker("Art", selection: $art) { ForEach(Self.arten, id: \.0) { Text($0.1).tag($0.0) } }
                    DatePicker("Fällig am", selection: $datum, displayedComponents: .date)
                }
                .navigationTitle("Termin eintragen")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { neuerTermin = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Sichern") {
                            neuerTermin = false
                            Task { await anlegen("termin", ["fahrzeug": autoId, "art": art, "faellig": PbDatum.text(datum)]) }
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $neueReservierung) {
            NavigationStack {
                Form {
                    Picker("Auto", selection: $autoId) { ForEach(fahrzeuge) { Text($0.name).tag($0.id) } }
                    DatePicker("Ab", selection: $von)
                    DatePicker("Bis", selection: $bis)
                }
                .navigationTitle("Auto reservieren")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { neueReservierung = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Reservieren") {
                            neueReservierung = false
                            Task {
                                await anlegen("reservierung", ["fahrzeug": autoId, "wer": Konto.shared.anmeldung?.userId ?? "",
                                                               "von": PbDatum.text(von), "bis": PbDatum.text(bis)])
                            }
                        }
                    }
                }
            }
        }
    }

    private func verbindung() async -> (String, String)? {
        guard let a = Konto.shared.anmeldung,
              let url = await PocketBase.ersteErreichbare([a.serverUrl, Konto.shared.tailscaleUrl]) else { return nil }
        return (url, a.token)
    }

    private func laden() async {
        guard let (url, token) = await verbindung() else { fehler = "Server nicht erreichbar."; return }
        do {
            let jetzt = Date()
            termine = try await PocketBase.liste(server: url, token: token, collection: "termin", seit: "")
                .filter { !$0.b("erledigt") && !$0.b("geloescht") }
                .sorted { $0.s("faellig") < $1.s("faellig") }
            reservierungen = try await PocketBase.liste(server: url, token: token, collection: "reservierung", seit: "")
                .filter { !$0.b("geloescht") && (PbDatum.datum($0.s("bis")) ?? jetzt) > jetzt }
                .sorted { $0.s("von") < $1.s("von") }
            // Kosten gibt es erst ab Server #100; ein älterer Server soll die anderen Listen nicht verhindern.
            kosten = ((try? await PocketBase.liste(server: url, token: token, collection: "kosten", seit: "")) ?? [])
                .filter { !$0.b("geloescht") }
                .sorted { $0.s("datum") > $1.s("datum") }
            fehler = nil
        } catch {
            fehler = "Termine und Reservierungen nicht lesbar (älterer Server?)."
        }
    }

    private func anlegen(_ col: String, _ body: JSON) async {
        guard let (url, token) = await verbindung() else { fehler = "Server nicht erreichbar."; return }
        do {
            try await PocketBase.anlegen(server: url, token: token, collection: col, body: body)
        } catch {
            fehler = Fehlertext.aus(error, "Speichern")  // z. B. "Das Auto ist in der Zeit schon reserviert."
        }
        await laden()
    }

    private func aendern(_ col: String, _ id: String, _ body: JSON) async {
        guard let (url, token) = await verbindung() else { return }
        try? await PocketBase.aendern(server: url, token: token, collection: col, id: id, body: body)
        await laden()
    }
}
