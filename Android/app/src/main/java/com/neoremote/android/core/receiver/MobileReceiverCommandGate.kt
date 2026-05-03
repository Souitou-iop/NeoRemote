package com.neoremote.android.core.receiver

import com.neoremote.android.core.model.RemoteCommand

class MobileReceiverCommandGate(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxCommandsPerWindow: Int = 80,
    private val windowMs: Long = 1_000L,
) {
    private var hasClientHello = false
    private val commandTimestamps = ArrayDeque<Long>()

    fun reset() {
        hasClientHello = false
        commandTimestamps.clear()
    }

    fun evaluate(command: RemoteCommand): MobileReceiverGateResult {
        if (command is RemoteCommand.ClientHello) {
            hasClientHello = true
            return MobileReceiverGateResult.Allowed
        }

        if (!hasClientHello) {
            return MobileReceiverGateResult.Rejected("Android 被控端需要先完成 clientHello")
        }

        val now = clock()
        while (commandTimestamps.firstOrNull()?.let { now - it >= windowMs } == true) {
            commandTimestamps.removeFirst()
        }
        if (commandTimestamps.size >= maxCommandsPerWindow) {
            return MobileReceiverGateResult.Rejected("Android 被控端命令频率过高，已限流")
        }
        commandTimestamps.addLast(now)
        return MobileReceiverGateResult.Allowed
    }
}

sealed interface MobileReceiverGateResult {
    data object Allowed : MobileReceiverGateResult
    data class Rejected(val message: String) : MobileReceiverGateResult
}
