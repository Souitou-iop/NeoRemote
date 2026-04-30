# NeoRemote Security Review

> Review date: 2026-05-01
> Scope: iOS, Android, macOS, Windows, CI/CD

---

## Summary

| Severity | Count |
| -------- | ----- |
| Critical | 2     |
| High     | 5     |
| Medium   | 8     |
| Low      | 6     |

The most urgent finding is the **systemic absence of transport-layer security across all four platforms**. Every TCP connection transmits remote-control commands in plaintext, and the UDP discovery protocol is unauthenticated. Together these allow any LAN attacker to intercept, replay, or inject input commands.

---

## Cross-Platform Systemic Issues

These issues affect all four platforms and must be resolved at the protocol level.

### Critical #1: Plaintext TCP, No Encryption

| Platform | Location | Evidence |
| -------- | -------- | -------- |
| iOS      | `iOS/NeoRemote/Core/RemoteTransport.swift:34` | `NWParameters(tls: nil, tcp: tcpOptions)` |
| Android  | `Android/app/src/main/java/.../transport/RemoteTransport.kt:49` | `Socket()` bare connection |
| macOS    | `MacOS/Sources/.../Networking/TCPRemoteServer.swift:47` | `NWParameters(tls: nil, tcp: tcpOptions)` |
| Windows  | `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp:121` | `socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)` |

**Impact**: Any device on the same LAN can sniff, replay, or inject mouse/keyboard/gesture commands. An attacker can remotely control the victim's computer.

**Recommendation**:

- **Short-term** — Add PSK (pre-shared key) handshake authentication with HMAC command signing.
- **Medium-term** — Optional TLS transport (iOS/macOS: `NWParameters.tls`, Android: `SSLSocket`, Windows: SChannel).

### Critical #2: Unauthenticated UDP Discovery

All platforms respond to a fixed string `"NEOREMOTE_DISCOVER_V1"` with their TCP port. Any device can spoof discovery responses to perform MITM attacks.

**Recommendation**: Add challenge-response — client sends a random nonce, server signs the nonce with PSK and returns the signature.

---

## High Severity

### High #3: Android AccessibilityService Unrestricted Gesture Injection

**File**: `Android/app/src/main/java/.../receiver/MobileControlAccessibilityService.kt:329-361`

`dispatchGesture` executes coordinates received from the network with no bounds checking, rate limiting, or confirmation prompt. An attacker can tap any screen location, including system dialogs or banking apps.

**Recommendation**:

- Coordinate whitelist / safe-zone restriction
- Confirmation dialog on connection establishment
- Gesture rate limiting (e.g., max 10 gestures per 500ms)

### High #4: No Protocol-Level Authentication

**File**: `Android/app/src/main/java/.../protocol/ProtocolCodec.kt:123` (and equivalent on all platforms)

`decodeCommand` accepts any JSON over TCP without a shared secret, token, or challenge-response. Combined with plaintext transport, any LAN device can impersonate a controller.

**Recommendation**: Implement a unified handshake protocol — `clientHello` -> `challenge` -> `response` -> `ack` — where authentication must succeed before commands are accepted.

### High #5: Windows Predictable Client ID

**File**: `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp:273-278`

`MakeClientId()` uses `steady_clock::now().count()`, which is a deterministic, guessable sequence.

**Recommendation**: Use a cryptographically random UUID (e.g., `BCryptGenRandom` on Windows).

### High #6: macOS Release Build Contains `get-task-allow` Entitlement

**File**: `MacOS/.build/arm64-apple-macosx/debug/NeoRemoteMac-entitlement.plist`

`com.apple.security.get-task-allow` is `true`. This disables SIP protections and allows debugger attachment. Acceptable in debug but must not ship in release.

**Recommendation**: Set to `false` in the release entitlements plist.

### High #7: Windows No Connection Rate Limiting

**File**: `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp:160-185`

`AcceptLoop` has no connection rate limit or max-client cap. An attacker can exhaust resources by opening many connections.

**Recommendation**: Add a max concurrent client limit (e.g., 5) and connection rate limiter.

---

## Medium Severity

### Medium #8: iOS JSON Stream Parser Unbounded Buffer

**File**: `iOS/NeoRemote/Core/RemoteTransport.swift:135-192`

`JsonMessageStreamDecoder` appends all incoming bytes to `buffer` with no size cap. A malicious peer sending partial JSON (no closing `}`) causes unbounded memory growth.

**Recommendation**: Add a maximum buffer size (e.g., 1MB) and drop the connection if exceeded.

### Medium #9: Android JSON Stream Parser Unbounded Buffer

**File**: `Android/app/src/main/java/.../protocol/JsonMessageStreamDecoder.kt:8`

Same issue as iOS — `buffer += data` concatenates without limit.

**Recommendation**: Same as iOS — add buffer size cap.

### Medium #10: iOS Silent Decode Failure

**File**: `iOS/NeoRemote/Core/RemoteTransport.swift:93`

