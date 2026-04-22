package com.neoremote.android.core.transport

import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.TransportConnectionState
import com.neoremote.android.core.protocol.JsonMessageStreamDecoder
import com.neoremote.android.core.protocol.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

interface RemoteTransport {
    var onStateChange: ((TransportConnectionState) -> Unit)?
    var onMessage: ((ProtocolMessage) -> Unit)?

    fun connect(endpoint: DesktopEndpoint)
    fun disconnect()
    fun send(data: ByteArray)
}

class SocketRemoteTransport(
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RemoteTransport {
    override var onStateChange: ((TransportConnectionState) -> Unit)? = null
    override var onMessage: ((ProtocolMessage) -> Unit)? = null

    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var readJob: Job? = null
    @Volatile
    private var manualDisconnect = false

    override fun connect(endpoint: DesktopEndpoint) {
        disconnect()
        manualDisconnect = false
        scope.launch {
            runCatching {
                onStateChange?.invoke(TransportConnectionState.Connecting)
                val nextSocket = Socket()
                nextSocket.tcpNoDelay = true
                nextSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
                socket = nextSocket
                onStateChange?.invoke(TransportConnectionState.Connected)
                startReadLoop(nextSocket)
            }.onFailure { error ->
                val message = error.message ?: "连接失败"
                onStateChange?.invoke(TransportConnectionState.Failed(message))
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        readJob?.cancel()
        readJob = null
        runCatching { socket?.close() }
        socket = null
        onStateChange?.invoke(TransportConnectionState.Disconnected(null))
    }

    override fun send(data: ByteArray) {
        val activeSocket = socket ?: return
        scope.launch {
            runCatching {
                writeMutex.withLock {
                    activeSocket.getOutputStream().write(data)
                    activeSocket.getOutputStream().flush()
                }
            }.onFailure { error ->
                onStateChange?.invoke(
                    TransportConnectionState.Failed(error.message ?: "发送失败"),
                )
            }
        }
    }

    private fun startReadLoop(activeSocket: Socket) {
        readJob = scope.launch {
            val decoder = JsonMessageStreamDecoder()
            val buffer = ByteArray(4096)
            try {
                val input = activeSocket.getInputStream()
                while (isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) {
                        if (manualDisconnect) {
                            onStateChange?.invoke(TransportConnectionState.Disconnected(null))
                        } else {
                            onStateChange?.invoke(
                                TransportConnectionState.Disconnected("连接已关闭"),
                            )
                        }
                        break
                    }
                    if (bytesRead == 0) continue

                    decoder.append(buffer.copyOf(bytesRead)).forEach { payload ->
                        onMessage?.invoke(codec.decodeMessage(payload))
                    }
                }
            } catch (error: IOException) {
                if (manualDisconnect) {
                    onStateChange?.invoke(TransportConnectionState.Disconnected(null))
                } else {
                    onStateChange?.invoke(
                        TransportConnectionState.Failed(error.message ?: "读取失败"),
                    )
                }
            }
        }
    }

    fun closeScope() {
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}

class MockRemoteTransport : RemoteTransport {
    override var onStateChange: ((TransportConnectionState) -> Unit)? = null
    override var onMessage: ((ProtocolMessage) -> Unit)? = null

    val sentPayloads = mutableListOf<ByteArray>()
    var shouldFailOnConnect = false

    override fun connect(endpoint: DesktopEndpoint) {
        if (shouldFailOnConnect) {
            onStateChange?.invoke(TransportConnectionState.Failed("mock connect failed"))
        } else {
            onStateChange?.invoke(TransportConnectionState.Connected)
        }
    }

    override fun disconnect() {
        onStateChange?.invoke(TransportConnectionState.Disconnected(null))
    }

    override fun send(data: ByteArray) {
        sentPayloads += data
    }
}

