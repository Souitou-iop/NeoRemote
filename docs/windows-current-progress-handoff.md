# NeoRemote 当前进度与 Windows 端接力说明

更新时间：2026-04-22

本文档用于替代旧的跨端进度认知，给接下来 Windows 端开发提供一个准确的当前状态。注意：`docs/cross-platform-current-progress.md` 中“Android 尚未开始”的描述已经过时，不应再作为最新事实依据。

## 一句话结论

当前仓库已经不是 “iOS -> macOS 原型期”，而是：

- `iOS` 控制端：已存在，作为交互与协议基线
- `MacOS` 接收端：已存在，可作为桌面端参考实现
- `Android` 控制端：首版原生 App 已从 0 搭建完成，并已完成命令行构建与单元测试闭环
- `Windows` 接收端：尚未开始，当前目录只有占位文件，下一阶段可以直接开工

## 当前目录状态

- `iOS/`
  - 现有 iPhone 控制端
- `MacOS/`
  - 现有 macOS 桌面接收端
- `Android/`
  - 新增的原生 Android 控制端工程，已具备真实实现，不是空壳
- `Windows/`
  - 当前仅有 `.gitkeep`
- `docs/`
  - 现有产品与交接文档

## iOS / macOS 当前定位

### iOS

iOS 端仍然是移动控制端的行为参考，主要用于给 Android 对齐交互模型与协议语义。

当前应视为已具备：

- Bonjour 发现
- 手动连接
- 最近设备 / 上次连接恢复
- `Remote / Devices / Settings` 结构
- 触控输入到远控命令的映射

### macOS

macOS 端仍然是桌面接收端的参考实现，Windows 端应优先对齐它的协议、连接模型和产品边界，而不是自由设计一套新形态。

当前应视为已具备：

- Bonjour 发布 `_neoremote._tcp.`
- TCP `50505` 监听
- JSON v1 协议解析
- `ack / status / heartbeat` 回包
- 鼠标 `move / tap / scroll / drag` 注入
- 单连接占用策略
- 运行状态展示与设置能力

## Android 端当前真实进度

Android 首版原生控制端已经完成从 0 到可编译工程的落地，位于 `Android/`。

### 已完成范围

- Kotlin + Gradle Kotlin DSL 单模块工程
- Compose Material 3 UI
- 根路由按连接状态切换
- `Remote / Devices / Settings` 三页结构
- 手动连接弹窗
- Bonjour 发现 `_neoremote._tcp.`
- TCP 长连接，启用 `tcpNoDelay`
- JSON v1 协议编解码
- 基于 brace-depth 的 JSON 流式拆包
- 最近设备 / 上次连接 / 手动连接草稿持久化
- 自动恢复连接
- 每 2 秒心跳
- 服务端 `ack / status / heartbeat` 处理
- 触控手势映射：
  - 单指移动
  - 单击
  - 双指纵向滚动
  - 双击后拖拽
- 基础震动反馈

### 关键代码位置

- 入口与 UI
  - `Android/app/src/main/java/com/neoremote/android/MainActivity.kt`
  - `Android/app/src/main/java/com/neoremote/android/ui/NeoRemoteApp.kt`
  - `Android/app/src/main/java/com/neoremote/android/ui/screens/OnboardingScreen.kt`
  - `Android/app/src/main/java/com/neoremote/android/ui/screens/ConnectedScreens.kt`
- 核心能力
  - `Android/app/src/main/java/com/neoremote/android/core/discovery/DiscoveryService.kt`
  - `Android/app/src/main/java/com/neoremote/android/core/transport/RemoteTransport.kt`
  - `Android/app/src/main/java/com/neoremote/android/core/protocol/ProtocolCodec.kt`
  - `Android/app/src/main/java/com/neoremote/android/core/protocol/JsonMessageStreamDecoder.kt`
  - `Android/app/src/main/java/com/neoremote/android/core/session/SessionCoordinatorViewModel.kt`
  - `Android/app/src/main/java/com/neoremote/android/core/touch/TouchSurfaceInputAdapter.kt`
  - `Android/app/src/main/java/com/neoremote/android/core/persistence/DeviceRegistry.kt`
- 触控区域
  - `Android/app/src/main/java/com/neoremote/android/ui/components/TouchSurfaceView.kt`

### 已补测试

