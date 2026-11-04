import AppIntents
import Foundation

// Die Intents liegen in App und Widget-Erweiterung. Als LiveActivityIntent laufen sie
// immer im Prozess der App (die App wird dafür im Hintergrund gestartet), deshalb
// greift nur die App-Version wirklich auf die Aufzeichnung zu.

struct FahrtStartenIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Fahrt starten"
    static let description = IntentDescription("Startet die Aufzeichnung mit dem zuletzt gewählten Auto.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET
        try await FahrtRecorder.shared.starten()
        #endif
        return .result()
    }
}

struct FahrtBeendenIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Fahrt beenden"
    static let description = IntentDescription("Beendet die laufende Fahrt.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET
        await FahrtRecorder.shared.beenden()
        #endif
        return .result()
    }
}

/// Roadmap Nr. 6: iOS meldet Apps kein Bluetooth-Ereignis. Die Kurzbefehl-Automation "Bluetooth
/// verbunden" ruft deshalb diesen Intent auf, der nur fragt ("Fährst du?") und nie still startet.
struct FahrtFragenIntent: AppIntent {
    static let title: LocalizedStringResource = "Fragen: Fährst du?"
    static let description = IntentDescription("Zeigt eine Mitteilung mit „Ich fahre“ und „Nein“. Für die Automation beim Verbinden mit dem Auto-Bluetooth.")
    static let openAppWhenRun = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET
        await FahrtFrage.zeigen()
        #endif
        return .result()
    }
}

/// Für den Schalter im Kontrollzentrum.
struct FahrtUmschaltenIntent: SetValueIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Fahrt umschalten"

    @Parameter(title: "Fahrt läuft")
    var value: Bool

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET
        if value {
            try await FahrtRecorder.shared.starten()
        } else {
            await FahrtRecorder.shared.beenden()
        }
        #endif
        return .result()
    }
}
