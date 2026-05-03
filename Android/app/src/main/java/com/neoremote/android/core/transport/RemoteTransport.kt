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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private var socket: Socket? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var writeChannel: Channel<ByteArray>? = null
    @Volatile
    private var manualDisconnect = false

    override fun connect(endpoint: DesktopEndpoint) {
        closeActiveSocket()
        manualDisconnect = false
        scope.launch {
            runCatching {
                onStateChange?.invoke(TransportConnectionState.Connecting)
                val nextSocket = Socket()
                nextSocket.tcpNoDelay = true
                nextSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
                socket = nextSocket
                startWriteLoop(nextSocket)
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
        closeActiveSocket()
        onStateChange?.invoke(TransportConnectionState.Disconnected(null))
    }

    override fun send(data: ByteArray) {
        val result = writeChannel?.trySend(data)
        if (result == null || result.isFailure) {
            onStateChange?.invoke(TransportConnectionState.Failed("发送队列不可用"))
        }
    }

    private fun startWriteLoop(activeSocket: Socket) {
        val channel = Channel<ByteArray>(SEND_BUFFER_CAPACITY)
        writeChannel = channel
        writeJob = scope.launch {
            runCatching {
                val output = activeSocket.getOutputStream()
                for (payload in channel) {
                    output.write(payload)
                    output.flush()
                }
            }.onFailure { error ->
                if (!activeSocket.isClosed) {
                    onStateChange?.invoke(
                        TransportConnectionState.Failed(error.message ?: "发送失败"),
                    )
                }
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

                    runCatching { decoder.append(buffer.copyOf(bytesRead)) }
                        .onSuccess { payloads ->
                            payloads.forEach { payload ->
                                runCatching { codec.decodeMessage(payload) }
                                    .onSuccess { message -> onMessage?.invoke(message) }
                                    .onFailure { error ->
                                        onStateChange?.invoke(
                                            TransportConnectionState.Failed(error.message ?: "协议解析失败"),
                                        )
                                    }
                            }
                        }.onFailure { error ->
                            closeActiveSocket()
                            onStateChange?.invoke(
                                TransportConnectionState.Failed(error.message ?: "协议消息过大"),
                            )
                            return@launch
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

    private fun closeActiveSocket() {
        readJob?.cancel()
        readJob = null
        writeChannel?.close()
        writeChannel = null
        writeJob?.cancel()
        writeJob = null
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val SEND_BUFFER_CAPACITY = 512
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
