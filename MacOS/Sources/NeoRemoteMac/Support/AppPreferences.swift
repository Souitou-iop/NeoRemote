import Foundation

final class AppPreferences {
    private enum Keys {
        static let didCompleteOnboarding = "did_complete_onboarding"
        static let isListeningEnabled = "is_listening_enabled"
        static let isListeningSoundEnabled = "is_listening_sound_enabled"
        static let showsMenuBarExtra = "shows_menu_bar_extra"
        static let autoAllowTrustedDevices = "auto_allow_trusted_devices"
        static let trustedDeviceIDs = "trusted_device_ids"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var didCompleteOnboarding: Bool {
        get { defaults.bool(forKey: Keys.didCompleteOnboarding) }
        set { defaults.set(newValue, forKey: Keys.didCompleteOnboarding) }
    }

    var isListeningEnabled: Bool {
        get {
            if defaults.object(forKey: Keys.isListeningEnabled) == nil {
                return true
            }
            return defaults.bool(forKey: Keys.isListeningEnabled)
        }
        set { defaults.set(newValue, forKey: Keys.isListeningEnabled) }
    }

    var isListeningSoundEnabled: Bool {
        get {
            if defaults.object(forKey: Keys.isListeningSoundEnabled) == nil {
                return true
            }
            return defaults.bool(forKey: Keys.isListeningSoundEnabled)
        }
        set { defaults.set(newValue, forKey: Keys.isListeningSoundEnabled) }
    }

    var showsMenuBarExtra: Bool {
        get {
            if defaults.object(forKey: Keys.showsMenuBarExtra) == nil {
                return true
            }
            return defaults.bool(forKey: Keys.showsMenuBarExtra)
        }
        set { defaults.set(newValue, forKey: Keys.showsMenuBarExtra) }
    }

    var autoAllowTrustedDevices: Bool {
        get {
            if defaults.object(forKey: Keys.autoAllowTrustedDevices) == nil {
                return true
            }
            return defaults.bool(forKey: Keys.autoAllowTrustedDevices)
        }
        set { defaults.set(newValue, forKey: Keys.autoAllowTrustedDevices) }
    }

    var trustedDeviceIDs: Set<String> {
        get { Set(defaults.stringArray(forKey: Keys.trustedDeviceIDs) ?? []) }
        set { defaults.set(Array(newValue).sorted(), forKey: Keys.trustedDeviceIDs) }
    }
}
