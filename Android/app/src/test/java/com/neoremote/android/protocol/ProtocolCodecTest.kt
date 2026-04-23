package com.neoremote.android.protocol

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.protocol.ProtocolCodec
import org.junit.Test

class ProtocolCodecTest {
    private val codec = ProtocolCodec()

    @Test
    fun `encode move command keeps expected fields`() {
        val payload = codec.encode(RemoteCommand.Move(12.5, -3.2)).decodeToString()

        assertThat(payload).contains("\"type\":\"move\"")
        assertThat(payload).contains("\"dx\":12.5")
        assertThat(payload).contains("\"dy\":-3.2")
    }

    @Test
    fun `decode status message keeps message text`() {
        val message = codec.decodeMessage("""{"type":"status","message":"desktop-ready"}""".encodeToByteArray())

        assertThat(message).isEqualTo(ProtocolMessage.Status("desktop-ready"))
    }

    @Test
    fun `decode command supports drag payload`() {
        val command = codec.decodeCommand(
            """{"type":"drag","state":"started","dx":4.0,"dy":-2.0}""".encodeToByteArray(),
        )

        assertThat(command).isEqualTo(RemoteCommand.Drag(DragState.STARTED, 4.0, -2.0, MouseButtonKind.PRIMARY))
    }

    @Test
    fun `encode and decode secondary drag command`() {
        val encoded = codec.encode(RemoteCommand.Drag(DragState.STARTED, 4.0, -2.0, MouseButtonKind.SECONDARY))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"button\":\"secondary\"")
        assertThat(command).isEqualTo(RemoteCommand.Drag(DragState.STARTED, 4.0, -2.0, MouseButtonKind.SECONDARY))
    }

    @Test
    fun `decode tap command defaults to primary button`() {
        val command = codec.decodeCommand("""{"type":"tap","button":"primary"}""".encodeToByteArray())

        assertThat(command).isEqualTo(RemoteCommand.Tap(MouseButtonKind.PRIMARY))
    }

    @Test
    fun `encode and decode middle tap command`() {
        val encoded = codec.encode(RemoteCommand.Tap(MouseButtonKind.MIDDLE))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"button\":\"middle\"")
        assertThat(command).isEqualTo(RemoteCommand.Tap(MouseButtonKind.MIDDLE))
    }
}
