package com.neoremote.android.core.receiver

import android.util.Log
import com.neoremote.android.BuildConfig
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.protocol.JsonMessageStreamDecoder
import com.neoremote.android.core.protocol.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

data class MobileCommandHandleResult(
    val handled: Boolean,
    val response: ProtocolMessage? = null,
    val statusMessage: String? = null,
)

interface MobileCommandHandler {
    fun handle(command: RemoteCommand): MobileCommandHandleResult
}

class MobileReceiverServer(
    private val commandHandler: MobileCommandHandler,
    private val port: Int = DEFAULT_PORT,
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val gateFactory: () -> MobileReceiverCommandGate = { MobileReceiverCommandGate() },
) {
    private var serverSocket: ServerSocket? = null
    private var activeClient: Socket? = null
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            runCatching {
                ServerSocket(port).use { server ->
                    server.reuseAddress = true
                    serverSocket = server
                    Log.i(TAG, "Android mobile receiver listening on TCP $port")
                    while (isActive) {
                        val client = server.accept()
                        debugLog { "Android mobile receiver accepted ${client.remoteSocketAddress}" }
                        replaceActiveClient(client)
                        launch { handleClient(client) }
                    }
                }
            }.onFailure { error ->
                if (error !is IOException || isActive) {
                    Log.w(TAG, "Android mobile receiver stopped unexpectedly", error)
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        closeActiveClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun closeScope() {
        stop()
        scope.cancel()
    }

    private fun replaceActiveClient(client: Socket) {
        closeActiveClient()
        activeClient = client
        client.tcpNoDelay = true
    }

    private fun closeActiveClient() {
        runCatching { activeClient?.close() }
        activeClient = null
    }

    private fun handleClient(client: Socket) {
        val decoder = JsonMessageStreamDecoder()
        val gate = gateFactory()
        val buffer = ByteArray(4096)
        runCatching {
            client.getOutputStream().write(status("Android 被控端已连接"))
            client.getOutputStream().flush()
            while (scope.isActive && !client.isClosed) {
                val bytesRead = client.getInputStream().read(buffer)
                if (bytesRead < 0) {
                    debugLog { "Android mobile receiver client closed ${client.remoteSocketAddress}" }
                    break
                }
                if (bytesRead == 0) continue
                debugLog { "Android mobile receiver read bytes=$bytesRead from ${client.remoteSocketAddress}" }
                decoder.append(buffer.copyOf(bytesRead)).forEach { payload ->
                    val command = codec.decodeCommand(payload)
                    debugLog { "Android mobile receiver decoded command=$command" }
                    val gateResult = gate.evaluate(command)
                    if (gateResult is MobileReceiverGateResult.Rejected) {
                        client.getOutputStream().write(codec.encode(ProtocolMessage.Status(gateResult.message)))
                        client.getOutputStream().flush()
                        return@forEach
                    }
                    val result = commandHandler.handle(command)
                    val response = when {
                        result.response != null -> codec.encode(result.response)
                        command is RemoteCommand.Heartbeat -> codec.encode(ProtocolMessage.Heartbeat)
                        result.handled -> codec.encode(ProtocolMessage.Ack)
                        else -> codec.encode(
                            ProtocolMessage.Status(
                                result.statusMessage ?: "Android 被控端忽略了该命令",
                            ),
                        )
                    }
                    client.getOutputStream().write(response)
                    client.getOutputStream().flush()
                }
            }
        }.onFailure { error ->
            if (!client.isClosed) {
                Log.w(TAG, "Android mobile receiver client failed", error)
            }
        }
    }

    private fun status(message: String): ByteArray =
        """{"type":"status","message":"$message"}""".encodeToByteArray()

    companion object {
        const val DEFAULT_PORT = 51101
        private const val TAG = "NeoRemoteReceiver"
    }
}

private inline fun debugLog(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d("NeoRemoteReceiver", message())
    }
}
