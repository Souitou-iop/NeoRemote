# NeoRemote Security Review

> Review date: 2026-05-01
> Scope: iOS, Android, macOS, Windows, CI/CD
> Status: Updated after source verification and first-pass fixes.

---

## Summary

| Status | Count |
| ------ | ----- |
| Open Critical | 2 |
| Open High | 3 |
| Open Medium | 4 |
| Open Low | 3 |
| Fixed in this pass | 8 |
| False positive / overstated | 2 |

The core risk is real: NeoRemote is a LAN-only remote input tool, but it currently lacks an authenticated control channel. Plain TCP plus unauthenticated UDP discovery allows LAN spoofing, replay, and impersonation unless the desktop-side approval flow catches the attacker. The macOS approval flow reduces some direct-control impact, but it is not a cryptographic trust boundary.

---

## Fixed in This Pass

| ID | Issue | Evidence |
| -- | ----- | -------- |
| F1 | iOS JSON stream buffer now has a 1MB cap and disconnects on overflow | `iOS/NeoRemote/Core/RemoteTransport.swift` |
| F2 | Android JSON stream buffer now has a 1MB cap and fails the connection on overflow | `Android/app/src/main/java/com/neoremote/android/core/protocol/JsonMessageStreamDecoder.kt` |
| F3 | macOS JSON stream buffer now has a 1MB cap and disconnects on overflow | `MacOS/Sources/NeoRemoteMac/Core/Protocol/JSONMessageStreamDecoder.swift` |
| F4 | Windows JSON stream buffer now has a 1MB cap and rejects oversized partial payloads | `Windows/src/NeoRemote.Core/src/JsonMessageStreamDecoder.cpp` |
| F5 | `build-all.yml` now declares least-privilege `permissions: contents: read` | `.github/workflows/build-all.yml` |
| F6 | `.gitignore` now blocks `.env`, `*.p12`, `*.pem`, `*.key`, and `credentials*` | `.gitignore` |
| F7 | Removed iOS production `print()`, gated Android debug logs behind debug builds, removed unused iOS settings slider code, and removed macOS listener port force unwrap | iOS/Android/macOS source |
| F8 | Android app backup is disabled so future paired-device secrets are not copied by platform backup | `Android/app/src/main/AndroidManifest.xml` |

---

## False Positive / Overstated Findings

### FP-1: macOS release build contains `get-task-allow`

The original report cited `MacOS/.build/arm64-apple-macosx/debug/NeoRemoteMac-entitlement.plist`. That file is a SwiftPM debug build artifact and is not tracked by git. Current release packaging uses explicit signing steps and does not reference that debug entitlement file. This should not be treated as a High release-blocking finding unless a shipped artifact is shown to contain the entitlement.

### FP-2: Android gesture injection has no bounds checking

The original statement was too broad. `MobileControlAccessibilityService` dispatches gestures, but upstream `MobileInputPlanner` clamps pointer movement to the viewport and clamps normalized screen gestures to `0.0...1.0`. The remaining risk is still High because there is no cryptographic peer authentication, no per-connection user confirmation for Android controlled-device mode, no gesture rate limit, and no safe-zone policy for sensitive UI.

---

## Open Critical

### Critical #1: No authenticated or encrypted control channel

| Platform | Evidence |
| -------- | -------- |
| iOS | `NWParameters(tls: nil, tcp: tcpOptions)` in `iOS/NeoRemote/Core/RemoteTransport.swift` |
| Android | bare `Socket()` in `Android/app/src/main/java/com/neoremote/android/core/transport/RemoteTransport.kt` |
| macOS | `NWParameters(tls: nil, tcp: tcpOptions)` in `MacOS/Sources/NeoRemoteMac/Core/Networking/TCPRemoteServer.swift` |
| Windows | `socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)` in `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp` |

**Impact**: A LAN attacker can observe, replay, or inject remote-control traffic unless an application-level approval state blocks it.

