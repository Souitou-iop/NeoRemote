# NeoRemote – 四端原生无线触控板软件 PRD (v2.0 详细技术选型版)

## 1. 概述
**产品名称**：NeoRemote  
**产品类型**：跨平台无线触控板与远程控制软件  
**核心架构**：移动端 (iOS/Android) 采集原生手势与提供震动 + 局域网 TCP/UDP 通信 + 桌面端 (macOS/Windows) 注入系统原生输入事件。

## 2. 核心技术架构与详细平台选型 (重点明确)

为保证“原生级别”的极低延迟与细腻震动手感，四端均放弃大前端跨端框架（如 Flutter、React Native、Electron），严格采用各平台原生语言与底层 API 进行开发。

### 2.1 iOS 移动端 (Controller)
*   **开发语言**: Swift 5.x / 6.x
*   **UI 框架**: SwiftUI (主界面与设置) + UIKit (`UIViewRepresentable` 封装)
    *   *选型细节*: SwiftUI 开发设置页更高效，但在触控板核心区域，强烈推荐使用 UIKit 的 `UIPanGestureRecognizer` 和 `UITouch` 原始事件，以获取**零延迟的原始触摸数据 (Raw Touch Data)**。SwiftUI 的高层手势 API 在高频微小移动时可能存在毫秒级过滤延迟。
*   **震动反馈 (Haptics)**:
    *   *普通点击*: `UIImpactFeedbackGenerator(style: .light/.medium)`。
    *   *高级触控 (拖拽/边界反弹)*: 引入 **CoreHaptics** (`CHHapticEngine`)。利用 `CHHapticPattern` 动态调整 `hapticIntensity` (强度) 和 `hapticSharpness` (锐度)，实现类似 MacBook 触控板的持续轻柔摩擦感。
*   **网络通信**: Apple `Network.framework` (`NWConnection`)。
    *   *配置要求*: 采用底层 TCP Socket，**必须**设置 `TCP_NODELAY` (关闭 Nagle 算法) 以防止小数据包积压，保证鼠标轨迹平滑。UDP 用于局域网设备广播发现。

### 2.2 Android 移动端 (Controller)
*   **开发语言**: Kotlin
*   **UI 框架**: Jetpack Compose + 原生 `View` 混合
    *   *选型细节*: 核心触控区需直接处理底层 `MotionEvent`。通过 Compose 的 `pointerInput` (结合 `awaitPointerEventScope`) 或传统 View 重写 `onTouchEvent` 获取高采样率的 X/Y 轴相对位移 (Delta)。必须注意避免在 touch 回调中创建新对象，防止 GC 抖动 (Jank) 导致光标卡顿。
*   **震动反馈 (Haptics)**:
    *   *核心 API*: `VibratorManager` -> `VibrationEffect` (API 26+) 和 `Vibrator` (兼容老版本)。
    *   *高级触控*: 重点使用 `VibrationEffect.createWaveform()` 或 `VibrationEffect.Composition` (API 30+) 拼接平滑震动曲线，以代码方式模拟 iOS 的细腻震感。
*   **网络通信**: Kotlin Coroutines (协程) + `java.net.Socket` (TCP 数据流) / `DatagramSocket` (UDP 发现)。为求极致性能，直接操作 Socket 字节流，不引入庞大的 HTTP 库。

### 2.3 macOS 桌面端 (Helper)
*   **开发语言**: Swift
*   **UI 框架**: AppKit (`NSStatusItem` 纯状态栏应用) 或 SwiftUI (`MenuBarExtra`)。由于完全在后台运行，只需一个菜单栏小图标提供配对和退出入口。
*   **输入注入 (Input Injection)**: **CoreGraphics** 框架。
    *   *移动*: `CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: CGPoint)`。
    *   *点击*: 构造 `.leftMouseDown` 与 `.leftMouseUp` 事件。
    *   *滚动*: `CGEvent(scrollWheelEvent2Source: ...)`。
    *   *执行*: 调用 `CGEventPost(.cghidEventTap, event)` 注入系统队列。
*   **网络通信**: `Network.framework` (`NWListener`) 开启本地 TCP 端口监听移动端连接。
*   **关键系统权限**: **必须**在产品引导页提示用户开启 "系统设置 -> 隐私与安全性 -> **辅助功能 (Accessibility)**"。若无此授权，`CGEventPost` 将被系统静默拦截，光标无法移动。

### 2.4 Windows 桌面端 (Helper)
*   **开发语言**: C# (.NET 8 / .NET 9)
*   **UI 框架**: WPF 或 WinForms (`NotifyIcon` 最小化到系统托盘)。不推荐 UWP/WinUI 3，因沙盒机制可能影响底层 API 调用及开机自启逻辑。
*   **输入注入 (Input Injection)**: Win32 API (`user32.dll`)。
    *   *核心函数*: `SendInput`。
    *   *数据结构*: `INPUT`, `MOUSEINPUT` (配合 `MOUSEEVENTF_MOVE`, `MOUSEEVENTF_LEFTDOWN`, `MOUSEEVENTF_WHEEL` 标志位)。
    *   *调用方式*: 使用 `P/Invoke` (如 `[DllImport("user32.dll")]`) 映射底层 C 函数。相对鼠标位移直接累加传入。
*   **网络通信**: `.NET` 标准库 `System.Net.Sockets.TcpListener` (监听指令) 和 `UdpClient` (响应广播)。
*   **关键系统权限**: 默认情况下可控制普通应用；**重点提示**：若用户焦点在以“管理员权限”运行的窗口（如任务管理器、部分游戏）上，Helper 应用本身也必须提升为**管理员身份运行 (UAC)**，否则 `SendInput` 将在此类窗口上失效。

### 2.5 跨端通信协议规范
*   **传输协议**: TCP 长连接 (全端启用 `TCP_NODELAY`)。
*   **数据格式 (Payload)**: 
    *   首版 (MVP) 推荐使用紧凑型 JSON，例如：`{"t":"m","dx":5.2,"dy":-2.1}`，兼顾四端序列化库的便利性与抓包调试。
    *   进阶版 (v1.0+) 强烈建议改为自定义**二进制结构体** (Binary Struct)，例如：`[1Byte 类型][2Bytes X轴][2Bytes Y轴]`，单包仅 5 字节，大幅降低解析延迟与局域网网络开销。

## 3. 功能需求与优先级
*   **P0 (MVP阶段)**: 
    *   TCP 连通 + UDP 局域网发现。
    *   单指滑动 (触发 `UIPanGestureRecognizer` / `MotionEvent` -> TCP -> `CGEvent` / `SendInput`)。
    *   单击 (触发 `VibrationEffect` / `UIImpactFeedbackGenerator`)。
*   **P1 (完善阶段)**:
    *   双指垂直滚动、双击拖拽。
    *   CoreHaptics 细腻震感曲线调优。
    *   macOS 辅助功能权限状态检测与自动弹窗引导。
    *   Windows Helper 开机自启动与 UAC 提权逻辑。

## 4. 性能与非功能指标对齐
*   **端到端延迟**: 移动端屏幕刷新捕获手指位置 (≈16ms) + JSON序列化与TCP发送 (≈2-5ms) + 局域网传输 (≈5-10ms) + 桌面端反序列化与系统注入 (≈1-2ms) = **总体目标 < 35ms**。
*   **内存与性能控制**: Android 触控拦截线程中实行 **Zero-Allocation** (零内存分配) 策略，避免触发垃圾回收；macOS/Windows 事件循环中避免内存泄露，保持 Helper 常驻内存 < 30MB。
