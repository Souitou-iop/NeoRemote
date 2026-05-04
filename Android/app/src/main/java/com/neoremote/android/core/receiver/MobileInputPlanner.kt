package com.neoremote.android.core.receiver

import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.VideoActionKind
import kotlin.math.abs
import kotlin.math.hypot

private const val TOUCHPAD_MOVE_SWIPE_DURATION_MS = 160L
private const val DEFAULT_LONG_PRESS_DURATION_MS = 520L

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
    data class LongPressAt(
        val position: PointerPosition,
        val durationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS,
        val showTrail: Boolean = true,
    ) : MobileInputAction
    data class Swipe(
        val from: PointerPosition,
        val to: PointerPosition,
        val durationMs: Long,
        val showTrail: Boolean = true,
        val clearsVideoToggleCache: Boolean = false,
    ) : MobileInputAction
    data class VideoToggle(val kind: VideoToggleKind) : MobileInputAction
    data class Global(val action: SystemAction) : MobileInputAction
}

enum class VideoToggleKind {
    LIKE,
    FAVORITE,
}

class MobileMoveGestureAccumulator(
    private val minDistance: Float = 8f,
    private val durationMs: Long = TOUCHPAD_MOVE_SWIPE_DURATION_MS,
) {
    private var pendingFrom: PointerPosition? = null
    private var pendingTo: PointerPosition? = null

    fun accept(action: MobileInputAction): List<MobileInputAction> =
        when (action) {
            is MobileInputAction.MovePointer -> {
                if (pendingFrom == null) {
                    pendingFrom = action.from
                }
                pendingTo = action.to
                emptyList()
            }

            else -> flush() + action
        }

    fun flush(): List<MobileInputAction> {
        val from = pendingFrom
        val to = pendingTo
        pendingFrom = null
        pendingTo = null

        if (from == null || to == null) return emptyList()
        val distance = hypot(to.x - from.x, to.y - from.y)
        if (distance < minDistance) return emptyList()

        return listOf(
            MobileInputAction.Swipe(
                from = from,
                to = to,
                durationMs = durationMs,
            ),
        )
    }

    fun reset() {
        pendingFrom = null
        pendingTo = null
    }
}

enum class MobileActionQueuePolicy {
    APPEND,
    REPLACE_PENDING,
    REPLACE_PENDING_VIDEO_NAVIGATION,
}

class MobileActionQueue(
    private val maxPendingActions: Int = 3,
) {
    private val pendingActions = ArrayDeque<MobileInputAction>()

    val size: Int
        get() = pendingActions.size

    fun enqueue(actions: List<MobileInputAction>, policy: MobileActionQueuePolicy) {
        when (policy) {
            MobileActionQueuePolicy.REPLACE_PENDING -> pendingActions.clear()
            MobileActionQueuePolicy.REPLACE_PENDING_VIDEO_NAVIGATION ->
                pendingActions.removeAll { it.isVideoNavigationAction }
            MobileActionQueuePolicy.APPEND -> Unit
        }
        actions.forEach { action ->
            pendingActions.addLast(action)
            while (pendingActions.size > maxPendingActions) {
                pendingActions.removeFirst()
            }
        }
    }

    fun removeFirstOrNull(): MobileInputAction? = pendingActions.removeFirstOrNull()

    fun clear() {
        pendingActions.clear()
    }
}

private val MobileInputAction.isVideoNavigationAction: Boolean
    get() = this is MobileInputAction.Swipe && clearsVideoToggleCache

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
            is RemoteCommand.ScreenGesture -> handleScreenGesture(command)
            is RemoteCommand.ClientHello,
            RemoteCommand.RequestVideoState,
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
        return when (action) {
            VideoActionKind.SWIPE_UP -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(center.x, height * 0.75f),
                    to = PointerPosition(center.x, height * 0.25f),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                    clearsVideoToggleCache = true,
                ),
            )

            VideoActionKind.SWIPE_DOWN -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(center.x, height * 0.25f),
                    to = PointerPosition(center.x, height * 0.75f),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                    clearsVideoToggleCache = true,
                ),
            )

            VideoActionKind.SWIPE_LEFT -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(width * 0.75f, center.y),
                    to = PointerPosition(width * 0.25f, center.y),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                    clearsVideoToggleCache = true,
                ),
            )

            VideoActionKind.SWIPE_RIGHT -> listOf(
                MobileInputAction.Swipe(
                    from = PointerPosition(width * 0.25f, center.y),
                    to = PointerPosition(width * 0.75f, center.y),
                    durationMs = VIDEO_SWIPE_DURATION_MS,
                    showTrail = false,
                    clearsVideoToggleCache = true,
                ),
            )

            VideoActionKind.DOUBLE_TAP_LIKE -> listOf(MobileInputAction.VideoToggle(VideoToggleKind.LIKE))
            VideoActionKind.FAVORITE -> listOf(MobileInputAction.VideoToggle(VideoToggleKind.FAVORITE))
            VideoActionKind.PLAY_PAUSE -> listOf(MobileInputAction.TapAt(center, showTrail = false))
            VideoActionKind.BACK -> listOf(MobileInputAction.Global(SystemAction.BACK))
            VideoActionKind.UNKNOWN -> emptyList()
        }
    }

    private fun handleScreenGesture(command: RemoteCommand.ScreenGesture): List<MobileInputAction> {
        val start = normalizedPoint(command.startX, command.startY)
        val end = normalizedPoint(command.endX, command.endY)
        return when (command.kind) {
            ScreenGestureKind.TAP -> listOf(MobileInputAction.TapAt(start, showTrail = false))
            ScreenGestureKind.LONG_PRESS -> listOf(
                MobileInputAction.LongPressAt(
                    position = start,
                    durationMs = command.durationMs.coerceIn(300L, 1_200L),
                    showTrail = false,
                ),
            )
            ScreenGestureKind.SWIPE -> listOf(
                MobileInputAction.Swipe(
                    from = start,
                    to = end,
                    durationMs = command.durationMs.coerceIn(80L, 800L),
                    showTrail = false,
                ),
            )
            ScreenGestureKind.UNKNOWN -> emptyList()
        }
    }

    private fun normalizedPoint(x: Double, y: Double): PointerPosition =
        PointerPosition(
            x = (x.coerceIn(0.0, 1.0) * width).toFloat(),
            y = (y.coerceIn(0.0, 1.0) * height).toFloat(),
        )

    private fun PointerPosition.clamped(): PointerPosition =
        PointerPosition(
            x = x.coerceIn(0f, width.toFloat()),
            y = y.coerceIn(0f, height.toFloat()),
        )

    companion object {
        const val SCROLL_DURATION_MS = 140L
        const val DRAG_DURATION_MS = 220L
        const val VIDEO_SWIPE_DURATION_MS = 120L

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
