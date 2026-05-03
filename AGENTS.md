# AGENTS.md

Guidelines for AI coding agents working on NeoRemote.

---

## Project Overview

NeoRemote is a **cross-device input control tool** — not a remote desktop. It lets phones control desktops (macOS/Windows) and Android devices over LAN. Four native platform implementations, no cross-platform frameworks.

**Core constraint**: This is a LAN-only tool with no server, no accounts, no cloud. Keep it simple.

---

## Repository Structure

```
.
├── Android/                   # Kotlin / Jetpack Compose
│   ├── app/src/main/java/     #   Source code
│   ├── app/src/test/          #   Unit tests
│   ├── app/src/androidTest/   #   UI tests
│   └── vendor/                #   Local composite-build deps (e.g. AndroidLiquidGlass)
├── iOS/                       # Swift / SwiftUI
│   ├── NeoRemote/Core/        #   Transport, protocol, discovery, session
│   ├── NeoRemote/Features/    #   UI screens (Onboarding, Remote, Devices, Settings)
│   └── NeoRemoteTests/        #   Unit tests
├── MacOS/                     # Swift Package (SPM)
│   ├── Sources/NeoRemoteMac/  #   App, Core, Features, Support
│   └── Tests/                 #   Unit tests
├── Windows/                   # C++20 / Win32
│   ├── src/NeoRemote.Core/    #   Protocol, input injection (platform-independent)
│   ├── src/NeoRemote.Windows/ #   TCP, UDP, Tray, Win32 specifics
│   └── tests/                 #   Unit tests
├── resources/                 # Brand assets, screenshots, docs, resource tooling
│   └── build/                 # Local installable build outputs (ignored)
└── .github/workflows/         # CI: build-all.yml, beta-release.yml
```

---

## Platform Conventions

### iOS (Swift / SwiftUI)

- **Minimum**: iOS 17.0+
- **Concurrency**: Swift 6 concurrency model. Use `@MainActor` for UI state, `async/await` for async work.
- **UI**: SwiftUI preferred. UIKit only when SwiftUI lacks the capability (e.g., haptics).
- **Architecture**: `Core/` for logic (transport, protocol, discovery, session). `Features/` for screens. `App/` for entry point.
- **Naming**: Protocols end with `ing` (e.g., `RemoteTransporting`). Views end with `View` or `Screen`.
- **No force unwraps** in production code. Use `guard let` or `if let`.
- **Testing**: `XCTest` for unit tests. Test files mirror source structure.

### Android (Kotlin / Jetpack Compose)

- **Minimum**: minSdk 26 (Android 8.0), compileSdk 36
- **UI**: Jetpack Compose only. No XML layouts.
- **Architecture**: `core/` for business logic (transport, protocol, session, receiver). `ui/` for Compose screens and components.
- **Package**: `com.neoremote.android`
- **Dependencies**: All versions pinned exactly in `build.gradle.kts`. No floating ranges.
- **Serialization**: `kotlinx.serialization` for JSON.
- **Coroutines**: Use `viewModelScope` for ViewModel work, `Dispatchers.IO` for network.
- **Testing**: JUnit 4 + Truth assertions + kotlinx-coroutines-test.

### macOS (Swift / SPM)

- **Minimum**: macOS 15+
- **Structure**: Swift Package with `Sources/NeoRemoteMac/` and `Tests/`.
- **UI**: SwiftUI for settings/dashboard. AppKit for menu bar and accessibility.
- **Input injection**: CoreGraphics (`CGEvent`). Requires accessibility permission.
- **Networking**: `Network.framework` (`NWConnection`, `NWListener`).
- **Build**: `swift build -c release --package-path MacOS` or `./MacOS/script/build_and_run.sh`.
- **Output**: `MacOS/dist/NeoRemoteMac.app`

### Windows (C++20 / Win32)

