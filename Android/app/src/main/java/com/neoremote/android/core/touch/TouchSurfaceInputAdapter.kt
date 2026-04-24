package com.neoremote.android.core.touch

import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.TouchPoint
import com.neoremote.android.core.model.TouchSensitivitySettings
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.TouchSurfaceSemanticEvent
import kotlin.math.abs
import kotlin.math.hypot

class TouchSurfaceInputAdapter(
    settings: TouchSensitivitySettings = TouchSensitivitySettings(),
    private val tapDistanceThreshold: Float = 12f,
    private val tapDurationThreshold: Double = 0.22,
    private val doubleTapWindow: Double = 0.32,
    private val dragDistanceThreshold: Float = 14f,
    private val rightDragHoldDelay: Double = 0.18,
    private val scrollDominanceThreshold: Float = 1.15f,
) {
    private var moveMultiplier: Double = 1.0
    private var scrollMultiplier: Double = 1.0
    private var scrollActivationDistance: Float = 14f

    private data class ActiveTouch(
        val startPoint: TouchPoint,
        val point: TouchPoint,
        val startTime: Double,
    )

    private enum class Phase {
        IDLE,
        SINGLE_TAP_CANDIDATE,
        LEFT_DRAG_CANDIDATE,
        LEFT_DRAG_ACTIVE,
        MULTI_TOUCH_CANDIDATE,
        SCROLL_ACTIVE,
        RIGHT_DRAG_ACTIVE,
        MIDDLE_TAP_CANDIDATE,
    }

    private val activeTouches = linkedMapOf<Int, ActiveTouch>()
    private var phase = Phase.IDLE
    private var lastTapTime: Double? = null
    private var sessionStartTime: Double? = null
    private var sessionStartCentroid: TouchPoint? = null
    private var previousCentroid: TouchPoint? = null
    private var maxTouchCount = 0
    private var singleTouchId: Int? = null

    init {
        apply(settings)
    }

    fun apply(settings: TouchSensitivitySettings) {
        val clamped = settings.clamped
        moveMultiplier = clamped.cursorSensitivity
        scrollMultiplier = clamped.swipeSensitivity
        scrollActivationDistance = (14.0 / clamped.swipeSensitivity).toFloat()
    }

    fun touchBegan(id: Int, point: TouchPoint, timestamp: Double): TouchSurfaceOutput {
        if (activeTouches.isEmpty()) {
            beginSession(id = id, point = point, timestamp = timestamp)
        } else {
            activeTouches[id] = ActiveTouch(startPoint = point, point = point, startTime = timestamp)
            maxTouchCount = maxOf(maxTouchCount, activeTouches.size)
            sessionStartCentroid = centroid()
            previousCentroid = sessionStartCentroid
            singleTouchId = null

            phase = when (activeTouches.size) {
                2 -> Phase.MULTI_TOUCH_CANDIDATE
                3 -> Phase.MIDDLE_TAP_CANDIDATE
                else -> Phase.IDLE
            }
        }

        return TouchSurfaceOutput()
    }

    fun touchMoved(id: Int, point: TouchPoint, timestamp: Double): TouchSurfaceOutput {
        val existing = activeTouches[id] ?: return TouchSurfaceOutput()
        val previousPoint = existing.point
        val oldCentroid = previousCentroid ?: centroid()

        activeTouches[id] = existing.copy(point = point)

        return when (phase) {
            Phase.SINGLE_TAP_CANDIDATE -> handleSingleMove(from = previousPoint, to = point)
            Phase.LEFT_DRAG_CANDIDATE -> handleLeftDragCandidateMove(id = id, point = point)
            Phase.LEFT_DRAG_ACTIVE -> {
                val dx = (point.x - previousPoint.x) * moveMultiplier
                val dy = (point.y - previousPoint.y) * moveMultiplier
                TouchSurfaceOutput(
                    commands = listOf(RemoteCommand.Drag(DragState.CHANGED, dx, dy, MouseButtonKind.PRIMARY)),
                    semanticEvent = TouchSurfaceSemanticEvent.PRIMARY_DRAG_CHANGED,
                )
            }

            Phase.MULTI_TOUCH_CANDIDATE,
            Phase.SCROLL_ACTIVE,
            Phase.RIGHT_DRAG_ACTIVE,
            -> handleTwoFingerMove(timestamp = timestamp, oldCentroid = oldCentroid)

            Phase.MIDDLE_TAP_CANDIDATE -> {
                val start = sessionStartCentroid
                if (start != null && start.distanceTo(centroid()) > tapDistanceThreshold) {
                    phase = Phase.IDLE
                }
                TouchSurfaceOutput()
            }

            Phase.IDLE -> TouchSurfaceOutput()
        }
    }

    fun touchEnded(id: Int, point: TouchPoint, timestamp: Double): TouchSurfaceOutput {
        if (!activeTouches.containsKey(id)) return TouchSurfaceOutput()
        activeTouches[id] = activeTouches.getValue(id).copy(point = point)

        val dragEnd = dragEndOutputIfNeeded(endingTouchId = id)
        activeTouches.remove(id)

        if (dragEnd != null) {
            resetIfSessionEnded()
            return dragEnd
        }

        if (activeTouches.isNotEmpty()) return TouchSurfaceOutput()

        val duration = timestamp - (sessionStartTime ?: timestamp)
        val movement = sessionStartCentroid?.let { start ->
            start.distanceTo(previousCentroid ?: start)
        } ?: 0f
        val output = when (phase) {
            Phase.SINGLE_TAP_CANDIDATE,
            Phase.LEFT_DRAG_CANDIDATE,
            -> {
                if (duration <= tapDurationThreshold && movement <= tapDistanceThreshold) {
                    lastTapTime = timestamp
                    TouchSurfaceOutput(
                        commands = listOf(RemoteCommand.Tap(MouseButtonKind.PRIMARY)),
                        semanticEvent = TouchSurfaceSemanticEvent.PRIMARY_TAP,
                    )
                } else {
                    TouchSurfaceOutput()
                }
            }

            Phase.MULTI_TOUCH_CANDIDATE -> {
                if (maxTouchCount == 2 && duration <= tapDurationThreshold && movement <= tapDistanceThreshold) {
                    TouchSurfaceOutput(
                        commands = listOf(RemoteCommand.Tap(MouseButtonKind.SECONDARY)),
                        semanticEvent = TouchSurfaceSemanticEvent.SECONDARY_TAP,
                    )
                } else {
                    TouchSurfaceOutput()
                }
            }

            Phase.MIDDLE_TAP_CANDIDATE -> {
                if (maxTouchCount == 3 && duration <= tapDurationThreshold && movement <= tapDistanceThreshold) {
                    TouchSurfaceOutput(
                        commands = listOf(RemoteCommand.Tap(MouseButtonKind.MIDDLE)),
                        semanticEvent = TouchSurfaceSemanticEvent.MIDDLE_TAP,
                    )
                } else {
                    TouchSurfaceOutput()
                }
            }

            else -> TouchSurfaceOutput()
        }

        resetSession()
        return output
    }

    fun cancelAllTouches(): TouchSurfaceOutput {
        val output = when (phase) {
            Phase.LEFT_DRAG_ACTIVE -> TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.PRIMARY)),
                semanticEvent = TouchSurfaceSemanticEvent.PRIMARY_DRAG_ENDED,
            )

            Phase.RIGHT_DRAG_ACTIVE -> TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.SECONDARY)),
                semanticEvent = TouchSurfaceSemanticEvent.SECONDARY_DRAG_ENDED,
            )

            else -> TouchSurfaceOutput()
        }

        resetSession()
        return output
    }

    private fun beginSession(id: Int, point: TouchPoint, timestamp: Double) {
        activeTouches[id] = ActiveTouch(startPoint = point, point = point, startTime = timestamp)
        singleTouchId = id
        sessionStartTime = timestamp
        sessionStartCentroid = point
        previousCentroid = point
        maxTouchCount = 1

        phase = if (lastTapTime?.let { timestamp - it <= doubleTapWindow } == true) {
            Phase.LEFT_DRAG_CANDIDATE
        } else {
            Phase.SINGLE_TAP_CANDIDATE
        }
    }

    private fun handleSingleMove(from: TouchPoint, to: TouchPoint): TouchSurfaceOutput {
        val dx = (to.x - from.x) * moveMultiplier
        val dy = (to.y - from.y) * moveMultiplier
        previousCentroid = to

        if (abs(dx) <= 0.05 && abs(dy) <= 0.05) return TouchSurfaceOutput()
        return TouchSurfaceOutput(commands = listOf(RemoteCommand.Move(dx, dy)))
    }

    private fun handleLeftDragCandidateMove(id: Int, point: TouchPoint): TouchSurfaceOutput {
        if (id != singleTouchId) return TouchSurfaceOutput()
        val touch = activeTouches[id] ?: return TouchSurfaceOutput()
        previousCentroid = point

        if (touch.startPoint.distanceTo(point) <= dragDistanceThreshold) return TouchSurfaceOutput()

        phase = Phase.LEFT_DRAG_ACTIVE
        val dx = (point.x - touch.startPoint.x) * moveMultiplier
        val dy = (point.y - touch.startPoint.y) * moveMultiplier
        val commands = buildList {
            add(RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0, MouseButtonKind.PRIMARY))
            if (abs(dx) > 0.1 || abs(dy) > 0.1) {
                add(RemoteCommand.Drag(DragState.CHANGED, dx, dy, MouseButtonKind.PRIMARY))
            }
        }

        return TouchSurfaceOutput(
            commands = commands,
            semanticEvent = TouchSurfaceSemanticEvent.PRIMARY_DRAG_STARTED,
        )
    }

    private fun handleTwoFingerMove(timestamp: Double, oldCentroid: TouchPoint): TouchSurfaceOutput {
        if (activeTouches.size != 2) {
            phase = Phase.IDLE
            return TouchSurfaceOutput()
        }

        val newCentroid = centroid()
        previousCentroid = newCentroid
        val dx = newCentroid.x - oldCentroid.x
        val dy = newCentroid.y - oldCentroid.y
        val sessionDistance = sessionStartCentroid?.distanceTo(newCentroid) ?: 0f
        val sessionDuration = timestamp - (sessionStartTime ?: timestamp)

        if (phase == Phase.RIGHT_DRAG_ACTIVE) {
            return TouchSurfaceOutput(
                commands = listOf(
                    RemoteCommand.Drag(
                        state = DragState.CHANGED,
                        dx = dx * moveMultiplier,
                        dy = dy * moveMultiplier,
                        button = MouseButtonKind.SECONDARY,
                    ),
                ),
                semanticEvent = TouchSurfaceSemanticEvent.SECONDARY_DRAG_CHANGED,
            )
        }

        if (phase == Phase.SCROLL_ACTIVE) {
            return scrollOutput(dx = dx, dy = dy)
        }

        if (sessionDistance <= minOf(dragDistanceThreshold, scrollActivationDistance)) return TouchSurfaceOutput()

        if (sessionDuration >= rightDragHoldDelay) {
            phase = Phase.RIGHT_DRAG_ACTIVE
            val commands = buildList {
                add(RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0, MouseButtonKind.SECONDARY))
                if (abs(dx) > 0.1 || abs(dy) > 0.1) {
                    add(
                        RemoteCommand.Drag(
                            state = DragState.CHANGED,
                            dx = dx * moveMultiplier,
                            dy = dy * moveMultiplier,
                            button = MouseButtonKind.SECONDARY,
                        ),
                    )
                }
            }
            return TouchSurfaceOutput(
                commands = commands,
                semanticEvent = TouchSurfaceSemanticEvent.SECONDARY_DRAG_STARTED,
            )
        }

        if (sessionDistance >= scrollActivationDistance && abs(dy) >= abs(dx) * scrollDominanceThreshold) {
            phase = Phase.SCROLL_ACTIVE
            return scrollOutput(dx = dx, dy = dy)
        }

        if (sessionDistance >= scrollActivationDistance && abs(dx) >= abs(dy) * scrollDominanceThreshold) {
            phase = Phase.SCROLL_ACTIVE
            return scrollOutput(dx = dx, dy = dy)
        }

        return TouchSurfaceOutput()
    }

    private fun scrollOutput(dx: Float, dy: Float): TouchSurfaceOutput {
        val isHorizontal = abs(dx) >= abs(dy)
        val deltaX = if (isHorizontal) -dx * scrollMultiplier else 0.0
        val deltaY = if (isHorizontal) 0.0 else -dy * scrollMultiplier
        if (abs(deltaX) <= 0.1 && abs(deltaY) <= 0.1) return TouchSurfaceOutput()
        return TouchSurfaceOutput(
            commands = listOf(RemoteCommand.Scroll(deltaX = deltaX, deltaY = deltaY)),
            semanticEvent = TouchSurfaceSemanticEvent.SCROLLING,
        )
    }

    private fun centroid(): TouchPoint {
        if (activeTouches.isEmpty()) return TouchPoint(0f, 0f)
        return TouchPoint(
            x = activeTouches.values.map { it.point.x }.average().toFloat(),
            y = activeTouches.values.map { it.point.y }.average().toFloat(),
        )
    }

    private fun dragEndOutputIfNeeded(endingTouchId: Int): TouchSurfaceOutput? =
        when {
            phase == Phase.LEFT_DRAG_ACTIVE && endingTouchId == singleTouchId -> {
                phase = Phase.IDLE
                TouchSurfaceOutput(
                    commands = listOf(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.PRIMARY)),
                    semanticEvent = TouchSurfaceSemanticEvent.PRIMARY_DRAG_ENDED,
                )
            }

            phase == Phase.RIGHT_DRAG_ACTIVE -> {
                phase = Phase.IDLE
                TouchSurfaceOutput(
                    commands = listOf(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.SECONDARY)),
                    semanticEvent = TouchSurfaceSemanticEvent.SECONDARY_DRAG_ENDED,
                )
            }

            else -> null
        }

    private fun resetIfSessionEnded() {
        if (activeTouches.isEmpty()) {
            resetSession()
        }
    }

    private fun resetSession() {
        activeTouches.clear()
        phase = Phase.IDLE
        sessionStartTime = null
        sessionStartCentroid = null
        previousCentroid = null
        maxTouchCount = 0
        singleTouchId = null
    }
}

private fun TouchPoint.distanceTo(other: TouchPoint): Float =
    hypot(other.x - x, other.y - y)
