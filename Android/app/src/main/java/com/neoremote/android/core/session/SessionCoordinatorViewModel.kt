package com.neoremote.android.core.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neoremote.android.core.discovery.AndroidNsdDiscoveryService
import com.neoremote.android.core.discovery.DiscoveryService
import com.neoremote.android.core.model.ConnectionFailure
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionStatus
import com.neoremote.android.core.model.SessionUiState
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.TouchSurfaceSemanticEvent
import com.neoremote.android.core.model.TransportConnectionState
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SessionUiState(
            recentDevices = registry.loadRecentDevices(),
            lastConnectedEndpoint = registry.loadLastConnectedDevice(),
            manualConnectDraft = registry.loadManualDraft(),
            hapticsEnabled = registry.loadHapticsEnabled(),
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

        val lastConnected = uiState.value.lastConnectedEndpoint
        if (lastConnected != null) {
            connect(lastConnected, isRecovery = true)
        } else {
            _uiState.update {
                it.copy(
                    status = SessionStatus.DISCOVERING,
                    route = SessionRoute.ONBOARDING,
                    statusMessage = "正在扫描局域网桌面端",
                )
            }
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

    fun connect(endpoint: DesktopEndpoint, isRecovery: Boolean = false) {
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
            TouchSurfaceSemanticEvent.MOVING -> Unit
            TouchSurfaceSemanticEvent.TAP -> showHud("已点击")
            TouchSurfaceSemanticEvent.SCROLLING -> showHud("双指滚动")
            TouchSurfaceSemanticEvent.DRAG_STARTED -> showHud("开始拖拽")
            TouchSurfaceSemanticEvent.DRAG_CHANGED -> showHud("拖拽中")
            TouchSurfaceSemanticEvent.DRAG_ENDED -> showHud("结束拖拽")
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

    private fun bindDiscovery() {
        discoveryService.onUpdate = { devices ->
            scope.launch {
                _uiState.update { current ->
                    when {
                        current.activeEndpoint == null && devices.isEmpty() -> {
                            current.copy(
                                discoveredDevices = devices,
                                status = SessionStatus.DISCOVERING,
                                statusMessage = "暂未发现桌面端，可手动输入地址",
                            )
                        }

                        current.activeEndpoint == null -> {
                            current.copy(
                                discoveredDevices = devices,
                                status = SessionStatus.DISCONNECTED,
                                statusMessage = "发现 ${devices.size} 台桌面端",
                            )
                        }

                        else -> current.copy(discoveredDevices = devices)
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
                    is RemoteCommand.Scroll -> "正在滚动桌面内容"
                    is RemoteCommand.Move -> "远程控制中"
                    is RemoteCommand.Drag -> when (command.state) {
                        com.neoremote.android.core.model.DragState.STARTED -> "桌面拖拽已开始"
                        com.neoremote.android.core.model.DragState.CHANGED -> "拖拽进行中"
                        com.neoremote.android.core.model.DragState.ENDED -> "拖拽已结束"
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

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(2_000)
                send(RemoteCommand.Heartbeat)
            }
        }
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
}
