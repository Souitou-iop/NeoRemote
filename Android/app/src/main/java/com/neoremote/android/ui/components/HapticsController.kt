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
            TouchSurfaceSemanticEvent.TAP -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            TouchSurfaceSemanticEvent.DRAG_STARTED -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            TouchSurfaceSemanticEvent.DRAG_CHANGED -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            TouchSurfaceSemanticEvent.DRAG_ENDED -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            TouchSurfaceSemanticEvent.SCROLLING -> null
            null -> null
        } ?: return
        activeVibrator.vibrate(effect)
    }
}

