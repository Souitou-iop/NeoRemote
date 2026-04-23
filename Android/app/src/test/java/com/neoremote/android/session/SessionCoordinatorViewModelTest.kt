package com.neoremote.android.session

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.discovery.FakeDiscoveryService
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SessionRoute
import com.neoremote.android.core.model.SessionStatus
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
