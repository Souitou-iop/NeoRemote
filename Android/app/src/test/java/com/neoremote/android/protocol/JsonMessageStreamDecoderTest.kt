package com.neoremote.android.protocol

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.protocol.JsonMessageStreamDecoder
import org.junit.Test

class JsonMessageStreamDecoderTest {
    @Test
    fun `append splits sticky packets into separate json payloads`() {
        val decoder = JsonMessageStreamDecoder()

        val payloads = decoder.append(
            """{"type":"ack"}{"type":"heartbeat"}""".encodeToByteArray(),
        )

        assertThat(payloads.map { it.decodeToString() }).containsExactly(
            """{"type":"ack"}""",
            """{"type":"heartbeat"}""",
        ).inOrder()
    }

    @Test
    fun `append waits until json object is complete`() {
        val decoder = JsonMessageStreamDecoder()

        val first = decoder.append("""{"type":"status","message":"hel""".encodeToByteArray())
        val second = decoder.append("""lo"}""".encodeToByteArray())

        assertThat(first).isEmpty()
        assertThat(second.single().decodeToString()).isEqualTo("""{"type":"status","message":"hello"}""")
    }
}

