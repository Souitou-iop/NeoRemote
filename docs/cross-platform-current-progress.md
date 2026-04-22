# NeoRemote 当前进度摘要（面向 Android 与桌面端扩展）

本文档用于给后续 Android 控制端和 Windows 桌面端开发提供统一上下文，明确当前项目进度、跨平台协议边界、已完成能力和下一阶段实现约束。

## 当前项目结构

- `iOS/`
  - 已落地的 iPhone 控制端原型
  - 当前是现有产品形态和交互模型的基线
- `MacOS/`
  - 已落地的 macOS 桌面接收端
  - 当前已可运行、可联调
- `Windows/`
  - 目录已预留
  - 当前尚未开始接收端实现
- `docs/`
  - 产品、协议、跨端约束和当前进度文档

## 当前阶段结论

项目已经从“文档规划”进入“跨端原型联调”阶段。

现在的真实状态是：

- iOS 端已经能作为控制端发出远控协议命令
- macOS 端已经能作为接收端运行并执行基础控制
- Android 控制端尚未开始
- Windows 接收端尚未开始

后续目标不再只是 “iOS -> MacOS”，而是逐步形成下面这套形态：

- iOS / Android：统一作为移动控制端
- MacOS / Windows：统一作为桌面接收端

## 现阶段平台角色定义

### 控制端

- `iOS`
- `Android`

职责：

- 发现桌面端
- 发起连接
- 展示连接状态
- 将触控输入翻译成统一远控协议命令

### 桌面接收端

- `MacOS`
- `Windows`

职责：

- 发布局域网服务
- 接收 TCP 连接
- 解析统一协议
- 将远控命令映射为系统鼠标事件
- 回传 `ack / status / heartbeat`

## iOS 端现状

iOS 端当前是控制端基线，已有以下能力：

- Bonjour 自动发现桌面端
- 手动输入 `host + port` 连接
- 最近设备与上次连接恢复
- 连接状态管理
- 触控板输入映射为远控协议命令
- `Remote / Devices / Settings` 基础结构

关键目录与文件：

- `iOS/NeoRemote/App/`
- `iOS/NeoRemote/Core/`
- `iOS/NeoRemote/Features/Onboarding/OnboardingShellView.swift`
- `iOS/NeoRemote/Features/Remote/RemoteView.swift`
- `iOS/NeoRemote/Features/Settings/SettingsView.swift`

当前需要把 iOS 看作 Android 端的行为参考，而不是只看作某个平台自己的实现。

## macOS 端现状

macOS 端已经完成首版闭环，目录位于 `MacOS/`。

当前已完成能力：

- SwiftUI 主窗口 Dashboard
- 设置页
- 菜单栏状态入口
- Bonjour 发布
- TCP `50505` 监听
- JSON v1 协议解析
- 单连接占用策略
- 辅助功能权限检测与引导
- 鼠标 `move / tap / scroll / drag` 事件注入
- `ack / status / heartbeat` 回包
- 监听开关
- 菜单栏显示开关
- 监听音效开关
- 最近事件日志

当前 UI 状态：

- 主窗口只保留运行状态、当前会话和主操作
- 设置页承载权限、最近事件、监听偏好等次级信息
- 菜单栏使用纯图标态
- 当前菜单栏图标开关态为：
  - 开：`bolt.horizontal.fill`
  - 关：`bolt.horizontal`

## Windows 端当前定位

Windows 端还没有开始写，但它不是一个自由发挥的新产品，而是 MacOS 桌面接收端的并列实现。

Windows 首版定位必须是：

- 同一协议语义
- 同一连接模型
- 同一产品边界
- 同一移动端交互预期

也就是说，Windows 端首版不是“Windows 专属远控工具”，而是 NeoRemote 桌面接收端在 Windows 上的实现版本。

## 当前工程结构收口情况

此前为方便构建和启动，仓库根目录曾出现 macOS 专用目录。现在已经收回到 `MacOS/` 内部：

