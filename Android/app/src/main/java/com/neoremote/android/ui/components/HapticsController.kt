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
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun perform(event: TouchSurfaceSemanticEvent?) {
        val activeVibrator = vibrator ?: return
        val effect = when (event) {
            TouchSurfaceSemanticEvent.PRIMARY_TAP,
            TouchSurfaceSemanticEvent.SECONDARY_TAP,
            TouchSurfaceSemanticEvent.MIDDLE_TAP,
            -> tickEffect()
            TouchSurfaceSemanticEvent.PRIMARY_DRAG_STARTED,
            TouchSurfaceSemanticEvent.SECONDARY_DRAG_STARTED,
            -> clickEffect()
            TouchSurfaceSemanticEvent.PRIMARY_DRAG_CHANGED,
            TouchSurfaceSemanticEvent.SECONDARY_DRAG_CHANGED,
            -> tickEffect()
            TouchSurfaceSemanticEvent.PRIMARY_DRAG_ENDED,
            TouchSurfaceSemanticEvent.SECONDARY_DRAG_ENDED,
            -> tickEffect()
            TouchSurfaceSemanticEvent.SCROLLING -> null
            null -> null
        } ?: return
        runCatching {
            activeVibrator.vibrate(effect)
        }
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

    private companion object {
        const val TICK_DURATION_MS = 10L
        const val CLICK_DURATION_MS = 18L
    }
}
