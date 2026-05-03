package com.neoremote.android.transport

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DesktopEndpoint
import com.neoremote.android.core.model.EndpointSource
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.TransportConnectionState
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.protocol.JsonMessageStreamDecoder
import com.neoremote.android.core.protocol.ProtocolCodec
import com.neoremote.android.core.transport.SocketRemoteTransport
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class SocketRemoteTransportTest {
    @Test
    fun `socket transport writes burst commands in caller order`() {
        val codec = ProtocolCodec()
        val server = ServerSocket(0)
        val received = Collections.synchronizedList(mutableListOf<RemoteCommand>())
        val commandCount = 80
        val receivedAll = CountDownLatch(commandCount)
        val executor = Executors.newSingleThreadExecutor()

        executor.execute {
            server.use { activeServer ->
                activeServer.accept().use { socket ->
                    val decoder = JsonMessageStreamDecoder()
                    val buffer = ByteArray(4096)
                    while (receivedAll.count > 0) {
                        val bytesRead = socket.getInputStream().read(buffer)
                        if (bytesRead < 0) break
                        decoder.append(buffer.copyOf(bytesRead)).forEach { payload ->
                            received += codec.decodeCommand(payload)
                            receivedAll.countDown()
                        }
                    }
                }
            }
        }

        val transport = SocketRemoteTransport(codec = codec)
        val connected = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        transport.onStateChange = { state ->
            when (state) {
                TransportConnectionState.Connected -> connected.countDown()
                is TransportConnectionState.Failed -> failure.set(state.errorDescription)
                else -> Unit
            }
        }
        transport.connect(
            DesktopEndpoint(
                displayName = "Local Android Receiver",
                host = "127.0.0.1",
                port = server.localPort,
                platform = null,
                source = EndpointSource.MANUAL,
            ),
        )

        assertThat(connected.await(3, TimeUnit.SECONDS)).isTrue()

        val commands = List(commandCount) { index ->
            val action = if (index % 2 == 0) VideoActionKind.SWIPE_UP else VideoActionKind.PLAY_PAUSE
            RemoteCommand.VideoAction(action)
        }
        commands.forEach { command ->
            transport.send(codec.encode(command))
        }

        assertThat(receivedAll.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(failure.get()).isNull()
        assertThat(received).containsExactlyElementsIn(commands).inOrder()

        transport.disconnect()
        transport.closeScope()
        executor.shutdownNow()
    }
}
