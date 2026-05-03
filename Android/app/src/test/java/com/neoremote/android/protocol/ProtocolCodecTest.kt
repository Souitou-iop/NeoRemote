package com.neoremote.android.protocol

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.ProtocolMessage
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.ToggleState
import com.neoremote.android.core.model.VideoInteractionState
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.protocol.ProtocolCodec
import org.junit.Test
import org.junit.Assert.assertThrows

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
    fun `decode video state message keeps target package and toggle states`() {
        val message = codec.decodeMessage(
            """
            {
              "type":"videoState",
              "targetPackage":"com.ss.android.ugc.aweme",
              "likeState":"active",
              "favoriteState":"inactive"
            }
            """.trimIndent().encodeToByteArray(),
        )

        assertThat(message).isEqualTo(
            ProtocolMessage.VideoState(
                VideoInteractionState(
                    targetPackage = "com.ss.android.ugc.aweme",
                    likeState = ToggleState.ACTIVE,
                    favoriteState = ToggleState.INACTIVE,
                ),
            ),
        )
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

    @Test
    fun `encode and decode scroll command keeps both axes`() {
        val encoded = codec.encode(RemoteCommand.Scroll(deltaX = 7.0, deltaY = -3.0))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"deltaX\":7.0")
        assertThat(encoded.decodeToString()).contains("\"deltaY\":-3.0")
        assertThat(command).isEqualTo(RemoteCommand.Scroll(deltaX = 7.0, deltaY = -3.0))
    }

    @Test
    fun `decode legacy scroll command defaults horizontal axis to zero`() {
        val command = codec.decodeCommand("""{"type":"scroll","deltaY":15.0}""".encodeToByteArray())

        assertThat(command).isEqualTo(RemoteCommand.Scroll(deltaX = 0.0, deltaY = 15.0))
    }

    @Test
    fun `decode command rejects non finite move`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decodeCommand("""{"type":"move","dx":1e999,"dy":0.0}""".encodeToByteArray())
        }

        assertThat(error).hasMessageThat().contains("dx must be finite")
    }

    @Test
    fun `decode command rejects out of range scroll`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decodeCommand("""{"type":"scroll","deltaX":0.0,"deltaY":9999.0}""".encodeToByteArray())
        }

        assertThat(error).hasMessageThat().contains("deltaY exceeds allowed range")
    }

    @Test
    fun `encode and decode client hello command`() {
        val encoded = codec.encode(RemoteCommand.ClientHello("android-1", "Pixel", "android"))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"type\":\"clientHello\"")
        assertThat(command).isEqualTo(RemoteCommand.ClientHello("android-1", "Pixel", "android"))
    }

    @Test
    fun `encode and decode system action command`() {
        val encoded = codec.encode(RemoteCommand.SystemActionCommand(SystemAction.BACK))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"type\":\"systemAction\"")
        assertThat(encoded.decodeToString()).contains("\"action\":\"back\"")
        assertThat(command).isEqualTo(RemoteCommand.SystemActionCommand(SystemAction.BACK))
    }

    @Test
    fun `encode and decode video action command`() {
        val encoded = codec.encode(RemoteCommand.VideoAction(VideoActionKind.SWIPE_UP))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"type\":\"videoAction\"")
        assertThat(encoded.decodeToString()).contains("\"action\":\"swipeUp\"")
        assertThat(command).isEqualTo(RemoteCommand.VideoAction(VideoActionKind.SWIPE_UP))
    }

    @Test
    fun `encode and decode favorite video action command`() {
        val encoded = codec.encode(RemoteCommand.VideoAction(VideoActionKind.FAVORITE))
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"type\":\"videoAction\"")
        assertThat(encoded.decodeToString()).contains("\"action\":\"favorite\"")
        assertThat(command).isEqualTo(RemoteCommand.VideoAction(VideoActionKind.FAVORITE))
    }

    @Test
    fun `encode and decode video state request command`() {
        val encoded = codec.encode(RemoteCommand.RequestVideoState)
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"type\":\"videoStateRequest\"")
        assertThat(command).isEqualTo(RemoteCommand.RequestVideoState)
    }

    @Test
    fun `decode unknown video action does not throw`() {
        val command = codec.decodeCommand("""{"type":"videoAction","action":"mystery"}""".encodeToByteArray())

        assertThat(command).isEqualTo(RemoteCommand.VideoAction(VideoActionKind.UNKNOWN))
    }

    @Test
    fun `encode and decode screen gesture command`() {
        val encoded = codec.encode(
            RemoteCommand.ScreenGesture(
                kind = ScreenGestureKind.SWIPE,
                startX = 0.25,
                startY = 0.75,
                endX = 0.25,
                endY = 0.20,
                durationMs = 240L,
            ),
        )
        val command = codec.decodeCommand(encoded)

        assertThat(encoded.decodeToString()).contains("\"type\":\"screenGesture\"")
        assertThat(encoded.decodeToString()).contains("\"kind\":\"swipe\"")
        assertThat(command).isEqualTo(
            RemoteCommand.ScreenGesture(
                kind = ScreenGestureKind.SWIPE,
                startX = 0.25,
                startY = 0.75,
                endX = 0.25,
                endY = 0.20,
                durationMs = 240L,
            ),
        )
    }
}