**Recommendation**: Add a cross-platform PSK pairing flow with challenge-response authentication and per-message HMAC. Add optional TLS after the protocol-level authentication is stable.

### Critical #2: Unauthenticated UDP discovery

The UDP fallback uses the fixed request `NEOREMOTE_DISCOVER_V1` and response prefix `NEOREMOTE_DESKTOP_V1`. Spoofed responses can steer clients toward an attacker-controlled endpoint.

**Recommendation**: Include a random nonce in discovery requests and require responders to sign the nonce with the paired-device PSK.

---

## Open High

### High #3: Android controlled-device mode can inject sensitive gestures after LAN command acceptance

Coordinates are clamped, but accepted commands can still trigger taps, swipes, global actions, and video actions through AccessibilityService.

**Recommendation**: Require explicit connection approval on the Android controlled device, add gesture rate limiting, and consider safe-zone restrictions for high-risk actions.

### High #4: Windows TCP server has no connection cap or rate limiter

`AcceptLoop` accepts clients continuously and spawns detached receive threads. A LAN attacker can create many connections to exhaust process resources.

**Recommendation**: Add a small max-client limit, reject excess clients, and rate-limit connection attempts per remote endpoint.

### High #5: Protocol commands are accepted before cryptographic trust is established

`clientHello` identifies the client, but it is not proof of possession of a shared secret. The macOS approval flow helps UX, not protocol authenticity.

**Recommendation**: Commands other than the pairing/auth handshake must be ignored until authentication succeeds.

---

## Open Medium

### Medium #7: iOS/macOS silently drop malformed decoded messages

`try? codec.decode(...)` / `try? codec.decodeCommand(...)` discards malformed payloads. This hides malformed-input attacks and makes debugging protocol abuse harder.

### Medium #8: macOS `@unchecked Sendable` relies on manual queue discipline

`TCPRemoteServer`, `ClientConnection`, and `CGEventInputInjector` use `@unchecked Sendable`. Current queue usage is intentional, but this bypasses compiler enforcement and should be revisited with actors or locks.

### Medium #9: Windows hand-rolled JSON parser is fragile

`FindString` and `FindNumber` use local string scanning. Escapes are only partially handled, and this parser is easy to break as the protocol grows.

### Medium #10: Windows server-side client IDs are predictable

`MakeClientId()` uses `steady_clock::now().count()`. This is not currently used as an auth token, so the severity is Medium rather than High.

---

## Open Low

| ID | Platform | Issue |
| -- | -------- | ----- |
| Low #12 | Android | `isMinifyEnabled = false`; release code is not obfuscated |
| Low #13 | Windows | Uses `SO_REUSEADDR`; consider `SO_EXCLUSIVEADDRUSE` for receiver port ownership |
| Low #14 | Documentation | Android signing preset documents local private keystore path; not a secret by itself, but keep it out of public-facing docs if publishing broadly |

---

## Priority Matrix

| Priority | Issue | Effort |
| -------- | ----- | ------ |
| P0 | Cross-platform PSK pairing + command authentication | 2-3d |
| P0 | UDP discovery nonce + signed response | 1d |
| P1 | Android controlled-device approval + gesture rate limiting | 0.5-1d |
| P1 | Windows max-client cap + connection rate limiting | 0.5d |
| P2 | Decode-error telemetry and repeated-malformed disconnect policy | 0.5d |
| P2 | Optional TLS transport | 3-5d |
| P3 | Android release minify hardening | 0.5d |

---

## Positive Findings

- All dependency versions are pinned.
- macOS Swift Package has zero external dependencies.
- Android, iOS, macOS, and Windows now cap JSON stream buffers at 1MB.
- `build-all.yml` now has least-privilege read-only token permissions.
- `.gitignore` now covers common signing and credential file patterns.
- Android backup is disabled.
- Detailed Android discovery, command, and gesture logs are now debug-build-only.
- macOS has an application-level incoming connection approval flow.
