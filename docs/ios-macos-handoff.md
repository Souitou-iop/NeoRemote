# NeoRemote iOS 端对接摘要

本文档用于给即将开始的 macOS 连接端提供 iOS 端现状、协议和联调要点。

## 当前状态

- iOS 端已经是原生 Swift 版本，可在模拟器运行。
- 根路由为：
  - 未连接：进入 Onboarding 引导壳
  - 已连接：进入 Connected 壳
- 已连接壳包含 3 个 tab：
  - `Remote`
  - `Devices`
  - `Settings`
- 默认进入 `Remote` 页。

关键入口文件：

- `iOS/NeoRemote/App/NeoRemoteApp.swift`
- `iOS/NeoRemote/App/AppRootView.swift`
- `iOS/NeoRemote/App/ConnectedShellView.swift`

## 连接方式

### 1. 自动发现

- 使用 Bonjour 发现桌面端
- 服务类型：`_neoremote._tcp.`
- 代码位置：`iOS/NeoRemote/Core/DiscoveryService.swift`

### 2. 手动连接

- 用户可以输入 `host + port`
- 默认端口：`50505`
- 地址校验逻辑在：`iOS/NeoRemote/Core/DeviceRegistry.swift`

### 3. 最近设备与恢复

- 会持久化最近连接设备
- 会持久化最后一次连接目标
- App 启动时会尝试自动恢复最近桌面端连接

## 核心数据模型

定义位置：`iOS/NeoRemote/Core/Domain.swift`

### SessionStatus

- `disconnected`
- `discovering`
- `connecting`
- `connected`
- `reconnecting`
- `failed`

### DesktopPlatform

- `macOS`
- `windows`

说明：

- UI 对用户统一叫 `Desktop`
- 内部保留平台位，方便后续扩展 Windows

### DesktopEndpoint

字段：

- `id`
- `displayName`
- `host`
- `port`
- `platform`
- `lastSeenAt`
- `source`

### RemoteCommand

iOS 发给桌面端的命令：

- `move(dx, dy)`
- `tap(kind)`
- `scroll(deltaY)`
- `drag(state, dx, dy)`
- `heartbeat`

### ProtocolMessage

iOS 当前识别的桌面端消息：

- `ack`
- `status(String)`
- `heartbeat`
- `unknown(type: String)`

## 传输协议

### 传输层

- 纯 TCP 连接
- iOS 侧实现基于 `NWConnection`
- 代码位置：`iOS/NeoRemote/Core/RemoteTransport.swift`

### 编码层

- 当前为 JSON v1
- 代码位置：`iOS/NeoRemote/Core/ProtocolCodec.swift`

### iOS -> macOS 命令格式

```json
{ "type": "move", "dx": 12.3, "dy": -4.8 }
{ "type": "tap", "button": "primary" }
{ "type": "scroll", "deltaY": 18.0 }
{ "type": "drag", "state": "started", "dx": 0.0, "dy": 0.0 }
{ "type": "drag", "state": "changed", "dx": 5.0, "dy": -2.0 }
{ "type": "drag", "state": "ended", "dx": 0.0, "dy": 0.0 }
{ "type": "heartbeat" }
```

### macOS -> iOS 消息格式

```json
{ "type": "ack" }
{ "type": "status", "message": "已连接 Mac" }
{ "type": "heartbeat" }
```

说明：

- 未知 `type` 会被 iOS 识别为 `unknown`
- 当前没有做配对码、认证或加密
- 当前没有复杂 framing 协议，建议桌面端先按单条 JSON 消息模型实现

## 手势到命令映射

实现位置：`iOS/NeoRemote/Core/TouchSurfaceInputAdapter.swift`

- 单指移动：发送 `move(dx, dy)`
- 单击：发送 `tap(primary)`
- 双指垂直滚动：发送 `scroll(deltaY)`
- 双击后拖拽：发送 `drag(started / changed / ended)`

补充说明：

- 当前 UI 没有显式右键按钮
- 协议模型里保留了 `MouseButtonKind.secondary`
- 连接成功后 iOS 会每 2 秒发送一次 `heartbeat`

## 会话协调逻辑

实现位置：`iOS/NeoRemote/Core/SessionCoordinator.swift`

主要职责：

- 启动发现服务
- 发起手动/自动连接
- 处理连接状态切换
- 持久化最近设备和最后连接设备
- 转发触控事件生成的远控命令
- 维护 HUD 和状态文案
- 维持心跳

当前有一个 Debug 演示模式：

- 环境变量：`NEOREMOTE_DEMO_MODE=1`
- 打开后直接进入已连接演示页

## 当前页面状态

### Onboarding

文件：`iOS/NeoRemote/Features/Onboarding/OnboardingShellView.swift`

当前能力：

- 展示品牌头图
- 展示连接状态
- 展示 Bonjour 自动发现设备
- 展示最近连接设备
- 支持手动输入地址连接

### Remote

文件：`iOS/NeoRemote/Features/Remote/RemoteView.swift`

当前状态：

- 以大触控板为主体
- 顶部保留：
  - 页面标题 `Remote`
  - 当前设备名
  - 连接状态胶囊
  - 原生 `断开` 按钮
- 已去除大部分教学和说明文案

### Settings

文件：`iOS/NeoRemote/Features/Settings/SettingsView.swift`

当前保留内容：

- 连接策略
- 当前会话
- 维护操作

## macOS 端首版最小对接要求

建议 macOS 端第一版先满足下面这几个点：

1. 发布 Bonjour 服务 `_neoremote._tcp.`
2. 监听 TCP `50505`
3. 正常接收并解析 JSON v1 命令
4. 能把 `move / tap / scroll / drag` 映射成 macOS 鼠标事件
5. 建连成功后回一个 `ack`
6. 可选回 `status`，让 iOS 显示更明确的连接状态
7. 保持连接并响应心跳

## 已有测试

测试目录：`iOS/NeoRemoteTests`

当前已有：

- `DeviceRegistryTests.swift`
- `ProtocolCodecTests.swift`
- `TouchSurfaceInputAdapterTests.swift`
- `SessionCoordinatorTests.swift`

覆盖重点：

- 协议编解码
- 最近设备持久化
- 手势到命令映射
- 连接状态流转

## 建议的 macOS 端实现顺序

1. 先做 Bonjour 发布
2. 再做 TCP server
3. 再把 JSON 命令解析接上
4. 最后实现鼠标移动、点击、滚动、拖拽事件注入

这样可以先尽快完成和 iOS 的“可发现 + 可连接 + 可收命令”联调闭环。
