package com.neoremote.android.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.neoremote.android.core.model.TouchPoint
import com.neoremote.android.core.model.TouchSensitivitySettings
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.touch.TouchSurfaceInputAdapter

@Composable
fun TouchSurfaceHost(
    modifier: Modifier = Modifier,
    settings: TouchSensitivitySettings,
    onOutput: (TouchSurfaceOutput) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TouchSurfaceView(context).apply {
                this.onOutput = onOutput
                updateSettings(settings)
            }
        },
        update = { view ->
            view.onOutput = onOutput
            view.updateSettings(settings)
        },
    )
}

class TouchSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onOutput: ((TouchSurfaceOutput) -> Unit)? = null
    private val adapter = TouchSurfaceInputAdapter()
    private val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
    }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            width.toFloat() - paddingRight,
            height.toFloat() - paddingBottom,
        )
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xFFE4EEF8.toInt(), 0xFFBBD0E6.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        outline.color = 0x3357799B
        canvas.drawRoundRect(rect, 48f, 48f, paint)
        canvas.drawRoundRect(rect, 48f, 48f, outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val timestamp = event.eventTime / 1000.0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val index = event.actionIndex
                if (!event.hasPointerAt(index)) return true
                emit(
                    adapter.touchBegan(
                        id = event.getPointerId(index),
                        point = event.touchPointAt(index),
                        timestamp = timestamp,
                    ),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                repeat(event.pointerCount) { index ->
                    emit(
                        adapter.touchMoved(
                            id = event.getPointerId(index),
                            point = event.touchPointAt(index),
                            timestamp = timestamp,
                        ),
                    )
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val index = event.actionIndex
                if (!event.hasPointerAt(index)) return true
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                emit(
                    adapter.touchEnded(
                        id = event.getPointerId(index),
                        point = event.touchPointAt(index),
                        timestamp = timestamp,
                    ),
                )
            }

            MotionEvent.ACTION_CANCEL -> emit(adapter.cancelAllTouches())
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun updateSettings(settings: TouchSensitivitySettings) {
        adapter.apply(settings)
    }

    private fun emit(output: TouchSurfaceOutput) {
        if (output.commands.isEmpty() && output.semanticEvent == null) return
        onOutput?.invoke(output)
    }

    private fun MotionEvent.touchPointAt(index: Int): TouchPoint =
        TouchPoint(getX(index) / density, getY(index) / density)
}

private fun MotionEvent.hasPointerAt(index: Int): Boolean =
    index >= 0 && index < pointerCount