- **Structure**: `NeoRemote.Core` (platform-independent logic) and `NeoRemote.Windows` (Win32 layer).
- **Input injection**: `SendInput` API.
- **Networking**: Raw Winsock (`socket()`, `bind()`, `listen()`, `accept()`).
- **JSON**: Hand-rolled parser in `Protocol.cpp`. Be careful with edge cases.
- **Build**: Visual Studio 2022 or `./Windows/scripts/build_receiver.ps1`.
- **Output**: `Windows/build/NeoRemote.WindowsReceiver.exe`

---

## Protocol Rules

NeoRemote uses **JSON over TCP**. The protocol is defined independently on each platform.

### Command format

```json
{ "type": "clientHello", "clientId": "...", "displayName": "...", "platform": "ios" }
{ "type": "tap", "button": "primary" }
{ "type": "move", "dx": 12.3, "dy": -4.8 }
{ "type": "scroll", "deltaX": 0.0, "deltaY": 18.0 }
{ "type": "drag", "state": "started", "dx": 0.0, "dy": 0.0, "button": "primary" }
{ "type": "screenGesture", "kind": "swipe", "startX": 0.5, "startY": 0.8, "endX": 0.5, "endY": 0.2, "durationMs": 260 }
{ "type": "systemAction", "action": "back" }
{ "type": "videoAction", "action": "swipeUp" }
{ "type": "heartbeat" }
```

### Response format

```json
{ "type": "ack" }
{ "type": "status", "message": "..." }
{ "type": "heartbeat" }
```

### When extending the protocol

1. Add the new command type to **all four platforms** simultaneously:
   - iOS/macOS: `RemoteCommand` enum in `ProtocolCodec.swift`
   - Android: `RemoteCommand` sealed class in `Models.kt` + encode/decode in `ProtocolCodec.kt`
   - Windows: `RemoteCommandType` enum in `Protocol.cpp`
2. Maintain backward compatibility — old clients must ignore unknown `type` values.
3. Update the README protocol section.

### Discovery

- **Bonjour/DNS-SD**: Service type `_neoremote._tcp.`
- **UDP fallback**: Port `51101`, request `NEOREMOTE_DISCOVER_V1`, response prefix `NEOREMOTE_DESKTOP_V1`

### Default ports

| Target | Port |
| ------ | ---- |
| macOS receiver | `50505` |
| Windows receiver | `51101` |
| Android controlled device | `51101` |

---

## Security Constraints

See `resources/docs/security-review.md` for the full audit. Key rules:

1. **Never store secrets in code** — no API keys, passwords, or tokens in source files.
2. **Input validation** — validate all network-received data before using it. Check coordinate bounds, string lengths, numeric ranges.
3. **No unbounded buffers** — `JsonMessageStreamDecoder` must have a maximum buffer size (e.g., 1MB). Drop connection if exceeded.
4. **AccessibilityService safety** (Android) — gesture coordinates from the network must be bounds-checked against screen dimensions before `dispatchGesture`.
5. **Don't log sensitive data** — no `print()`/`Log.*()` of user data, device IDs, or protocol payloads in release builds.
6. **`.gitignore` sensitive files** — `.env`, `*.p12`, `*.pem`, `*.key`, `credentials*`, `*.jks`, `*.keystore` must never be committed. Current `.gitignore` covers `*.jks`, `*.keystore`, `*.idsig` but is missing `.env`, `*.p12`, `*.pem`, `*.key` — add these if you're editing `.gitignore`.

---

## Build & Test

Local installable artifacts and ad-hoc packaging intermediates must stay under
`resources/build/`. Do not create APK, IPA, app ZIP, EXE ZIP, archives,
DerivedData, or other handoff outputs in the repository root or a top-level
`release/` directory.

### iOS

```bash
# Build for simulator
xcodebuild build \
  -project iOS/NeoRemote.xcodeproj \
  -scheme NeoRemote \
  -destination 'id=<SIMULATOR_ID>'

# Run tests
xcodebuild test \
  -project iOS/NeoRemote.xcodeproj \
  -scheme NeoRemote \
  -destination 'platform=iOS Simulator,name=iPhone 17'
```

