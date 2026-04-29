package com.neoremote.android.core.receiver

import android.util.Log
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

fun interface MobileCommandHandler {
    fun handle(command: RemoteCommand): Boolean
}

class MobileReceiverServer(
    private val commandHandler: MobileCommandHandler,
    private val port: Int = DEFAULT_PORT,
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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
                        Log.i(TAG, "Android mobile receiver accepted ${client.remoteSocketAddress}")
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
        val buffer = ByteArray(4096)
        runCatching {
            client.getOutputStream().write(status("Android 被控端已连接"))
            client.getOutputStream().flush()
            while (scope.isActive && !client.isClosed) {
                val bytesRead = client.getInputStream().read(buffer)
                if (bytesRead < 0) break
                if (bytesRead == 0) continue
                decoder.append(buffer.copyOf(bytesRead)).forEach { payload ->
                    val command = codec.decodeCommand(payload)
                    Log.d(TAG, "Android mobile receiver decoded command=$command")
                    val handled = commandHandler.handle(command)
                    val response = when {
                        command is RemoteCommand.Heartbeat -> """{"type":"heartbeat"}"""
                        handled -> """{"type":"ack"}"""
                        else -> """{"type":"status","message":"Android 被控端忽略了该命令"}"""
                    }.encodeToByteArray()
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
