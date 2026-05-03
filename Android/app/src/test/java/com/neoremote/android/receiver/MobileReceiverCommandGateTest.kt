package com.neoremote.android.receiver

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.receiver.MobileReceiverCommandGate
import com.neoremote.android.core.receiver.MobileReceiverGateResult
import org.junit.Test

class MobileReceiverCommandGateTest {
    @Test
    fun `commands before client hello are rejected`() {
        val gate = MobileReceiverCommandGate()

        val result = gate.evaluate(RemoteCommand.Tap(MouseButtonKind.PRIMARY))

        assertThat(result).isEqualTo(MobileReceiverGateResult.Rejected("Android 被控端需要先完成 clientHello"))
    }

    @Test
    fun `client hello opens command gate`() {
        val gate = MobileReceiverCommandGate()

        assertThat(gate.evaluate(RemoteCommand.ClientHello("android-1", "Pixel", "android")))
            .isEqualTo(MobileReceiverGateResult.Allowed)
        assertThat(gate.evaluate(RemoteCommand.Tap(MouseButtonKind.PRIMARY)))
            .isEqualTo(MobileReceiverGateResult.Allowed)
    }

    @Test
    fun `command rate limit rejects burst traffic`() {
        var now = 1_000L
        val gate = MobileReceiverCommandGate(clock = { now }, maxCommandsPerWindow = 2, windowMs = 1_000L)
        gate.evaluate(RemoteCommand.ClientHello("android-1", "Pixel", "android"))

        assertThat(gate.evaluate(RemoteCommand.Tap(MouseButtonKind.PRIMARY))).isEqualTo(MobileReceiverGateResult.Allowed)
        assertThat(gate.evaluate(RemoteCommand.Tap(MouseButtonKind.PRIMARY))).isEqualTo(MobileReceiverGateResult.Allowed)
        assertThat(gate.evaluate(RemoteCommand.Tap(MouseButtonKind.PRIMARY)))
            .isEqualTo(MobileReceiverGateResult.Rejected("Android 被控端命令频率过高，已限流"))

        now += 1_000L
        assertThat(gate.evaluate(RemoteCommand.Tap(MouseButtonKind.PRIMARY))).isEqualTo(MobileReceiverGateResult.Allowed)
    }
}