- 启动脚本：`MacOS/script/build_and_run.sh`
- 构建产物：`MacOS/dist/NeoRemoteMac.app`

当前根目录不再保留独立的 `dist/` 和 `script/`。

## 当前跨平台协议基线

Android、MacOS、Windows 后续都需要围绕同一协议基线实现。首版不要自行扩展。

### 服务发现

- Bonjour 服务类型：`_neoremote._tcp.`

### 连接方式

- 纯 TCP
- 默认端口：`50505`

### 控制端 -> 桌面端 入站命令

- `move(dx, dy)`
- `tap(kind)`
- `scroll(deltaY)`
- `drag(state, dx, dy)`
- `heartbeat`

示例：

```json
{ "type": "move", "dx": 12.3, "dy": -4.8 }
{ "type": "tap", "button": "primary" }
{ "type": "scroll", "deltaY": 18.0 }
{ "type": "drag", "state": "started", "dx": 0.0, "dy": 0.0 }
{ "type": "drag", "state": "changed", "dx": 5.0, "dy": -2.0 }
{ "type": "drag", "state": "ended", "dx": 0.0, "dy": 0.0 }
{ "type": "heartbeat" }
```

### 桌面端 -> 控制端 回包消息

- `ack`
- `status(message)`
- `heartbeat`

示例：

```json
{ "type": "ack" }
{ "type": "status", "message": "已连接 Desktop" }
{ "type": "heartbeat" }
```

## 跨平台硬约束

这部分是后续 Android 和 Windows 实现都必须遵守的边界。

### 产品边界约束

- 当前产品形态是“远程触控板”，不是“远程桌面”
- 首版不要加入屏幕画面回传
- 首版不要加入绝对坐标点击
- 首版不要加入窗口级控制协议
- 首版不要加入认证、配对码、加密、文件传输、快捷键面板等扩展能力

### 协议约束

- 必须复用当前 JSON v1 协议语义
- 不要自行新增消息类型
- 不要修改现有字段名
- 不要调整默认端口
- 不要把心跳、状态消息或 busy 语义改成平台自定义版本
- 如果后续确实要扩协议，必须先保证与现有实现兼容

### 连接模型约束

- Bonjour 始终可被发现
- 同一时间只允许 `1` 台客户端接管
- 有活跃连接时，新的连接会收到 busy/status 后被拒绝
- 桌面端应持续支持 `ack / status / heartbeat`

### 交互模型约束

- 单指移动 -> `move(dx, dy)`
- 单击 -> `tap(primary)`
- 双指纵向滚动 -> `scroll(deltaY)`
- 双击后拖拽 -> `drag(started / changed / ended)`
- 首版不要额外塞入复杂手势，例如三指、多段快捷操作、悬浮工具轮盘

## Android 原生实现约束

Android 端必须按“原生 Android 客户端”实现，但原生不等于自由发挥。

### 技术实现约束

- 使用 Kotlin 实现
- UI 使用 Jetpack Compose
- 网络发现、TCP 连接、状态管理、输入映射分层实现，不要把协议、UI、连接逻辑揉在一个页面里
- 不要引入 Flutter、React Native、KMM 或额外跨端框架
- 不要为了首版提前引入复杂插件化、脚本化、MVI 基建或过度抽象

### 页面与信息架构约束

- 首版页面结构建议与 iOS 对齐：
  - 未连接：连接引导页
  - 已连接：`Remote / Devices / Settings`
- `Remote` 仍然应是主入口
- 设置页先承接连接策略、当前会话、维护操作
- 视觉可以 Android 化，但信息架构和主流程不要偏离 iOS

### 状态机约束

- 会话状态命名和流转尽量与 iOS 对齐：
  - `disconnected`
  - `discovering`
  - `connecting`
  - `connected`
  - `reconnecting`
  - `failed`
- 最近设备、最后连接目标、自动恢复策略都应保留
- 接到 `status(message)` 时，应按“服务端状态反馈”处理，不要在 Android 端自行发明另一套解释模型

