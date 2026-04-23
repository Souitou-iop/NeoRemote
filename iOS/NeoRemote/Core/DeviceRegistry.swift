import Foundation

final class DeviceRegistry {
    private enum Keys {
        static let recentDevices = "recent_devices"
        static let lastConnected = "last_connected"
        static let hapticsEnabled = "haptics_enabled"
    }

    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let maxRecentCount = 6

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadRecentDevices() -> [DesktopEndpoint] {
        loadArray(forKey: Keys.recentDevices)
    }

    func loadLastConnectedDevice() -> DesktopEndpoint? {
        loadValue(forKey: Keys.lastConnected)
    }

    func loadHapticsEnabled() -> Bool {
        guard defaults.object(forKey: Keys.hapticsEnabled) != nil else { return true }
        return defaults.bool(forKey: Keys.hapticsEnabled)
    }

    func saveHapticsEnabled(_ isEnabled: Bool) {
        defaults.set(isEnabled, forKey: Keys.hapticsEnabled)
    }

    func upsertRecent(_ endpoint: DesktopEndpoint) {
        var current = loadRecentDevices()
        current.removeAll { $0.host == endpoint.host && $0.port == endpoint.port }

        var updated = endpoint
        updated.source = .recent
        updated.lastSeenAt = Date()

        current.insert(updated, at: 0)
        save(current.prefix(maxRecentCount).map { $0 }, forKey: Keys.recentDevices)
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

    private func loadValue<T: Decodable>(forKey key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, forKey key: String) {
        guard let data = try? encoder.encode(value) else { return }
        defaults.set(data, forKey: key)
    }
}