- `Android/app/src/test/java/com/neoremote/android/protocol/ProtocolCodecTest.kt`
- `Android/app/src/test/java/com/neoremote/android/protocol/JsonMessageStreamDecoderTest.kt`
- `Android/app/src/test/java/com/neoremote/android/persistence/DeviceRegistryTest.kt`
- `Android/app/src/test/java/com/neoremote/android/touch/TouchSurfaceInputAdapterTest.kt`
- `Android/app/src/test/java/com/neoremote/android/session/SessionCoordinatorViewModelTest.kt`
- `Android/app/src/androidTest/java/com/neoremote/android/ui/NeoRemoteUiSmokeTest.kt`

## Android 已验证结果

以下结果已经在命令行闭环跑通过：

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`

当时的 debug APK 产物路径为：

- `Android/app/build/outputs/apk/debug/app-debug.apk`

## Android 当前遗留问题

Android 代码和命令行 Gradle wrapper 已经可用，但 Android Studio 本地调试链路当前仍未完全打通。

### 已确认事实

- `Android/settings.gradle.kts` 已正确包含 `include(":app")`
- 命令行执行 `./gradlew --version` 已可正常返回 `Gradle 8.9`
- 项目 wrapper 已改为使用本地可用的 Gradle 分发包，避免远程下载超时

### 当前问题表现

- Android Studio 仍可能卡在旧的临时目录缓存状态
- `Run/Debug Configurations` 中无法稳定识别 `app` module
- 因当前使用场景是从 Windows 远程操作 Mac，且没有便捷的本机 `adb` 联调路径，所以尚未完成 Android 真机/模拟器闭环验证

### 这意味着什么

- Android 工程本身不是阻塞点
- Android Studio IDE 状态仍是单独问题
- 后续如果要继续 Android 联调，优先从 Windows 本地 `adb` 或真实设备安装 APK 入手，而不是继续在当前 Mac 的 Android Studio 上死磕

## Windows 端当前状态

`Windows/` 当前还没有接收端实现，只有：

- `Windows/.gitkeep`

因此 Windows 端现在是一个干净起点，但它的目标非常明确：不是新做一个 Windows 专属远控产品，而是实现 NeoRemote 桌面接收端的 Windows 版本。

## Windows 端必须对齐的协议与边界

Windows 首版必须直接复用当前协议基线，不要自行扩展。

### 服务发现

- Bonjour / mDNS 服务类型：`_neoremote._tcp.`

### 默认端口

- `50505`

### 控制端 -> 桌面端

- `move(dx, dy)`
- `tap(button)`
- `scroll(deltaY)`
- `drag(state, dx, dy)`
- `heartbeat`

### 桌面端 -> 控制端

- `ack`
- `status(message)`
- `heartbeat`

### 产品边界

Windows 首版不要做：

- 认证 / 配对码
- 加密
- 文件传输
- 快捷键面板
- 屏幕画面回传
- 绝对坐标点击
- 平台自定义协议分叉

## Windows 端建议的首版拆分

建议按下面顺序推进：

### 第一阶段：核心服务先跑通

- 搭一个最小可运行的 Windows 原生接收端工程
- 发布 `_neoremote._tcp.`
- TCP 监听 `50505`
- 解析 JSON v1 协议
- 回包 `ack / status / heartbeat`
- 暂时先把收到的命令打印成日志，确认和 iOS / Android 都能握手

### 第二阶段：输入注入

- 将 `move / tap / scroll / drag` 映射到 Windows 鼠标事件
- 先保证鼠标移动、左键点击、滚轮、拖拽 4 条主路径稳定

### 第三阶段：桌面端壳层

- 增加基础状态页或托盘入口
- 增加监听开关、状态展示、最近事件
- 对齐 macOS 端的基础产品形态，但不必一开始就追求完全一致

## 建议的 Windows 首个里程碑

Windows 第一批交付，建议只追这个目标：

- 手机端能发现 Windows 桌面端
- 能连接成功
- 能收到 `ack`
- 能持续收到 `heartbeat`
- 能通过 `move / tap / scroll / drag` 控制 Windows 鼠标

只要这个里程碑打通，NeoRemote 就从“Apple 内部闭环”变成了真正的跨平台闭环。

## 当前建议

接下来优先级建议如下：

1. 暂时停止在当前环境继续折腾 Android Studio IDE 调试
2. 保留 Android 现有实现和命令行验证结果，作为已完成阶段
3. 正式开始 `Windows/` 接收端首版
4. Windows 首版优先对齐 macOS 的协议与连接语义，不先做 UI 花活

## 备注

如果后续有人继续接 Android：

- 应以 `Android/` 现有工程为基线继续迭代
- 不要再按“Android 尚未开始”的旧前提重新规划
- 先补真实设备安装与联调，再考虑 Android Studio 本地 IDE 体验优化