### Android 联调约束

- Android 首版必须以“直接和当前 MacOS 端联调成功”为完成标准
- 不能只做到本地 UI 和假数据演示
- 至少要验证：
  - 能发现 Desktop
  - 能连接 Desktop
  - 能收到 `ack/status/heartbeat`
  - 能实际驱动 `move / tap / scroll / drag`

## Windows 原生实现约束

Windows 端必须按“原生 Windows 桌面接收端”实现，也不能偏离当前桌面接收端模型。

### 技术实现约束

- 使用 Windows 原生技术栈实现
- 首版优先保证协议与输入注入闭环，不要先做复杂桌面 UI
- 桌面服务、协议解析、会话状态、输入注入应分层
- 不要为了首版引入 Electron、Flutter Desktop 或额外跨端 UI 框架

### 产品行为约束

- Windows 端角色必须和 MacOS 一致，都是“桌面接收端”
- 必须作为被控桌面存在，而不是控制端
- 首版不需要追求 MacOS 界面一比一复制，但连接模型和能力边界必须一致

### Windows 桌面能力约束

- 必须监听同一 TCP 端口 `50505`
- 必须支持同一组 JSON v1 命令
- 必须回传 `ack / status / heartbeat`
- 必须遵守单连接占用策略
- 必须将 `move / tap / scroll / drag` 映射到 Windows 鼠标输入
- 首版先聚焦输入注入、连接状态、基础设置，不要扩到键盘、文件、窗口管理

### Windows 联调约束

- Windows 首版必须以“可被 iOS 和 Android 控制端直接发现并控制”为完成标准
- 不能只做到本地 TCP demo
- 至少要验证：
  - 能被移动端发现
  - 能建立连接
  - 能正确回 `ack/status/heartbeat`
  - 能实际执行鼠标移动、点击、滚动、拖拽

## 当前产品边界解释

这里是 Android 和 Windows 都需要提前知道的一点：

当前产品形态不是“远程桌面”，而是“远程触控板”。

这意味着：

- 控制端发送的是相对位移和点击命令
- 桌面端接收后注入全局鼠标事件
- 当前没有屏幕画面回传
- 当前没有绝对坐标点击
- 当前没有窗口级专用控制协议

因此，后续两个方向最稳的路线都不是发明新协议，而是先围绕现有控制模型打通。

## 已完成验证

当前已真实验证的只有 MacOS 端：

- `swift test --package-path MacOS`
- `./MacOS/script/build_and_run.sh --verify`

当前 MacOS 已包含协议、服务状态、输入规划等测试，并且已经完成真实运行验证。

Android 与 Windows 端目前尚未开始，不应在文档里假装它们已经具备可运行能力。

## 建议的下一阶段顺序

建议按下面顺序推进，优先最大化复用现有成果：

1. 先做 Android 控制端，对齐 iOS 现有控制模型
2. 再做 Windows 桌面接收端，对齐 MacOS 现有协议与连接模型
3. 等 Android + Windows 首版联调跑通后，再考虑跨平台增强

## Android 首版最小目标

如果只追求 Android 最小闭环，建议至少做到：

1. 发现 Desktop
2. 手动连接 Desktop
3. 建立 TCP 会话
4. 发送 `move / tap / scroll / drag / heartbeat`
5. 接收 `ack / status / heartbeat`
6. 展示连接状态与错误状态

做到这一步，就已经能和现在的 MacOS 端完成真实联调。

## Windows 首版最小目标

如果只追求 Windows 最小闭环，建议至少做到：

1. 发布 `_neoremote._tcp.` 服务
2. 监听 TCP `50505`
3. 接收并解析 JSON v1 命令
4. 将 `move / tap / scroll / drag` 映射成 Windows 鼠标事件
5. 在建连后回传 `ack`
6. 支持 `status / heartbeat`
7. 遵守单连接占用策略

做到这一步，就已经能和 iOS / Android 控制端形成统一协议下的桌面接收端实现。
