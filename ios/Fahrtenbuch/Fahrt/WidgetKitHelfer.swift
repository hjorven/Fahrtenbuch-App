import WidgetKit

enum WidgetKitHelfer {
    static func neuLaden() {
        WidgetCenter.shared.reloadAllTimelines()
        ControlCenter.shared.reloadAllControls()
    }
}
