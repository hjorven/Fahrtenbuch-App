import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

// Widget, Control und Live Activity liegen bewusst in EINER Erweiterung:
// mit kostenloser Apple-ID braucht jede Erweiterung eine eigene App-ID (max. 10 pro Woche).

@main
struct FahrtenbuchWidgets: WidgetBundle {
    var body: some Widget {
        FahrtWidget()
        FahrtControl()
        FahrtLiveActivity()
    }
}

// MARK: - Homescreen-Widget

struct FahrtEintrag: TimelineEntry {
    let date: Date
    let laeuft: Bool
    let start: Date?
    let km: Double
    let fahrzeug: String?
    var monat: String? = nil
}

struct FahrtProvider: TimelineProvider {
    func placeholder(in context: Context) -> FahrtEintrag {
        FahrtEintrag(date: .now, laeuft: false, start: nil, km: 0, fahrzeug: "Bus")
    }

    func getSnapshot(in context: Context, completion: @escaping (FahrtEintrag) -> Void) {
        completion(aktuell())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<FahrtEintrag>) -> Void) {
        completion(Timeline(entries: [aktuell()], policy: .never))
    }

    private func aktuell() -> FahrtEintrag {
        FahrtEintrag(date: .now, laeuft: GeteilterStatus.laeuft, start: GeteilterStatus.start,
                     km: GeteilterStatus.km, fahrzeug: GeteilterStatus.fahrzeugName, monat: GeteilterStatus.monatsZeile)
    }
}

struct FahrtWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "FahrtWidget", provider: FahrtProvider()) { e in
            FahrtWidgetAnsicht(e: e)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Fahrt")
        .description("Fahrt mit einem Tipp starten und beenden.")
        .supportedFamilies([.systemSmall, .accessoryRectangular])
    }
}

struct FahrtWidgetAnsicht: View {
    let e: FahrtEintrag
    @Environment(\.widgetFamily) private var familie

    var body: some View {
        if familie == .accessoryRectangular {
            HStack {
                Image(systemName: e.laeuft ? "car.fill" : "car")
                VStack(alignment: .leading) {
                    Text(e.fahrzeug ?? "Fahrtenbuch").font(.headline).lineLimit(1)
                    if e.laeuft, let s = e.start { Text(s, style: .timer) } else { Text("bereit") }
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: "car.fill").foregroundStyle(.tint)
                    Text(e.fahrzeug ?? "Kein Auto").font(.headline).lineLimit(1)
                }
                Spacer(minLength: 0)
                if e.laeuft, let s = e.start {
                    Text(s, style: .timer).font(.title2.monospacedDigit().bold())
                    Button(intent: FahrtBeendenIntent()) {
                        Label("Beenden", systemImage: "stop.fill").frame(maxWidth: .infinity)
                    }
                    .tint(.red)
                } else {
                    Text(e.monat ?? "Keine Fahrt").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    Button(intent: FahrtStartenIntent()) {
                        Label("Starten", systemImage: "play.fill").frame(maxWidth: .infinity)
                    }
                    .disabled(e.fahrzeug == nil)
                }
            }
        }
    }
}

// MARK: - Kontrollzentrum / Sperrbildschirm / Action-Taste

struct FahrtControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hjorven.fahrtenbuch.control") {
            ControlWidgetToggle("Fahrt", isOn: GeteilterStatus.laeuft, action: FahrtUmschaltenIntent()) { an in
                Label(an ? "Fährt" : "Starten", systemImage: an ? "car.fill" : "car")
            }
        }
        .displayName("Fahrt starten")
        .description("Startet oder beendet die Fahrtaufzeichnung.")
    }
}

// MARK: - Live Activity

struct FahrtLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FahrtAttributes.self) { ctx in
            HStack(spacing: 16) {
                Image(systemName: "car.fill").font(.title2).foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ctx.attributes.fahrzeugName).font(.headline)
                    Text("\(kmText(ctx.state.km)) · \(Int(ctx.state.kmh.rounded())) km/h").font(.subheadline)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 6) {
                    Text(ctx.attributes.start, style: .timer).font(.title3.monospacedDigit().bold())
                        .multilineTextAlignment(.trailing)
                    Button(intent: FahrtBeendenIntent()) {
                        Label("Beenden", systemImage: "stop.fill").font(.caption.bold())
                    }
                    .tint(.red)
                }
            }
            .padding()
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(ctx.attributes.fahrzeugName, systemImage: "car.fill").font(.caption)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(ctx.attributes.start, style: .timer).monospacedDigit().multilineTextAlignment(.trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Text("\(kmText(ctx.state.km)) · \(Int(ctx.state.kmh.rounded())) km/h")
                        Spacer()
                        Button(intent: FahrtBeendenIntent()) {
                            Label("Beenden", systemImage: "stop.fill")
                        }
                        .tint(.red)
                    }
                }
            } compactLeading: {
                Image(systemName: "car.fill")
            } compactTrailing: {
                Text(kmText(ctx.state.km)).monospacedDigit()
            } minimal: {
                Image(systemName: "car.fill")
            }
        }
    }
}

private func kmText(_ km: Double) -> String {
    km.formatted(.number.precision(.fractionLength(1)).locale(Locale(identifier: "de_DE"))) + " km"
}