### Android

```bash
cd Android
./gradlew :app:testDebugUnitTest      # Unit tests
./gradlew :app:assembleDebug          # Debug APK
./gradlew :app:assembleRelease        # Release APK (unsigned)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Signing**: See `Android/android-signing-preset.md` for the full signing workflow. Key rules:

- `build.gradle.kts` does **not** embed signing config — signing is done externally via `apksigner`.
- Release APK only builds `arm64-v8a` (see `defaultConfig.ndk.abiFilters`).
- Never commit `.jks`, `.keystore`, `.idsig`, or signing passwords.
- CI uses GitHub Secrets; local uses `$HOME/.neoremote/android-release-signing/credentials.env` (chmod 600).
- Always run `apksigner verify --verbose --print-certs` after signing.

### macOS

```bash
swift test --package-path MacOS                        # Tests
swift build -c release --package-path MacOS            # Release build
./MacOS/script/build_and_run.sh                        # Build + launch
./MacOS/script/build_and_run.sh --verify               # Build + launch + verify
```

### Windows

```powershell
./Windows/scripts/build_receiver.ps1
```

The build script auto-detects a local `CN=NeoRemote Local Development` certificate for signing. If not found, it produces an unsigned build gracefully.

---

## CI/CD

- **`build-all.yml`**: Triggers on push to `main` or manual dispatch. Builds all four platform artifacts.
- **`beta-release.yml`**: Manual dispatch only. Creates a GitHub prerelease with all artifacts.
- **Android signing**: Uses GitHub Secrets (`ANDROID_RELEASE_KEYSTORE_BASE64`, etc.). Never hardcode signing credentials.

---

## Do NOT

- **Do not** add cross-platform frameworks (Flutter, React Native, Electron, KMP). Each platform is native.
- **Do not** add server-side components, databases, or cloud dependencies.
- **Do not** add external runtime dependencies to iOS/macOS (Swift packages with no external deps is intentional).
- **Do not** use `!!` (Kotlin force unwrap) or `as` (unsafe cast) in production code.
- **Do not** use `@unchecked Sendable` without documenting why the manual synchronization is safe.
- **Do not** add `print()`/`NSLog()`/`Log.d()` in production code paths. Use proper logging or remove.
- **Do not** change the JSON protocol format without updating all four platforms.
- **Do not** commit `.DS_Store`, `DerivedData/`, `.build/`, `build/`, `artifacts/`, or any IDE-specific files.
- **Do not** commit signing materials — `.jks`, `.keystore`, `.idsig`, `credentials.env`, keystore passwords.
- **Do not** embed signing config in `Android/app/build.gradle.kts` — signing is done externally via `apksigner`.
- **Do not** modify `vendor/` directories — they are third-party composite-build dependencies.
- **Do not** add floating dependency versions (e.g., `1.2.+`). Pin exact versions.

---

## Commit Messages

Use conventional format:

```
type: description in Chinese or English

feat: 新增功能
fix: 修复 bug
refactor: 重构（不改变行为）
docs: 文档更新
ci: CI/CD 变更
test: 测试相关
```

Keep the first line under 72 characters. Use the body for context if needed.

---

## Icon Sync

The single source of truth for app icons is `resources/icons/NeoRemote.icon` (Icon Composer format). After editing, run:

```bash
./resources/scripts/sync_icons.sh
```

This syncs to iOS (Xcode assets), macOS (`AppIcon.icns`), Android (`mipmap-*`), and Windows (`NeoRemote.ico`).

---

## Documentation

- `README.md` — English, primary
- `README_CN.md` — Chinese, mirror of English
- `AGENTS.md` — This file. Agent guidelines and constraints.
- `resources/docs/security-review.md` — Full security audit report.
- `Android/android-signing-preset.md` — Android signing workflow and conventions.

When updating one README, update both to keep them in sync.
