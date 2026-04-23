package com.neoremote.android.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.neoremote.android.core.model.TouchSurfaceSemanticEvent

class HapticsController(
    context: Context,
) {
    private var lastMoveTickAt = 0L
    private var lastDragTickAt = 0L

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun perform(event: TouchSurfaceSemanticEvent?) {
        val activeVibrator = vibrator ?: return
        val effect = when (event) {
            TouchSurfaceSemanticEvent.MOVING -> if (canPlayMoveTick()) tickEffect() else null
            TouchSurfaceSemanticEvent.TAP -> tickEffect()
            TouchSurfaceSemanticEvent.DRAG_STARTED -> clickEffect()
            TouchSurfaceSemanticEvent.DRAG_CHANGED -> if (canPlayDragTick()) tickEffect() else null
            TouchSurfaceSemanticEvent.DRAG_ENDED -> tickEffect()
            TouchSurfaceSemanticEvent.SCROLLING -> null
            null -> null
        } ?: return
        activeVibrator.vibrate(effect)
    }

    private fun tickEffect(): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(TICK_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }

    private fun clickEffect(): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(CLICK_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }

    private fun canPlayMoveTick(): Boolean =
        canPlayThrottledTick(
            lastTickAt = lastMoveTickAt,
            minimumInterval = MOVE_TICK_INTERVAL_MS,
            onAccepted = { lastMoveTickAt = it },
        )

    private fun canPlayDragTick(): Boolean =
        canPlayThrottledTick(
            lastTickAt = lastDragTickAt,
            minimumInterval = DRAG_TICK_INTERVAL_MS,
            onAccepted = { lastDragTickAt = it },
        )

    private fun canPlayThrottledTick(
        lastTickAt: Long,
        minimumInterval: Long,
        onAccepted: (Long) -> Unit,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTickAt < minimumInterval) return false
        onAccepted(now)
        return true
    }

    private companion object {
        const val TICK_DURATION_MS = 10L
        const val CLICK_DURATION_MS = 18L
        const val MOVE_TICK_INTERVAL_MS = 80L
        const val DRAG_TICK_INTERVAL_MS = 60L
    }
}
