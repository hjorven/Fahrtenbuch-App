import ActivityKit
import Foundation

/// Daten der Live Activity auf dem Sperrbildschirm und in der Dynamic Island.
struct FahrtAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var km: Double
        var kmh: Double
        var stehtSeit: Date?
    }

    var fahrzeugName: String
    var start: Date
}
