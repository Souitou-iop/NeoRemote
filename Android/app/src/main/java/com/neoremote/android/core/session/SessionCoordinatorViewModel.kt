package com.neoremote.android.core.session

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neoremote.android.core.discovery.AndroidNsdDiscoveryService
import com.neoremote.android.core.discovery.DiscoveryService
import com.neoremote.android.core.model.ConnectionFailure
import com.neoremote.android.core.model.ControlMode
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.DesktopPlatform
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionStatus
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.TouchSurfaceSemanticEvent
import com.neoremote.android.core.model.TransportConnectionState
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.persistence.DeviceRegistry
import com.neoremote.android.core.persistence.SharedPreferencesStore
import com.neoremote.android.core.protocol.ProtocolCodec
import com.neoremote.android.core.transport.MockRemoteTransport
import com.neoremote.android.core.transport.RemoteTransport
import com.neoremote.android.core.transport.SocketRemoteTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionCoordinatorViewModel(
    private val registry: DeviceRegistry,
    private val discoveryService: DiscoveryService,
    private val transportFactory: () -> RemoteTransport,
    private val codec: ProtocolCodec = ProtocolCodec(),
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val isSelfEndpoint: (DesktopEndpoint) -> Boolean = ::isSelfAndroidReceiverEndpoint,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SessionUiState(
            recentDevices = registry.loadRecentDevices().filterNot(isSelfEndpoint),
            lastConnectedEndpoint = registry.loadLastConnectedDevice()?.takeUnless(isSelfEndpoint),
            manualConnectDraft = registry.loadManualDraft(),
            hapticsEnabled = registry.loadHapticsEnabled(),
            controlMode = registry.loadControlMode(),
            touchSensitivitySettings = registry.loadTouchSensitivitySettings(),
        ),
    )
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private var transport: RemoteTransport? = null
    private var hudClearJob: Job? = null
    private var heartbeatJob: Job? = null
    private var hasStarted = false
    private var connectionGeneration = 0L

    init {
        bindDiscovery()
    }

    fun start() {
        if (hasStarted) return
        hasStarted = true
        discoveryService.start()

        _uiState.update {
            it.copy(
                status = SessionStatus.DISCOVERING,
                route = SessionRoute.ONBOARDING,
                activeEndpoint = null,
                statusMessage = "正在扫描局域网设备",
            )
        }
    }

    fun refreshDiscovery() {
        _uiState.update {
            it.copy(
                status = if (it.activeEndpoint == null) SessionStatus.DISCOVERING else it.status,
                statusMessage = "重新扫描桌面端",
            )
        }
        discoveryService.refresh()
    }

    fun updateManualDraft(host: String, port: String) {
        val draft = uiState.value.manualConnectDraft.copy(host = host, port = port)
        registry.saveManualDraft(draft)
        _uiState.update { it.copy(manualConnectDraft = draft) }
    }

    fun connectUsingManualDraft() {
        runCatching {
            registry.validate(
                host = uiState.value.manualConnectDraft.host,
                portText = uiState.value.manualConnectDraft.port,
            )
        }.onSuccess { endpoint ->
            connect(endpoint, isRecovery = false)
        }.onFailure { error ->
            val message = (error as? ConnectionFailure)?.message ?: error.message ?: "连接失败"
            _uiState.update {
                it.copy(
                    status = SessionStatus.FAILED,
                    route = SessionRoute.ONBOARDING,
                    errorMessage = message,
                )
            }
        }
    }

    fun connectUsingAdbWiredDebug() {
        val host = ADB_WIRED_HOST
        val port = ADB_WIRED_PORT
        updateManualDraft(host = host, port = port.toString())
        connect(
            DesktopEndpoint(
                displayName = "ADB Wired Desktop",
                host = host,
                port = port,
                source = com.neoremote.android.core.model.EndpointSource.MANUAL,
                lastSeenAt = System.currentTimeMillis(),
            ),
            isRecovery = false,
        )
    }

    fun connect(endpoint: DesktopEndpoint, isRecovery: Boolean = false) {
        if (isSelfEndpoint(endpoint)) {
            _uiState.update {
                it.copy(
                    status = SessionStatus.DISCONNECTED,
                    route = SessionRoute.ONBOARDING,
                    activeEndpoint = null,
                    errorMessage = "不能连接本机 Android 被控端",
                    statusMessage = "已阻止本机自连",
                )
            }
            return
        }

        val generation = ++connectionGeneration
        heartbeatJob?.cancel()
        heartbeatJob = null
        transport?.let { previousTransport ->
            previousTransport.onStateChange = null
            previousTransport.onMessage = null
            previousTransport.disconnect()
        }
        val nextTransport = transportFactory()
        transport = nextTransport
        bindTransport(nextTransport, generation)
        _uiState.update {
            it.copy(
                activeEndpoint = endpoint,
                status = if (isRecovery) SessionStatus.RECONNECTING else SessionStatus.CONNECTING,
                route = SessionRoute.ONBOARDING,
                errorMessage = null,
                statusMessage = if (isRecovery) {
                    "正在恢复与 ${endpoint.displayName} 的连接"
                } else {
                    "正在连接 ${endpoint.displayName}"
                },
            )
        }
        nextTransport.connect(endpoint)
    }

    fun disconnect() {
        ++connectionGeneration
        heartbeatJob?.cancel()
        heartbeatJob = null
        transport?.let { activeTransport ->
            activeTransport.onStateChange = null
            activeTransport.onMessage = null
            activeTransport.disconnect()
        }
        transport = null
        _uiState.update {
            it.copy(
                status = SessionStatus.DISCONNECTED,
                route = SessionRoute.ONBOARDING,
                activeEndpoint = null,
                statusMessage = "已断开连接",
            )
        }
    }

    fun clearRecentDevices() {
        registry.clearRecentDevices()
        _uiState.update {
            it.copy(
                recentDevices = emptyList(),
                lastConnectedEndpoint = null,
            )
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        registry.saveHapticsEnabled(enabled)
        _uiState.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setCursorSensitivity(value: Double) {
        updateTouchSensitivity(
            uiState.value.touchSensitivitySettings.copy(cursorSensitivity = value)
        )
    }

    fun setSwipeSensitivity(value: Double) {
        updateTouchSensitivity(
            uiState.value.touchSensitivitySettings.copy(swipeSensitivity = value)
        )
    }

    fun setControlMode(mode: ControlMode) {
        registry.saveControlMode(mode)
        _uiState.update { it.copy(controlMode = mode) }
    }

    fun enterDemoMode() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        discoveryService.stop()
        transport?.disconnect()
        transport = MockRemoteTransport()

        _uiState.update {
            it.copy(
                status = SessionStatus.CONNECTED,
                route = SessionRoute.CONNECTED,
                activeEndpoint = DesktopEndpoint(
                    id = "demo-endpoint",
                    displayName = "NeoRemote Demo",
                    host = "demo.local",
                    port = 50505,
                    platform = com.neoremote.android.core.model.DesktopPlatform.MAC_OS,
                    lastSeenAt = System.currentTimeMillis(),
                    source = com.neoremote.android.core.model.EndpointSource.MANUAL,
                ),
                errorMessage = null,
                statusMessage = "功能演示",
            )
        }
        showHud("演示模式")
    }

    fun handleTouchOutput(output: TouchSurfaceOutput) {
        output.commands.forEach(::send)
        when (output.semanticEvent) {
            TouchSurfaceSemanticEvent.PRIMARY_TAP -> showHud("左键点击")
            TouchSurfaceSemanticEvent.SECONDARY_TAP -> showHud("右键点击")
            TouchSurfaceSemanticEvent.MIDDLE_TAP -> showHud("中键点击")
            TouchSurfaceSemanticEvent.SCROLLING -> Unit
            TouchSurfaceSemanticEvent.PRIMARY_DRAG_STARTED -> showHud("左键拖拽开始")
            TouchSurfaceSemanticEvent.PRIMARY_DRAG_CHANGED -> Unit
            TouchSurfaceSemanticEvent.PRIMARY_DRAG_ENDED -> showHud("左键拖拽结束")
            TouchSurfaceSemanticEvent.SECONDARY_DRAG_STARTED -> showHud("右键拖拽开始")
            TouchSurfaceSemanticEvent.SECONDARY_DRAG_CHANGED -> Unit
            TouchSurfaceSemanticEvent.SECONDARY_DRAG_ENDED -> showHud("右键拖拽结束")
            null -> Unit
        }
    }

    fun send(command: RemoteCommand) {
        if (uiState.value.status != SessionStatus.CONNECTED) return
        val activeTransport = transport ?: return
        runCatching {
            activeTransport.send(codec.encode(command))
            handleSemanticUpdate(command)
        }.onFailure { error ->
            _uiState.update {
                it.copy(errorMessage = "命令编码失败：${error.message}")
            }
        }
    }

    fun sendVideoAction(action: VideoActionKind) {
        send(RemoteCommand.VideoAction(action))
        showHud(action.hudText)
    }

    fun sendSystemAction(action: SystemAction) {
        send(RemoteCommand.SystemActionCommand(action))
        showHud(action.hudText)
    }

    fun sendScreenGesture(
        kind: ScreenGestureKind,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        durationMs: Long,
    ) {
        send(
            RemoteCommand.ScreenGesture(
                kind = kind,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = durationMs,
            ),
        )
        showHud(
            when (kind) {
                ScreenGestureKind.TAP -> "点击"
                ScreenGestureKind.LONG_PRESS -> "长按"
                ScreenGestureKind.SWIPE -> "滑动"
                ScreenGestureKind.UNKNOWN -> "未知手势"
            },
        )
    }

    private fun bindDiscovery() {
        discoveryService.onUpdate = { devices ->
            scope.launch {
                val visibleDevices = devices.filterNot(isSelfEndpoint)
                _uiState.update { current ->
                    when {
                        current.activeEndpoint == null && visibleDevices.isEmpty() -> {
                            current.copy(
                                discoveredDevices = visibleDevices,
                                status = SessionStatus.DISCOVERING,
                                statusMessage = "暂未发现桌面端，可手动输入地址",
                            )
                        }

                        current.activeEndpoint == null -> {
                            current.copy(
                                discoveredDevices = visibleDevices,
                                status = SessionStatus.DISCONNECTED,
                                statusMessage = "发现 ${visibleDevices.size} 台桌面端",
                            )
                        }

                        else -> current.copy(discoveredDevices = visibleDevices)
                    }
                }
            }
        }
    }

    private fun bindTransport(activeTransport: RemoteTransport, generation: Long) {
        activeTransport.onStateChange = { state ->
            scope.launch {
                if (generation == connectionGeneration && transport === activeTransport) {
                    handleTransportStateChange(state)
                }
            }
        }
        activeTransport.onMessage = { message ->
            scope.launch {
                if (generation == connectionGeneration && transport === activeTransport) {
                    handleProtocolMessage(message)
                }
            }
        }
    }

    private fun handleTransportStateChange(state: TransportConnectionState) {
        when (state) {
            TransportConnectionState.Idle -> Unit
            TransportConnectionState.Connecting -> Unit
            TransportConnectionState.Connected -> {
                val endpoint = uiState.value.activeEndpoint ?: return
                registry.upsertRecent(endpoint)
                registry.saveLastConnected(endpoint)
                _uiState.update {
                    it.copy(
                        status = SessionStatus.CONNECTED,
                        route = SessionRoute.CONNECTED,
                        recentDevices = registry.loadRecentDevices(),
                        lastConnectedEndpoint = endpoint,
                        statusMessage = "已连接 ${endpoint.displayName}",
                    )
                }
                sendClientHello()
                showHud("连接成功")
                startHeartbeat()
            }

            is TransportConnectionState.Disconnected -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                _uiState.update {
                    it.copy(
                        status = SessionStatus.DISCONNECTED,
                        route = SessionRoute.ONBOARDING,
                        activeEndpoint = null,
                        errorMessage = state.errorDescription ?: it.errorMessage,
                        statusMessage = "连接已断开",
                    )
                }
            }

            is TransportConnectionState.Failed -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                _uiState.update {
                    it.copy(
                        status = SessionStatus.FAILED,
                        route = SessionRoute.ONBOARDING,
                        errorMessage = state.errorDescription,
                        statusMessage = "连接失败",
                    )
                }
            }
        }
    }

    private fun handleProtocolMessage(message: ProtocolMessage) {
        when (message) {
            ProtocolMessage.Ack -> {
                _uiState.update { it.copy(statusMessage = "桌面端已确认连接") }
            }

            is ProtocolMessage.Status -> {
                _uiState.update { it.copy(statusMessage = message.message) }
            }

            ProtocolMessage.Heartbeat -> {
                _uiState.update { it.copy(statusMessage = "桌面端在线") }
            }

            is ProtocolMessage.Unknown -> {
                _uiState.update { it.copy(statusMessage = "收到未识别消息：${message.type}") }
            }
        }
    }

    private fun handleSemanticUpdate(command: RemoteCommand) {
        _uiState.update {
            it.copy(
                statusMessage = when (command) {
                    is RemoteCommand.ClientHello -> it.statusMessage
                    is RemoteCommand.Scroll -> it.statusMessage
                    is RemoteCommand.Move -> it.statusMessage
                    is RemoteCommand.Drag -> when (command.state) {
                        com.neoremote.android.core.model.DragState.STARTED -> "${command.button.displayText}拖拽已开始"
                        com.neoremote.android.core.model.DragState.CHANGED -> it.statusMessage
                        com.neoremote.android.core.model.DragState.ENDED -> "${command.button.displayText}拖拽已结束"
                    }

                    is RemoteCommand.SystemActionCommand -> command.action.hudText
                    is RemoteCommand.VideoAction -> command.action.hudText
                    is RemoteCommand.ScreenGesture -> when (command.kind) {
                        ScreenGestureKind.TAP -> "屏幕点击"
                        ScreenGestureKind.LONG_PRESS -> "屏幕长按"
                        ScreenGestureKind.SWIPE -> "屏幕滑动"
                        ScreenGestureKind.UNKNOWN -> it.statusMessage
                    }
                    is RemoteCommand.Tap -> it.statusMessage
                    RemoteCommand.Heartbeat -> it.statusMessage
                },
            )
        }
    }

    private fun showHud(message: String) {
        hudClearJob?.cancel()
        _uiState.update { it.copy(lastHudMessage = message) }
        hudClearJob = scope.launch {
            delay(1_200)
            _uiState.update { current ->
                if (current.lastHudMessage == message) {
                    current.copy(lastHudMessage = null)
                } else {
                    current
                }
            }
        }
    }

    private fun updateTouchSensitivity(settings: com.neoremote.android.core.model.TouchSensitivitySettings) {
        val clamped = settings.clamped
        registry.saveTouchSensitivitySettings(clamped)
        _uiState.update { it.copy(touchSensitivitySettings = clamped) }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(2_000)
                send(RemoteCommand.Heartbeat)
            }
        }
    }

    private fun sendClientHello() {
        send(
            RemoteCommand.ClientHello(
                clientId = registry.loadOrCreateClientId(),
                displayName = (Build.MODEL ?: "").takeIf { it.isNotBlank() } ?: "Android Device",
                platform = "android",
            ),
        )
    }

    fun shutdown() {
        ++connectionGeneration
        heartbeatJob?.cancel()
        hudClearJob?.cancel()
        transport?.let { activeTransport ->
            activeTransport.onStateChange = null
            activeTransport.onMessage = null
            activeTransport.disconnect()
        }
        transport = null
        discoveryService.stop()
        scope.cancel()
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }

    companion object {
        private const val ADB_WIRED_HOST = "127.0.0.1"
        private const val ADB_WIRED_PORT = 51101

        fun provideFactory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val registry = DeviceRegistry(SharedPreferencesStore(appContext))
                    return SessionCoordinatorViewModel(
                        registry = registry,
                        discoveryService = AndroidNsdDiscoveryService(appContext),
                        transportFactory = { SocketRemoteTransport() },
                    ) as T
                }
            }
    }

    private val com.neoremote.android.core.model.MouseButtonKind.displayText: String
        get() = when (this) {
            com.neoremote.android.core.model.MouseButtonKind.PRIMARY -> "左键"
            com.neoremote.android.core.model.MouseButtonKind.SECONDARY -> "右键"
            com.neoremote.android.core.model.MouseButtonKind.MIDDLE -> "中键"
        }

    private val VideoActionKind.hudText: String
        get() = when (this) {
            VideoActionKind.SWIPE_UP -> "下一条"
            VideoActionKind.SWIPE_DOWN -> "上一条"
            VideoActionKind.SWIPE_LEFT -> "左滑"
            VideoActionKind.SWIPE_RIGHT -> "右滑"
            VideoActionKind.DOUBLE_TAP_LIKE -> "点赞"
            VideoActionKind.FAVORITE -> "收藏"
            VideoActionKind.PLAY_PAUSE -> "播放/暂停"
            VideoActionKind.BACK -> "返回"
            VideoActionKind.UNKNOWN -> "未知视频动作"
        }

    private val SystemAction.hudText: String
        get() = when (this) {
            SystemAction.BACK -> "返回"
            SystemAction.HOME -> "桌面"
            SystemAction.RECENTS -> "后台"
        }
}

private fun isSelfAndroidReceiverEndpoint(endpoint: DesktopEndpoint): Boolean {
    if (endpoint.platform != DesktopPlatform.ANDROID) return false
    val normalizedHost = endpoint.host.trim().trimEnd('.').lowercase()
    if (normalizedHost in LOOPBACK_HOSTS) return true

    val localReceiverName = "NeoRemote Android ${Build.MODEL.orEmpty()}".trim()
    return localReceiverName.isNotBlank() &&
        endpoint.displayName.equals(localReceiverName, ignoreCase = true)
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
