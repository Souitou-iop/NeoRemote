package com.neoremote.android.session

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.discovery.FakeDiscoveryService
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.DesktopPlatform
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionStatus
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.TouchSensitivitySettings
import com.neoremote.android.core.model.TransportConnectionState
import com.neoremote.android.core.persistence.DeviceRegistry
import com.neoremote.android.core.persistence.MemoryKeyValueStore
import com.neoremote.android.core.protocol.ProtocolCodec
import com.neoremote.android.core.session.SessionCoordinatorViewModel
import com.neoremote.android.core.transport.MockRemoteTransport
import com.neoremote.android.core.transport.RemoteTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var registry: DeviceRegistry
    private lateinit var discoveryService: FakeDiscoveryService
    private lateinit var transport: MockRemoteTransport
    private lateinit var viewModel: SessionCoordinatorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        registry = DeviceRegistry(MemoryKeyValueStore())
        discoveryService = FakeDiscoveryService()
        transport = MockRemoteTransport()
        viewModel = SessionCoordinatorViewModel(
            registry = registry,
            discoveryService = discoveryService,
            transportFactory = { transport },
            codec = ProtocolCodec(),
            mainDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        viewModel.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `start publishes discovered devices from discovery service`() = runTest(dispatcher) {
        discoveryService.cannedResults = listOf(
            DesktopEndpoint(
                displayName = "Mac mini",
                host = "192.168.1.2",
                port = 50505,
                source = EndpointSource.DISCOVERED,
            ),
        )

        viewModel.start()
        runCurrent()

        assertThat(viewModel.uiState.value.discoveredDevices).hasSize(1)
        assertThat(viewModel.uiState.value.status).isEqualTo(SessionStatus.DISCONNECTED)
    }

    @Test
    fun `start hides endpoint that matches local Android receiver`() = runTest(dispatcher) {
        val selfEndpoint = DesktopEndpoint(
            displayName = "NeoRemote Android This Device",
            host = "192.168.31.20",
            port = 51101,
            platform = DesktopPlatform.ANDROID,
            source = EndpointSource.DISCOVERED,
        )
        val otherEndpoint = DesktopEndpoint(
            displayName = "NeoRemote Android Tablet",
            host = "192.168.31.21",
            port = 51101,
            platform = DesktopPlatform.ANDROID,
            source = EndpointSource.DISCOVERED,
        )
        discoveryService.cannedResults = listOf(selfEndpoint, otherEndpoint)
        viewModel = SessionCoordinatorViewModel(
            registry = registry,
            discoveryService = discoveryService,
            transportFactory = { transport },
            codec = ProtocolCodec(),
            mainDispatcher = dispatcher,
            isSelfEndpoint = { it == selfEndpoint },
        )

        viewModel.start()
        runCurrent()

        assertThat(viewModel.uiState.value.discoveredDevices).containsExactly(otherEndpoint)
        assertThat(viewModel.uiState.value.statusMessage).isEqualTo("发现 1 台桌面端")
    }

    @Test
    fun `connect rejects local Android receiver endpoint`() = runTest(dispatcher) {
        val selfEndpoint = DesktopEndpoint(
            displayName = "NeoRemote Android This Device",
            host = "192.168.31.20",
            port = 51101,
            platform = DesktopPlatform.ANDROID,
            source = EndpointSource.DISCOVERED,
        )
        viewModel = SessionCoordinatorViewModel(
            registry = registry,
            discoveryService = discoveryService,
            transportFactory = { transport },
            codec = ProtocolCodec(),
            mainDispatcher = dispatcher,
            isSelfEndpoint = { it == selfEndpoint },
        )

        viewModel.connect(selfEndpoint)
        runCurrent()

        assertThat(viewModel.uiState.value.activeEndpoint).isNull()
        assertThat(viewModel.uiState.value.status).isEqualTo(SessionStatus.DISCONNECTED)
        assertThat(viewModel.uiState.value.errorMessage).isEqualTo("不能连接本机 Android 被控端")
        assertThat(transport.sentPayloads).isEmpty()
    }

    @Test
    fun `start keeps recent device available without auto connecting stale endpoint`() = runTest(dispatcher) {
        val staleEndpoint = DesktopEndpoint(
            displayName = "Old Android Tablet",
            host = "10.46.147.91",
            port = 51101,
            source = EndpointSource.MANUAL,
        )
        registry.upsertRecent(staleEndpoint)
        registry.saveLastConnected(staleEndpoint)
        viewModel = SessionCoordinatorViewModel(
            registry = registry,
            discoveryService = discoveryService,
            transportFactory = { transport },
            codec = ProtocolCodec(),
            mainDispatcher = dispatcher,
        )

        viewModel.start()
        runCurrent()

        assertThat(viewModel.uiState.value.activeEndpoint).isNull()
        assertThat(viewModel.uiState.value.status).isEqualTo(SessionStatus.DISCOVERING)
        assertThat(viewModel.uiState.value.recentDevices).isNotEmpty()
        assertThat(transport.sentPayloads).isEmpty()
    }

    @Test
    fun `successful connect switches route to connected and persists recent device`() = runTest(dispatcher) {
        val endpoint = DesktopEndpoint(
            displayName = "Office Desktop",
            host = "10.0.0.8",
            port = 50505,
            source = EndpointSource.MANUAL,
        )

        viewModel.connect(endpoint)
        runCurrent()

        assertThat(viewModel.uiState.value.status).isEqualTo(SessionStatus.CONNECTED)
        assertThat(viewModel.uiState.value.route).isEqualTo(SessionRoute.CONNECTED)
        assertThat(registry.loadRecentDevices()).hasSize(1)
        viewModel.disconnect()
    }

    @Test
    fun `heartbeat is sent every two seconds after connection`() = runTest(dispatcher) {
        val endpoint = DesktopEndpoint(
            displayName = "Office Desktop",
            host = "10.0.0.8",
            port = 50505,
            source = EndpointSource.MANUAL,
        )

        viewModel.connect(endpoint)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        val payloads = transport.sentPayloads.map { ProtocolCodec().decodeCommand(it) }
        assertThat(payloads).contains(RemoteCommand.Heartbeat)
        viewModel.disconnect()
    }

    @Test
    fun `system action sends command while connected`() = runTest(dispatcher) {
        val endpoint = DesktopEndpoint(
            displayName = "Android Tablet",
            host = "10.0.0.20",
            port = 51101,
            source = EndpointSource.MANUAL,
        )

        viewModel.connect(endpoint)
        runCurrent()
        viewModel.sendSystemAction(SystemAction.HOME)
        runCurrent()

        val payloads = transport.sentPayloads.map { ProtocolCodec().decodeCommand(it) }
        assertThat(payloads).contains(RemoteCommand.SystemActionCommand(SystemAction.HOME))
        assertThat(viewModel.uiState.value.lastHudMessage).isEqualTo("桌面")
        viewModel.disconnect()
    }

    @Test
    fun `status message from desktop updates ui state`() = runTest(dispatcher) {
        val endpoint = DesktopEndpoint(
            displayName = "Office Desktop",
            host = "10.0.0.8",
            port = 50505,
            source = EndpointSource.MANUAL,
        )

        viewModel.connect(endpoint)
        runCurrent()
        transport.onMessage?.invoke(ProtocolMessage.Status("已连接 Desktop"))
        runCurrent()

        assertThat(viewModel.uiState.value.statusMessage).isEqualTo("已连接 Desktop")
        viewModel.disconnect()
    }

    @Test
    fun `touch sensitivity settings update ui state and registry`() = runTest(dispatcher) {
        viewModel.setCursorSensitivity(1.8)
        viewModel.setSwipeSensitivity(0.7)

        assertThat(viewModel.uiState.value.touchSensitivitySettings).isEqualTo(
            TouchSensitivitySettings(cursorSensitivity = 1.8, swipeSensitivity = 0.7),
        )
        assertThat(registry.loadTouchSensitivitySettings()).isEqualTo(
            TouchSensitivitySettings(cursorSensitivity = 1.8, swipeSensitivity = 0.7),
        )
    }

    @Test
    fun `stale transport callbacks do not override the active connection`() = runTest(dispatcher) {
        val controlledTransports = mutableListOf<ControlledRemoteTransport>()
        val localViewModel = SessionCoordinatorViewModel(
            registry = registry,
            discoveryService = discoveryService,
            transportFactory = {
                ControlledRemoteTransport().also(controlledTransports::add)
            },
            codec = ProtocolCodec(),
            mainDispatcher = dispatcher,
        )
        val firstEndpoint = DesktopEndpoint(
            displayName = "Old Desktop",
            host = "10.0.0.8",
            port = 50505,
            source = EndpointSource.MANUAL,
        )
        val secondEndpoint = DesktopEndpoint(
            displayName = "Current Desktop",
            host = "10.0.0.9",
            port = 50505,
            source = EndpointSource.MANUAL,
        )

        localViewModel.connect(firstEndpoint)
        val firstTransport = controlledTransports.single()
        firstTransport.emitState(TransportConnectionState.Connected)
        runCurrent()

        localViewModel.connect(secondEndpoint)
        val secondTransport = controlledTransports.last()
        secondTransport.emitState(TransportConnectionState.Connected)
        runCurrent()
        firstTransport.emitState(TransportConnectionState.Disconnected("旧连接关闭"))
        firstTransport.emitMessage(ProtocolMessage.Status("旧连接消息"))
        runCurrent()

        assertThat(localViewModel.uiState.value.route).isEqualTo(SessionRoute.CONNECTED)
        assertThat(localViewModel.uiState.value.status).isEqualTo(SessionStatus.CONNECTED)
        assertThat(localViewModel.uiState.value.activeEndpoint).isEqualTo(secondEndpoint)

        localViewModel.shutdown()
    }

    @Test
    fun `enter demo mode skips onboarding and lands on connected route`() = runTest(dispatcher) {
        viewModel.enterDemoMode()
        runCurrent()

        assertThat(viewModel.uiState.value.route).isEqualTo(SessionRoute.CONNECTED)
        assertThat(viewModel.uiState.value.status).isEqualTo(SessionStatus.CONNECTED)
        assertThat(viewModel.uiState.value.activeEndpoint?.displayName).isEqualTo("NeoRemote Demo")
        assertThat(viewModel.uiState.value.lastHudMessage).isEqualTo("演示模式")
    }

    @Test
    fun `set haptics enabled updates ui state and persists value`() = runTest(dispatcher) {
        viewModel.setHapticsEnabled(false)
        runCurrent()

        assertThat(viewModel.uiState.value.hapticsEnabled).isFalse()
        assertThat(registry.loadHapticsEnabled()).isFalse()
    }

    @Test
    fun `adb wired debug connects to localhost receiver port`() = runTest(dispatcher) {
        viewModel.connectUsingAdbWiredDebug()
        runCurrent()

        val state = viewModel.uiState.value
        assertThat(state.manualConnectDraft.host).isEqualTo("127.0.0.1")
        assertThat(state.manualConnectDraft.port).isEqualTo("51101")
        assertThat(state.activeEndpoint?.displayName).isEqualTo("ADB Wired Desktop")
        assertThat(state.activeEndpoint?.host).isEqualTo("127.0.0.1")
        assertThat(state.activeEndpoint?.port).isEqualTo(51101)
        assertThat(state.activeEndpoint?.source).isEqualTo(EndpointSource.MANUAL)
        assertThat(state.status).isEqualTo(SessionStatus.CONNECTED)
        viewModel.disconnect()
    }

    private class ControlledRemoteTransport : RemoteTransport {
        override var onStateChange: ((TransportConnectionState) -> Unit)? = null
        override var onMessage: ((ProtocolMessage) -> Unit)? = null

        override fun connect(endpoint: DesktopEndpoint) = Unit

        override fun disconnect() = Unit

        override fun send(data: ByteArray) = Unit

        fun emitState(state: TransportConnectionState) {
            onStateChange?.invoke(state)
        }

        fun emitMessage(message: ProtocolMessage) {
            onMessage?.invoke(message)
        }
    }
}
