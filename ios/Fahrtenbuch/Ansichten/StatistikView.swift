import Charts
import SwiftData
import SwiftUI

struct StatistikView: View {
    @Query(sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    // Test-Einträge zählen nicht mit.
    @Query(filter: #Predicate<Fahrt> { !$0.geloescht && !$0.test && !$0.vorlaeufig }) private var fahrten: [Fahrt]
    @Query(filter: #Predicate<Tankvorgang> { !$0.geloescht && !$0.test }) private var tankungen: [Tankvorgang]
    @Query(filter: #Predicate<Ausgleich> { !$0.geloescht }) private var ausgleiche: [Ausgleich]
    @Query private var benutzer: [Benutzer]
    @Environment(\.modelContext) private var context
    @State private var versatz = 0
    @State private var jahrAnsicht = false

    var body: some View {
        let (von, bis) = grenzen()
        let kpk = kostenProKm()
        let n = namen(benutzer)

        List {
            Section {
                Picker("Ansicht", selection: $jahrAnsicht) {
                    Text("Monat").tag(false)
                    Text("Jahr").tag(true)
                }
                .pickerStyle(.segmented)
                HStack {
                    Button { versatz -= 1 } label: { Image(systemName: "chevron.left") }
                    Spacer()
                    Text(jahrAnsicht ? von.formatted(.dateTime.year().locale(F.de)) : F.monat(von)).font(.headline)
                    Spacer()
                    Button { versatz += 1 } label: { Image(systemName: "chevron.right") }
                        .disabled(versatz >= 0)
                }
                .buttonStyle(.borderless)
            }
            .onChange(of: jahrAnsicht) { versatz = 0 }

            if jahrAnsicht {
                jahrDiagramm(von)
            }

            ForEach(fahrzeuge.filter(\.aktiv)) { fz in
                autoSection(fz, von: von, bis: bis, kpk: kpk)
            }

            if !jahrAnsicht {
                abrechnungSection(von: von, bis: bis, kpk: kpk, n: n)
            }

            if let t = Statistik.tachoAbweichung(fahrten) {
                Section {
                    LabeledContent("GPS gegenüber Tacho", value: "\(t.prozent >= 0 ? "+" : "")\(F.zahl(t.prozent)) %")
                } header: {
                    Text("Tacho-Abgleich")
                } footer: {
                    Text("Aus \(t.fahrten) Fahrten mit eingetragenem Tacho-Stand: GPS \(F.km(t.gpsKm)), Tacho \(F.km(t.tachoKm)).")
                }
            }
        }
        .navigationTitle("Statistik")
        .toolbar { StandardToolbar() }
    }

    // MARK: Jahr

    private struct MonatsWert: Identifiable {
        let monat: Int
        let km: Double
        let auto: String
        var id: String { "\(auto)-\(monat)" }
    }

    @ViewBuilder
    private func jahrDiagramm(_ von: Date) -> some View {
        let jahr = Calendar.current.component(.year, from: von)
        let werte: [MonatsWert] = fahrzeuge.filter(\.aktiv).flatMap { fz in
            Statistik.kmProMonat(fahrten, jahr: jahr, fahrzeugId: fz.id).enumerated().map {
                MonatsWert(monat: $0.offset + 1, km: $0.element, auto: fz.name)
            }
        }
        let summe = werte.reduce(0) { $0 + $1.km }
        Section {
            Chart(werte) { w in
                BarMark(x: .value("Monat", monatsKuerzel(w.monat)), y: .value("km", w.km))
                    .foregroundStyle(by: .value("Auto", w.auto))
            }
            .chartXScale(domain: (1...12).map(monatsKuerzel))
            .chartForegroundStyleScale(domain: fahrzeuge.filter(\.aktiv).map(\.name),
                                       range: fahrzeuge.filter(\.aktiv).map { Color(hex: $0.farbe) })
            .frame(height: 220)
            LabeledContent("Gesamt im Jahr", value: F.km(summe))
        } header: {
            Text("Kilometer pro Monat")
        }
    }

    private func monatsKuerzel(_ m: Int) -> String {
        var c = DateComponents()
        c.year = 2000
        c.month = m
        c.day = 1
        return Calendar.current.date(from: c)!.formatted(.dateTime.month(.abbreviated).locale(F.de))
    }

    // MARK: Auto

    @ViewBuilder
    private func autoSection(_ fz: Fahrzeug, von: Date, bis: Date, kpk: [String: Double]) -> some View {
        let eigene = tankungen.filter { $0.fahrzeugId == fz.id }
        let imZeitraum = eigene.filter { $0.datum >= von && $0.datum < bis }
        let verbrauch = Statistik.verbrauch(eigene, elektrisch: fz.elektrisch)
        let km = fahrten.filter { $0.fahrzeugId == fz.id && $0.ende != nil && $0.start >= von && $0.start < bis }
            .reduce(0.0) { $0 + $1.km }
        let kosten = imZeitraum.reduce(0.0) { $0 + $1.kosten }
        let verlauf = Statistik.verbrauchVerlauf(eigene, elektrisch: fz.elektrisch)
        let zeitraumText = jahrAnsicht ? "im Jahr" : "im Monat"
        Section {
            LabeledContent("Gefahren \(zeitraumText)", value: F.km(km))
            LabeledContent(fz.elektrisch ? "Ladekosten \(zeitraumText)" : "Getankt \(zeitraumText)", value: F.euro(kosten))
            if fz.elektrisch {
                let zuhause = imZeitraum.filter { $0.ladeort == "zuhause" }.reduce(0.0) { $0 + $1.liter }
                let oeffentlich = imZeitraum.filter { $0.ladeort != "zuhause" }.reduce(0.0) { $0 + $1.liter }
                LabeledContent("Zuhause geladen (kostenlos)", value: "\(F.zahl(zuhause)) kWh")
                LabeledContent("Öffentlich geladen", value: "\(F.zahl(oeffentlich)) kWh")
                if zuhause + oeffentlich > 0 {
                    LabeledContent("Anteil zuhause", value: "\(F.zahl(zuhause / (zuhause + oeffentlich) * 100, 0)) %")
                }
            }
            LabeledContent("Verbrauch", value: verbrauch.map { "\(F.zahl($0.literPro100km)) \(fz.einheit)/100 km" }
                           ?? (fz.elektrisch ? "zu wenig Ladedaten" : "zu wenig Tankdaten"))
            LabeledContent(fz.preisProKm > 0 ? "Preis pro km (fest)" : "Kosten pro km",
                           value: kpk[fz.id].map { "\(F.zahl($0, 3)) €" } ?? "–")
            if verlauf.count >= 2 {
                Chart(verlauf) { p in
                    LineMark(x: .value("Datum", p.datum), y: .value(fz.einheit, p.wert))
                        .foregroundStyle(Color(hex: fz.farbe))
                    PointMark(x: .value("Datum", p.datum), y: .value(fz.einheit, p.wert))
                        .foregroundStyle(Color(hex: fz.farbe))
                }
                .chartYAxisLabel("\(fz.einheit)/100 km")
                .frame(height: 140)
            }
        } header: {
            HStack(spacing: 6) {
                Circle().fill(Color(hex: fz.farbe)).frame(width: 8, height: 8)
                Text(fz.name)
            }
        }
    }

    // MARK: Abrechnung

    @ViewBuilder
    private func abrechnungSection(von: Date, bis: Date, kpk: [String: Double], n: [String: String]) -> some View {
        let monat = Statistik.zeitraum(von)
        let ausgleichMonat = ausgleiche.filter { $0.zeitraum == monat }
        let abrechnung = Statistik.abrechnung(fahrten: fahrten, tankungen: tankungen, kostenProKm: kpk, von: von, bis: bis,
                                              ausgleiche: ausgleichMonat)
        let offen = Statistik.ueberweisungen(abrechnung)
        Section {
            if abrechnung.isEmpty {
                Text("Keine Fahrten in diesem Monat").foregroundStyle(.secondary)
            }
            ForEach(abrechnung) { s in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(n[s.fahrerId] ?? "Fahrer").font(.headline)
                        Text(detail(s)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(saldoText(s.saldo))
                        .font(.subheadline.bold())
                        .foregroundStyle(s.saldo < -0.005 ? Color.red : Color.accentColor)
                }
            }
        } header: {
            Text("Abrechnung")
        } footer: {
            Text("Anteil = eigene km mal Kosten pro km des Autos. Die Kosten pro km kommen aus den letzten Tank- oder Ladevorgängen oder aus dem festen Preis in den Einstellungen. Laden zuhause zählt als kostenlos.")
        }

        if !abrechnung.isEmpty {
            Section {
                if offen.isEmpty {
                    Label("Alles ausgeglichen", systemImage: "checkmark.circle").foregroundStyle(.green)
                }
                ForEach(offen) { u in
                    HStack {
                        Text("\(n[u.von] ?? "Fahrer") an \(n[u.an] ?? "Fahrer"): \(F.euro(u.betrag))")
                        Spacer()
                        if u.von == Konto.shared.userId || u.an == Konto.shared.userId {
                            Button("Bezahlt") { eintragen(u, monat) }
                                .buttonStyle(.glass)
                        }
                    }
                }
                ForEach(ausgleichMonat) { a in
                    Text("\(n[a.von] ?? "Fahrer") hat \(n[a.an] ?? "Fahrer") \(F.euro(a.betrag)) gezahlt (\(F.datum(a.datum)))")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .swipeActions {
                            if a.von == Konto.shared.userId || a.an == Konto.shared.userId {
                                Button("Löschen", role: .destructive) { loeschen(a) }
                            }
                        }
                }
            } header: {
                Text("Ausgleich")
            } footer: {
                Text("\"Bezahlt\" trägt die Überweisung ein, danach steht der Monat für beide auf ausgeglichen.")
            }
        }
    }

    private func detail(_ s: Statistik.Saldo) -> String {
        var t = "\(F.km(s.km)) · Anteil \(F.euro(s.anteil)) · bezahlt \(F.euro(s.bezahlt))"
        if abs(s.ausgleich) > 0.005 { t += " · Ausgleich \(s.ausgleich > 0 ? "+" : "")\(F.euro(s.ausgleich))" }
        return t
    }

    private func eintragen(_ u: Statistik.Ueberweisung, _ monat: String) {
        context.insert(Ausgleich(von: u.von, an: u.an, betrag: u.betrag, zeitraum: monat))
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }

    private func loeschen(_ a: Ausgleich) {
        a.geloescht = true
        a.dirty = true
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }

    private func kostenProKm() -> [String: Double] {
        var m: [String: Double] = [:]
        for fz in fahrzeuge {
            if let k = Statistik.kostenProKm(fz, tankungen.filter { $0.fahrzeugId == fz.id }) { m[fz.id] = k }
        }
        return m
    }

    private func saldoText(_ s: Double) -> String {
        if s > 0.005 { return "bekommt \(F.euro(s))" }
        if s < -0.005 { return "zahlt \(F.euro(-s))" }
        return "ausgeglichen"
    }

    private func grenzen() -> (Date, Date) {
        let cal = Calendar.current
        if jahrAnsicht {
            let anfang = cal.date(from: cal.dateComponents([.year], from: Date()))!
            let von = cal.date(byAdding: .year, value: versatz, to: anfang)!
            return (von, cal.date(byAdding: .year, value: 1, to: von)!)
        }
        let anfang = cal.date(from: cal.dateComponents([.year, .month], from: Date()))!
        let von = cal.date(byAdding: .month, value: versatz, to: anfang)!
        let bis = cal.date(byAdding: .month, value: 1, to: von)!
        return (von, bis)
    }
}
