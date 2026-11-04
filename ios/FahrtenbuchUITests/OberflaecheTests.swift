import XCTest

/// Oberflächentest (Roadmap Nr. 20): App frisch starten, Einrichtung überspringen, alle Reiter und
/// die Einstellungen öffnen. Pro Schritt ein Screenshot im Testergebnis (CI: Artefakt).
final class OberflaecheTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    private func bild(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    /// Reiter unter iOS 26 (Tab-API) ist ein Button, je nach Ansicht in einer Tableiste oder nicht.
    private func reiter(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        let inLeiste = app.tabBars.buttons[name]
        return inLeiste.exists ? inLeiste : app.buttons[name].firstMatch
    }

    /// Standort-, Bewegungs- oder Mitteilungs-Abfrage von iOS schließen (im Simulator egal, welche Antwort).
    private func systemDialogSchliessen() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<3 {
            let dialog = sb.alerts.firstMatch
            guard dialog.waitForExistence(timeout: 2) else { return }
            let knoepfe = dialog.buttons
            let erlauben = ["Beim Verwenden der App erlauben", "Allow While Using App", "Erlauben", "Allow", "OK"]
                .map { knoepfe[$0] }.first { $0.exists }
            (erlauben ?? knoepfe.element(boundBy: max(0, knoepfe.count - 1))).tap()
        }
    }

    func testDurchAlleSeiten() {
        let app = XCUIApplication()
        app.launch()
        if app.staticTexts["Willkommen"].waitForExistence(timeout: 8) {
            bild(app, "01_einrichtung")
            // Die Einrichtung fragt gleich nach dem Standort: System-Dialog erst wegtippen,
            // sonst fängt er den Tipp auf "Weiter" ab.
            for _ in 0..<3 {
                systemDialogSchliessen()
                guard let knopf = ["Los geht's", "Weiter"].map({ app.buttons[$0].firstMatch }).first(where: { $0.exists }) else { break }
                var versuche = 0
                while !knopf.isHittable && versuche < 5 { app.swipeUp(); versuche += 1 }
                knopf.tap()
                if !app.staticTexts["Willkommen"].waitForExistence(timeout: 2) { break }
            }
        }
        let fahrt = reiter(app, "Fahrt")
        if !fahrt.waitForExistence(timeout: 10) {
            bild(app, "99_fehler_kein_reiter")
            // Element-Baum ins Log, damit man ohne Mac sieht, was stattdessen angezeigt wird.
            print("ELEMENTBAUM-ANFANG\n" + app.debugDescription + "\nELEMENTBAUM-ENDE")
            XCTFail("Reiter \"Fahrt\" nicht gefunden")
            return
        }
        bild(app, "02_fahrt")
        for (name, datei) in [("Fahrten", "03_fahrten"), ("Tanken", "04_tanken"), ("Statistik", "05_statistik")] {
            let r = reiter(app, name)
            if r.waitForExistence(timeout: 5) { r.tap() }
            bild(app, datei)
        }
        reiter(app, "Fahrt").tap()
        let zahnrad = app.buttons["gearshape"].firstMatch
        if zahnrad.waitForExistence(timeout: 3) {
            zahnrad.tap()
            bild(app, "06_einstellungen")
        }
    }
}
