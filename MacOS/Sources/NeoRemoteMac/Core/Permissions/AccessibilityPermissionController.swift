import AppKit
@preconcurrency import ApplicationServices

enum AccessibilityPermissionStatus: Equatable {
    case granted
    case denied
}

protocol AccessibilityPermissionControlling: AnyObject {
    var status: AccessibilityPermissionStatus { get }
    func refresh() -> AccessibilityPermissionStatus
    func requestPrompt() -> AccessibilityPermissionStatus
    func openSettings()
}

final class AccessibilityPermissionController: AccessibilityPermissionControlling {
    var status: AccessibilityPermissionStatus {
        refresh()
    }

    func refresh() -> AccessibilityPermissionStatus {
        AXIsProcessTrusted() ? .granted : .denied
    }

    func requestPrompt() -> AccessibilityPermissionStatus {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        return AXIsProcessTrustedWithOptions(options) ? .granted : .denied
    }

    func openSettings() {
        guard
            let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
        else {
            return
        }
        NSWorkspace.shared.open(url)
    }
}
