package com.neoremote.android.core.touch

import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.TouchPoint
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.TouchSurfaceSemanticEvent
import kotlin.math.abs
import kotlin.math.hypot

class TouchSurfaceInputAdapter(
    private val moveMultiplier: Double = 1.0,
    private val scrollMultiplier: Double = 1.0,
    private val tapDistanceThreshold: Float = 12f,
    private val tapDurationThreshold: Double = 0.22,
    private val doubleTapWindow: Double = 0.32,
) {
    private data class ActiveTouch(
        val startPoint: TouchPoint,
        val point: TouchPoint,
        val startTime: Double,
    )

    private val activeTouches = linkedMapOf<Int, ActiveTouch>()
    private var lastTapTime: Double? = null
    private var dragCandidateId: Int? = null
    private var dragActiveId: Int? = null
    private var multiTouchSession = false

    fun touchBegan(id: Int, point: TouchPoint, timestamp: Double): TouchSurfaceOutput {
        if (activeTouches.isEmpty()) {
            val previousTap = lastTapTime
            if (previousTap != null && timestamp - previousTap <= doubleTapWindow) {
                dragCandidateId = id
            }
        }

        activeTouches[id] = ActiveTouch(
            startPoint = point,
            point = point,
            startTime = timestamp,
        )

        if (activeTouches.size >= 2) {
            multiTouchSession = true
            val button = when (activeTouches.size) {
                2 -> MouseButtonKind.SECONDARY
                3 -> MouseButtonKind.MIDDLE
                else -> null
            } ?: return TouchSurfaceOutput()
            return TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Tap(button)),
                semanticEvent = TouchSurfaceSemanticEvent.TAP,
            )
        }
        return TouchSurfaceOutput()
    }

    fun touchMoved(id: Int, point: TouchPoint, @Suppress("UNUSED_PARAMETER") timestamp: Double): TouchSurfaceOutput {
        val existing = activeTouches[id] ?: return TouchSurfaceOutput()
        val previousAverageY = activeTouches.values.map { it.point.y }.average().toFloat()
        val previousPoint = existing.point
        activeTouches[id] = existing.copy(point = point)

        if (activeTouches.size >= 2) {
            multiTouchSession = true
            val newAverageY = activeTouches.values.map { it.point.y }.average().toFloat()
            val deltaY = (previousAverageY - newAverageY) * scrollMultiplier
            if (abs(deltaY) <= 0.1) return TouchSurfaceOutput()
            return TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Scroll(deltaY)),
                semanticEvent = TouchSurfaceSemanticEvent.SCROLLING,
            )
        }

        val dx = (point.x - previousPoint.x) * moveMultiplier
        val dy = (point.y - previousPoint.y) * moveMultiplier
        val totalDistance = existing.startPoint.distanceTo(point)

        if (dragActiveId == id) {
            return TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Drag(DragState.CHANGED, dx, dy)),
                semanticEvent = TouchSurfaceSemanticEvent.DRAG_CHANGED,
            )
        }

        if (dragCandidateId == id && totalDistance > tapDistanceThreshold) {
            dragCandidateId = null
            dragActiveId = id

            val commands = buildList {
                add(RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0))
                if (abs(dx) > 0.1 || abs(dy) > 0.1) {
                    add(RemoteCommand.Drag(DragState.CHANGED, dx, dy))
                }
            }
            return TouchSurfaceOutput(
                commands = commands,
                semanticEvent = TouchSurfaceSemanticEvent.DRAG_STARTED,
            )
        }

        if (abs(dx) <= 0.05 && abs(dy) <= 0.05) {
            return TouchSurfaceOutput()
        }

        return TouchSurfaceOutput(
            commands = listOf(RemoteCommand.Move(dx, dy)),
            semanticEvent = TouchSurfaceSemanticEvent.MOVING,
        )
    }

    fun touchEnded(id: Int, point: TouchPoint, timestamp: Double): TouchSurfaceOutput {
        val touch = activeTouches.remove(id) ?: return TouchSurfaceOutput()

        if (activeTouches.isEmpty()) {
            multiTouchSession = false
        }

        if (dragActiveId == id) {
            dragActiveId = null
            dragCandidateId = null
            return TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0)),
                semanticEvent = TouchSurfaceSemanticEvent.DRAG_ENDED,
            )
        }

        val duration = timestamp - touch.startTime
        val distance = touch.startPoint.distanceTo(point)

        if (dragCandidateId == id) {
            dragCandidateId = null
        }

        if (multiTouchSession) return TouchSurfaceOutput()
        if (duration > tapDurationThreshold || distance > tapDistanceThreshold) {
            return TouchSurfaceOutput()
        }

        lastTapTime = timestamp
        return TouchSurfaceOutput(
            commands = listOf(RemoteCommand.Tap(MouseButtonKind.PRIMARY)),
            semanticEvent = TouchSurfaceSemanticEvent.TAP,
        )
    }

    fun cancelAllTouches(): TouchSurfaceOutput {
        val hadDrag = dragActiveId != null
        activeTouches.clear()
        dragCandidateId = null
        dragActiveId = null
        multiTouchSession = false
        return if (hadDrag) {
            TouchSurfaceOutput(
                commands = listOf(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0)),
                semanticEvent = TouchSurfaceSemanticEvent.DRAG_ENDED,
            )
        } else {
            TouchSurfaceOutput()
        }
    }
}

private fun TouchPoint.distanceTo(other: TouchPoint): Float =
    hypot(other.x - x, other.y - y)
