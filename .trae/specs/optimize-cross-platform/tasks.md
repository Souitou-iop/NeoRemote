# Tasks

- [x] Task 1: Android Release 启用 R8 代码缩减
  - [x] SubTask 1.1: 修改 `Android/app/build.gradle.kts`，将 release 构建类型的 `isMinifyEnabled` 设为 `true`
  - [x] SubTask 1.2: 编写 `Android/app/proguard-rules.pro`，添加 kotlinx.serialization 保留规则、AccessibilityService 相关类保留规则、Compose 保留规则
  - [ ] SubTask 1.3: 验证 `./gradlew assembleRelease` 构建成功

- [x] Task 2: Android ProtocolCodec screenGesture 坐标校验
  - [x] SubTask 2.1: 在 `ProtocolCodec.kt` 的 `decodeCommand` 方法中，对 `screenGesture` 的 `startX`/`startY`/`endX`/`endY` 添加 `coerceIn(0.0, 1.0)` 范围校验
  - [x] SubTask 2.2: 在 `ProtocolCodecTest.kt` 中添加坐标超范围的测试用例

- [x] Task 3: Windows 协议命令补全（systemAction、videoAction、screenGesture）
  - [x] SubTask 3.1: 在 `Protocol.hpp` 的 `RemoteCommandType` 枚举中添加 `SystemAction`、`VideoAction`、`ScreenGesture`
  - [x] SubTask 3.2: 在 `RemoteCommand` 结构体中添加 `systemAction`、`videoAction`、`screenGestureKind`、`startX`/`startY`/`endX`/`endY`、`durationMs` 字段及对应工厂方法
  - [x] SubTask 3.3: 在 `Protocol.cpp` 的 `DecodeCommand` 中添加三种命令类型的解码逻辑，包含坐标范围校验
  - [x] SubTask 3.4: 在 Windows 测试中添加对应解码测试用例

- [x] Task 4: Windows 客户端 ID 改用 UUID
  - [x] SubTask 4.1: 修改 `TcpRemoteServer.cpp` 的 `MakeClientId()` 方法，使用 Windows API `CoCreateGuid` 生成 UUID 格式的客户端 ID
  - [x] SubTask 4.2: 确保 `#pragma comment(lib, "ole32.lib")` 已链接

- [x] Task 5: CI 工作流添加构建缓存
  - [x] SubTask 5.1: 在 `build-all.yml` 的 Android 构建步骤中确认 `gradle/actions/setup-gradle@v6` 已自动启用 Gradle 缓存（当前已配置，验证即可）
  - [x] SubTask 5.2: 在 `build-all.yml` 的 iOS 构建步骤前添加 Swift Package Manager 缓存（`~/Library/Developer/Xcode/DerivedData` 和 `.build`）
  - [x] SubTask 5.3: 在 `build-all.yml` 的 macOS 构建步骤前添加 SPM 缓存
  - [x] SubTask 5.4: 在 `beta-release.yml` 中同步相同的缓存配置

- [x] Task 6: Android Gradle 构建优化配置
  - [x] SubTask 6.1: 在 `Android/gradle.properties` 中添加 `org.gradle.parallel=true` 和 `org.gradle.caching=true`
  - [ ] SubTask 6.2: 验证 `./gradlew assembleDebug` 构建正常

- [x] Task 7: iOS 灵敏度设置去重
  - [x] SubTask 7.1: 修改 `SessionCoordinator.swift` 的 `setCursorSensitivity` 方法，在值未变化时提前返回
  - [x] SubTask 7.2: 修改 `SessionCoordinator.swift` 的 `setSwipeSensitivity` 方法，在值未变化时提前返回

# Task Dependencies
- [Task 3] 的 SubTask 3.4 依赖 SubTask 3.1-3.3 完成
- [Task 5] 的 SubTask 5.4 依赖 SubTask 5.2-5.3 的缓存模式确定
- 其余任务可并行执行