`try? self.codec.decode(payload)` silently discards decode errors. Malformed or tampered messages are ignored with no logging, making protocol-level attacks invisible.

**Recommendation**: Log decode failures and consider disconnecting after repeated malformed messages.

### Medium #11: macOS `@unchecked Sendable` Bypasses Swift Concurrency Safety

**File**: `MacOS/Sources/.../Networking/TCPRemoteServer.swift:22, 131` and `CGEventInputInjector.swift:17`

Mutable state is protected only by a single `DispatchQueue` with `@unchecked Sendable`. Any missed dispatch barrier creates data races.

**Recommendation**: Use `actor` or `Mutex` (Swift 6) instead of manual DispatchQueue management.

### Medium #12: Windows Unbounded Thread Creation

**File**: `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp:183`

`std::thread([this, clientId] { ReceiveLoop(clientId); }).detach()` — each client spawns a detached thread with no pool or limit.

**Recommendation**: Use a thread pool or IOCP-based async model.

### Medium #13: Windows Hand-Rolled JSON Parser is Fragile

**File**: `Windows/src/NeoRemote.Core/src/Protocol.cpp:12-83`

`FindString`/`FindNumber` do naive string scanning. No Unicode handling, no null-byte handling, no escape sequence support.

**Recommendation**: Replace with [nlohmann/json](https://github.com/nlohmann/json) or [simdjson](https://github.com/simdjson/simdjson).

### Medium #14: CI Missing `permissions` Declaration

**File**: `.github/workflows/build-all.yml`

No top-level `permissions:` block. All jobs inherit the default token scope, which may be broader than needed.

**Recommendation**: Add `permissions: contents: read` at the top level.

### Medium #15: `.gitignore` Missing Sensitive File Patterns

**File**: `.gitignore`

Blocks `*.jks`, `*.keystore`, `*.idsig` (good), but has no rules for `.env`, `*.p12`, `*.pem`, `*.key`, or `credentials*`.

**Recommendation**: Add the following entries:

```gitignore
.env
*.p12
*.pem
*.key
credentials*
```

---

## Low Severity

| # | Platform | Issue | File |
| - | -------- | ----- | ---- |
| 16 | iOS | Dead code `SensitivitySlider` / `NoHapticSlider` never used | `iOS/NeoRemote/Features/Settings/SettingsView.swift:65-142` |
| 17 | iOS | `print()` in production code leaks diagnostic info | `iOS/NeoRemote/Core/DiscoveryService.swift:331` |
| 18 | Android | `isMinifyEnabled = false` — release build not obfuscated | `Android/app/build.gradle.kts:31` |
| 19 | Android | `android:allowBackup="true"` — app data extractable via `adb backup` | `Android/app/src/main/AndroidManifest.xml:11` |
| 20 | macOS | Force-unwrap `NWEndpoint.Port(rawValue: port)!` can crash | `MacOS/Sources/.../Networking/TCPRemoteServer.swift:51` |
| 21 | Windows | No `SO_EXCLUSIVEADDRUSE` — port can be hijacked by another process | `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp:127-128` |

---

## Optimization Recommendations

### Architecture

1. **Standardize protocol handshake**: Unify TCP accept/connect across all four platforms into a `clientHello` -> `challenge` -> `response` -> `ack` four-step handshake. Commands must not be accepted before authentication succeeds.

2. **Command whitelist**: Currently `dispatchGesture` (Android) and `SendInput` (Windows) execute raw network data directly. Add a validation layer — only allow known action types, coordinates must be within screen bounds.

3. **Windows JSON parser**: The hand-written `FindString`/`FindNumber` is technical debt. Consider adopting [nlohmann/json](https://github.com/nlohmann/json) or [simdjson](https://github.com/simdjson/simdjson).

### CI/CD

4. **`build-all.yml`**: Add `permissions: contents: read` at the top level.
5. **`.gitignore`**: Supplement with sensitive file patterns (`.env`, `*.p12`, `*.pem`, `*.key`).

---

## Priority Matrix

| Priority | Issue | Effort |
| -------- | ----- | ------ |
| P0 | JSON buffer unbounded (iOS + Android, OOM risk) | 1h |
| P0 | Android gesture injection no bounds check | 2h |
| P1 | Protocol authentication layer (PSK + HMAC) | 2-3d |
| P1 | UDP discovery challenge-response | 1d |
| P2 | Optional TLS transport | 3-5d |
| P2 | Windows JSON parser replacement | 0.5d |
| P3 | CI `permissions` + `.gitignore` supplement | 30min |
| P3 | Dead code cleanup + `print()` removal | 30min |

---

## Positive Findings

- All dependency versions are pinned (no floating ranges)
- `Package.swift` has zero external dependencies (minimal attack surface)
- `sync_icons.sh` uses `mktemp` with cleanup trap — no command injection vectors
- Windows build script references local code-signing cert without hardcoded passwords
- Android self-connection check prevents connecting to self (though bypassable via IP spoofing)
- macOS has an approval flow for incoming connections (application-layer, not transport-layer)
