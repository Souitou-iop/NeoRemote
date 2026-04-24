import Foundation

final class DeviceRegistry {
    private enum Keys {
        static let recentDevices = "recent_devices"
        static let lastConnected = "last_connected"
        static let hapticsEnabled = "haptics_enabled"
        static let clientID = "client_id"
        static let cursorSensitivity = "cursor_sensitivity"
        static let swipeSensitivity = "swipe_sensitivity"
    }

    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let maxRecentCount = 3

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadRecentDevices() -> [DesktopEndpoint] {
        let stored = loadArray(forKey: Keys.recentDevices)
        let compacted = compactRecentDevices(stored)
        if compacted != stored {
            save(compacted, forKey: Keys.recentDevices)
        }
        return compacted
    }

    func loadLastConnectedDevice() -> DesktopEndpoint? {
        guard let endpoint: DesktopEndpoint = loadValue(forKey: Keys.lastConnected) else { return nil }
        let recents = loadRecentDevices()
        return recents.first { $0.matchesSameDesktop(as: endpoint) } ?? endpoint
    }

    func loadHapticsEnabled() -> Bool {
        guard defaults.object(forKey: Keys.hapticsEnabled) != nil else { return true }
        return defaults.bool(forKey: Keys.hapticsEnabled)
    }

    func saveHapticsEnabled(_ isEnabled: Bool) {
        defaults.set(isEnabled, forKey: Keys.hapticsEnabled)
    }

    func loadTouchSensitivitySettings() -> TouchSensitivitySettings {
        TouchSensitivitySettings(
            cursorSensitivity: loadSensitivityValue(
                key: Keys.cursorSensitivity,
                fallback: TouchSensitivitySettings.default.cursorSensitivity,
                range: TouchSensitivitySettings.cursorRange
            ),
            swipeSensitivity: loadSensitivityValue(
                key: Keys.swipeSensitivity,
                fallback: TouchSensitivitySettings.default.swipeSensitivity,
                range: TouchSensitivitySettings.swipeRange
            )
        )
    }

    func saveTouchSensitivitySettings(_ settings: TouchSensitivitySettings) {
        let clamped = settings.clamped
        defaults.set(clamped.cursorSensitivity, forKey: Keys.cursorSensitivity)
        defaults.set(clamped.swipeSensitivity, forKey: Keys.swipeSensitivity)
    }

    func loadOrCreateClientID() -> String {
        if let existing = defaults.string(forKey: Keys.clientID), !existing.isEmpty {
            return existing
        }
        let id = UUID().uuidString
        defaults.set(id, forKey: Keys.clientID)
        return id
    }

    func upsertRecent(_ endpoint: DesktopEndpoint) {
        var current = loadRecentDevices()
        current.removeAll { $0.matchesSameDesktop(as: endpoint) }

        var updated = endpoint
        updated.source = .recent
        updated.lastSeenAt = Date()

        current.insert(updated, at: 0)
        save(compactRecentDevices(current), forKey: Keys.recentDevices)
    }

    func saveLastConnected(_ endpoint: DesktopEndpoint?) {
        guard let endpoint else {
            defaults.removeObject(forKey: Keys.lastConnected)
            return
        }

        var updated = endpoint
        updated.source = .recent
        updated.lastSeenAt = Date()
        save(updated, forKey: Keys.lastConnected)
    }

    func clearRecentDevices() {
        defaults.removeObject(forKey: Keys.recentDevices)
        defaults.removeObject(forKey: Keys.lastConnected)
    }

    func validate(host: String, portText: String) throws -> DesktopEndpoint {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty else {
            throw ConnectionFailure.invalidHost
        }

        guard let port = UInt16(portText), port > 0 else {
            throw ConnectionFailure.invalidPort
        }

        return DesktopEndpoint(
            displayName: "Desktop",
            host: trimmedHost,
            port: port,
            platform: nil,
            lastSeenAt: Date(),
            source: .manual
        )
    }

    private func loadArray(forKey key: String) -> [DesktopEndpoint] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? decoder.decode([DesktopEndpoint].self, from: data)) ?? []
    }

    private func compactRecentDevices(_ devices: [DesktopEndpoint]) -> [DesktopEndpoint] {
        var seenKeys = Set<String>()
        var result: [DesktopEndpoint] = []

        for endpoint in devices.sortedByMostRecent() {
            let key = endpoint.deduplicationKey
            guard !seenKeys.contains(key) else { continue }
            seenKeys.insert(key)
            result.append(endpoint)
            if result.count == maxRecentCount { break }
        }

        return result
    }

    private func loadValue<T: Decodable>(forKey key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, forKey key: String) {
        guard let data = try? encoder.encode(value) else { return }
        defaults.set(data, forKey: key)
    }

    private func loadSensitivityValue(
        key: String,
        fallback: Double,
        range: ClosedRange<Double>
    ) -> Double {
        guard defaults.object(forKey: key) != nil else { return fallback }
        return defaults.double(forKey: key).clamped(to: range)
    }
}

extension DesktopEndpoint {
    var deduplicationKey: String {
        let normalizedName = displayName
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let normalizedHost = host
            .trimmingCharacters(in: CharacterSet(charactersIn: ".").union(.whitespacesAndNewlines))
            .lowercased()
        if !normalizedName.isEmpty, normalizedName != "desktop" {
            return "name:\(normalizedName)|port:\(port)"
        }

        return "host:\(normalizedHost)|port:\(port)"
    }

    func matchesSameDesktop(as other: DesktopEndpoint) -> Bool {
        deduplicationKey == other.deduplicationKey
    }
}

private extension Array where Element == DesktopEndpoint {
    func sortedByMostRecent() -> [DesktopEndpoint] {
        sorted {
            ($0.lastSeenAt ?? .distantPast) > ($1.lastSeenAt ?? .distantPast)
        }
    }
}
