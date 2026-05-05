# NeoRemote 跨平台优化 Spec

## Why
NeoRemote 四个平台实现中存在安全验证缺失、构建优化未启用、协议支持不完整、CI 缓存缺失等问题，影响 APK 安全性、Windows 端功能完整性以及 CI 构建效率。

## What Changes
- Android Release 构建启用 R8 代码缩减与混淆
- Android ProtocolCodec 增加 screenGesture 坐标范围校验
- Windows Protocol.cpp 补全缺失的协议命令（systemAction、videoAction、screenGesture）
- Windows 客户端 ID 生成改用 UUID 避免时间戳冲突
- CI 工作流添加 Gradle 和 Swift 构建缓存
- Android Gradle 配置启用并行编译与构建缓存
- iOS SessionCoordinator 灵敏度设置增加值未变化时跳过更新

## Impact
- Affected specs: Android 构建配置、协议编解码、Windows 协议实现、CI/CD 流程
- Affected code:
  - `Android/app/build.gradle.kts`
  - `Android/app/proguard-rules.pro`
  - `Android/app/src/main/java/com/neoremote/android/core/protocol/ProtocolCodec.kt`
  - `Windows/src/NeoRemote.Core/src/Protocol.cpp`
  - `Windows/src/NeoRemote.Core/include/NeoRemote/Core/Protocol.hpp`
  - `Windows/src/NeoRemote.Windows/src/TcpRemoteServer.cpp`
  - `.github/workflows/build-all.yml`
  - `.github/workflows/beta-release.yml`
  - `Android/gradle.properties`
  - `iOS/NeoRemote/Core/SessionCoordinator.swift`

## ADDED Requirements

### Requirement: Android Release R8 代码缩减
系统 SHALL 在 Android Release 构建中启用 R8 代码缩减（`isMinifyEnabled = true`），并配置 ProGuard 规则以保留反射使用的类（kotlinx.serialization、AccessibilityService 相关类）。

#### Scenario: Release APK 构建成功且体积减小
- **WHEN** 执行 `./gradlew assembleRelease`
- **THEN** 构建成功，APK 体积相比未启用 R8 时减小，且运行时功能正常

#### Scenario: kotlinx.serialization 类未被错误移除
- **WHEN** R8 启用后解析 JSON 协议消息
- **THEN** 序列化/反序列化正常工作，无 ClassNotFoundException

### Requirement: Android screenGesture 坐标校验
系统 SHALL 在 Android ProtocolCodec 的 `decodeCommand` 方法中，对 `screenGesture` 命令的 `startX`、`startY`、`endX`、`endY` 字段进行 [0.0, 1.0] 范围校验，超出范围时 clamp 到合法范围而非静默接受。

#### Scenario: 坐标超出范围时被 clamp
- **WHEN** 收到 `screenGesture` 命令且 `startX = -0.5, endY = 1.5`
- **THEN** `startX` 被 clamp 为 `0.0`，`endY` 被 clamp 为 `1.0`

### Requirement: Windows 协议命令补全
系统 SHALL 在 Windows Protocol.cpp 中支持 `systemAction`、`videoAction`、`screenGesture` 三种命令类型的解码，与 iOS/macOS/Android 保持协议一致性。

#### Scenario: 解码 systemAction 命令
- **WHEN** 收到 `{"type":"systemAction","action":"back"}`
- **THEN** 成功解码为 `RemoteCommandType::SystemAction`，action 为 `back`

#### Scenario: 解码 videoAction 命令
- **WHEN** 收到 `{"type":"videoAction","action":"swipeUp"}`
- **THEN** 成功解码为 `RemoteCommandType::VideoAction`，action 为 `swipeUp`

#### Scenario: 解码 screenGesture 命令
- **WHEN** 收到 `{"type":"screenGesture","kind":"swipe","startX":0.5,"startY":0.8,"endX":0.5,"endY":0.2,"durationMs":260}`
- **THEN** 成功解码为 `RemoteCommandType::ScreenGesture`，包含正确的坐标和时长字段

#### Scenario: 未知命令类型不崩溃
- **WHEN** 收到无法识别的命令类型
- **THEN** 抛出 `ProtocolCodecError` 而非崩溃

### Requirement: Windows 客户端 ID 改用 UUID
系统 SHALL 将 Windows `TcpRemoteServer::MakeClientId()` 从基于时间戳的 ID 生成改为基于 UUID 的 ID 生成，避免快速连续连接时的 ID 冲突。

#### Scenario: 快速连续连接生成不同 ID
- **WHEN** 两个客户端在 1ms 内连接
- **THEN** 两个客户端获得不同的 ID

### Requirement: CI 构建缓存
系统 SHALL 在 CI 工作流中为 Gradle 和 Swift 构建添加缓存，减少重复构建时间。

#### Scenario: Gradle 缓存命中
- **WHEN** CI 工作流第二次运行且 `build.gradle.kts` 未变更
- **THEN** Gradle 构建利用缓存，构建时间显著缩短

#### Scenario: Swift 构建缓存命中
- **WHEN** CI 工作流第二次运行且 `Package.swift` 未变更
- **THEN** Swift 构建利用缓存，构建时间缩短

### Requirement: Android Gradle 构建优化配置
系统 SHALL 在 `Android/gradle.properties` 中启用并行编译（`org.gradle.parallel=true`）和构建缓存（`org.gradle.caching=true`），提升本地和 CI 构建速度。

#### Scenario: 并行编译生效
- **WHEN** 执行 Gradle 构建
- **THEN** 多模块并行编译，构建时间缩短

### Requirement: iOS 灵敏度设置去重
系统 SHALL 在 iOS `SessionCoordinator` 的 `setCursorSensitivity` 和 `setSwipeSensitivity` 方法中，当新值与当前值相同时跳过更新，避免不必要的对象创建和 UserDefaults 写入。

#### Scenario: 设置相同值时跳过更新
- **WHEN** 调用 `setCursorSensitivity(1.0)` 且当前 `cursorSensitivity` 已经是 `1.0`
- **THEN** 不触发 `updateTouchSensitivity`，不写入 UserDefaults

## MODIFIED Requirements

### Requirement: Windows 协议编解码
Windows Protocol.hpp 中的 `RemoteCommand` 结构体 SHALL 新增 `systemAction`（std::string）、`videoAction`（std::string）、`screenGestureKind`（std::string）、`startX`/`startY`/`endX`/`endY`（double）、`durationMs`（long long）字段，以及对应的 `RemoteCommandType` 枚举值。

## REMOVED Requirements

（无移除项）
