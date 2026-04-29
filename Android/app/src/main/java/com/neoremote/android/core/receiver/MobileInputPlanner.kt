package com.neoremote.android.core.receiver

import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.VideoActionKind
import kotlin.math.abs

data class PointerPosition(
    val x: Float,
    val y: Float,
)

fun PointerPosition.toPhysicalPixels(density: Float): PointerPosition {
    val scale = density.takeIf { it > 0f } ?: 1f
    return PointerPosition(x = x * scale, y = y * scale)
}

sealed interface MobileInputAction {
    data class MovePointer(
        val from: PointerPosition,
        val to: PointerPosition,
    ) : MobileInputAction
    data class TapAt(
        val position: PointerPosition,
        val showTrail: Boolean = true,
    ) : MobileInputAction
    data class Swipe(
        val from: PointerPosition,
        val to: PointerPosition,
        val durationMs: Long,
        val showTrail: Boolean = true,
    ) : MobileInputAction
    data class Global(val action: SystemAction) : MobileInputAction
}

class MobileInputPlanner(
    viewportWidth: Int,
    viewportHeight: Int,
    initialPosition: PointerPosition = PointerPosition(
        x = viewportWidth.coerceAtLeast(1) / 2f,
        y = viewportHeight.coerceAtLeast(1) / 2f,
    ),
) {
    private val width = viewportWidth.coerceAtLeast(1)
    private val height = viewportHeight.coerceAtLeast(1)
    private var pointer = initialPosition.clamped()
    private var dragStart: PointerPosition? = null
    private var dragCurrent: PointerPosition? = null

    fun apply(command: RemoteCommand): List<MobileInputAction> =
        when (command) {
            is RemoteCommand.Move -> listOf(moveBy(command.dx, command.dy))
            is RemoteCommand.Tap -> listOf(MobileInputAction.TapAt(pointer))
            is RemoteCommand.Scroll -> listOf(scrollBy(command.deltaX, command.deltaY))
            is RemoteCommand.Drag -> handleDrag(command)
            is RemoteCommand.SystemActionCommand -> listOf(MobileInputAction.Global(command.action))
            is RemoteCommand.VideoAction -> handleVideoAction(command.action)
            is RemoteCommand.ClientHello,
            RemoteCommand.Heartbeat,
            -> emptyList()
        }

    fun currentPointer(): PointerPosition = pointer

    private fun moveBy(dx: Double, dy: Double): MobileInputAction.MovePointer {
        val from = pointer
        pointer = PointerPosition(
            x = pointer.x + dx.toFloat(),
            y = pointer.y + dy.toFloat(),
        ).clamped()
        return MobileInputAction.MovePointer(from = from, to = pointer)
    }

    private fun scrollBy(deltaX: Double, deltaY: Double): MobileInputAction.Swipe {
        val horizontal = abs(deltaX) > abs(deltaY)
        val distance = if (horizontal) deltaX.toFloat() else deltaY.toFloat()
        val from = pointer
        val to = if (horizontal) {
            PointerPosition(pointer.x - distance, pointer.y)
        } else {
            PointerPosition(pointer.x, pointer.y - distance)
        }.clamped()
        return MobileInputAction.Swipe(from = from, to = to, durationMs = SCROLL_DURATION_MS)
    }

    private fun handleDrag(command: RemoteCommand.Drag): List<MobileInputAction> =
        when (command.state) {
            DragState.STARTED -> {
                dragStart = pointer
                dragCurrent = pointer
                emptyList()
            }

            DragState.CHANGED -> {
                val from = dragCurrent ?: pointer
                dragCurrent = PointerPosition(
                    x = from.x + command.dx.toFloat(),
                    y = from.y + command.dy.toFloat(),
                ).clamped()
                pointer = dragCurrent ?: pointer
                listOf(MobileInputAction.MovePointer(from = from, to = pointer))
            }

            DragState.ENDED -> {
                val from = dragStart ?: pointer
                val to = (dragCurrent ?: pointer).clamped()
                dragStart = null
                dragCurrent = null
                pointer = to
                listOf(MobileInputAction.Swipe(from = from, to = to, durationMs = DRAG_DURATION_MS))
            }
        }

    private fun handleVideoAction(action: VideoActionKind): List<MobileInputAction> {
        val center = PointerPosition(width * 0.5f, height * 0.5f)
        val likePoint = PointerPosition(width * 0.55f, height * 0.5f)
        return when (action) {
            VideoActionKind.SWIPE_UP -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(center.x, height * 0.75f),
                    to = PointerPosition(center.x, height * 0.25f),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                ),
            )

            VideoActionKind.SWIPE_DOWN -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(center.x, height * 0.25f),
                    to = PointerPosition(center.x, height * 0.75f),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                ),
            )

            VideoActionKind.SWIPE_LEFT -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(width * 0.75f, center.y),
                    to = PointerPosition(width * 0.25f, center.y),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                ),
            )

            VideoActionKind.SWIPE_RIGHT -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(width * 0.25f, center.y),
                    to = PointerPosition(width * 0.75f, center.y),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                ),
            )

            VideoActionKind.DOUBLE_TAP_LIKE -> listOf(
                MobileInputAction.TapAt(likePoint, showTrail = false),
                MobileInputAction.TapAt(likePoint, showTrail = false),
            )

            VideoActionKind.PLAY_PAUSE -> listOf(MobileInputAction.TapAt(center, showTrail = false))
            VideoActionKind.BACK -> listOf(MobileInputAction.Global(SystemAction.BACK))
            VideoActionKind.UNKNOWN -> emptyList()
        }
    }

    private fun PointerPosition.clamped(): PointerPosition =
        PointerPosition(
            x = x.coerceIn(0f, width.toFloat()),
            y = y.coerceIn(0f, height.toFloat()),
        )

    companion object {
        const val SCROLL_DURATION_MS = 140L
        const val DRAG_DURATION_MS = 220L
        const val VIDEO_SWIPE_DURATION_MS = 220L

        fun fromPhysicalViewport(
            widthPixels: Int,
            heightPixels: Int,
            density: Float,
        ): MobileInputPlanner {
            val scale = density.takeIf { it > 0f } ?: 1f
            return MobileInputPlanner(
                viewportWidth = (widthPixels / scale).toInt().coerceAtLeast(1),
                viewportHeight = (heightPixels / scale).toInt().coerceAtLeast(1),
            )
        }
    }
}
