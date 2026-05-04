import Foundation

enum ConnectionDiagnostics {
    private static let flag = "--neoremote-diagnostics"
    private static let environmentKey = "NEOREMOTE_DIAGNOSTICS"
    private static let fileName = "neoremote-connection.log"

    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains(flag)
            || ProcessInfo.processInfo.environment[environmentKey] == "1"
    }

    static var logURL: URL {
        let baseURL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return baseURL.appendingPathComponent(fileName)
    }

    static func resetIfEnabled() {
        guard isEnabled else { return }
        try? FileManager.default.removeItem(at: logURL)
        log("diagnostics-started")
    }

    static func log(_ message: String) {
        guard isEnabled else { return }
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
        guard let data = line.data(using: .utf8) else { return }
        if FileManager.default.fileExists(atPath: logURL.path) {
            if let handle = try? FileHandle(forWritingTo: logURL) {
                try? handle.seekToEnd()
                try? handle.write(contentsOf: data)
                try? handle.close()
            }
        } else {
            try? data.write(to: logURL, options: .atomic)
        }
    }
}

enum LaunchDiagnostics {
    static var autoconnectEndpoint: DesktopEndpoint? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "--neoremote-autoconnect-android") else {
            return nil
        }
        let valueIndex = arguments.index(after: index)
        guard arguments.indices.contains(valueIndex) else { return nil }
        let value = arguments[valueIndex]
        let parts = value.split(separator: ":", maxSplits: 1).map(String.init)
        guard let host = parts.first, !host.isEmpty else { return nil }
        let port = parts.dropFirst().first.flatMap(UInt16.init) ?? 51101
        return DesktopEndpoint(
            displayName: "Android",
            host: host,
            port: port,
            platform: .android,
            lastSeenAt: Date(),
            source: .manual
        )
    }
}
