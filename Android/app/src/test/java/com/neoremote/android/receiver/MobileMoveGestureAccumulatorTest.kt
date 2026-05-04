package com.neoremote.android.receiver

import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.receiver.MobileActionQueue
import com.neoremote.android.core.receiver.MobileActionQueuePolicy
import com.neoremote.android.core.receiver.MobileInputAction
import com.neoremote.android.core.receiver.MobileMoveGestureAccumulator
import com.neoremote.android.core.receiver.PointerPosition
import com.neoremote.android.core.receiver.ToggleBounds
import com.neoremote.android.core.receiver.VideoToggleKind
import com.neoremote.android.core.receiver.isToggleBoundsOnCurrentViewport
import com.neoremote.android.core.receiver.mobileQueuePolicyFor
import com.neoremote.android.core.receiver.remainingVideoToggleSettleDelayMs
import com.neoremote.android.core.receiver.shouldRefreshToggleCache
import org.junit.Test

class MobileMoveGestureAccumulatorTest {
    @Test
    fun `continuous move actions are flushed as one swipe`() {
        val accumulator = MobileMoveGestureAccumulator()

        assertThat(
            accumulator.accept(
                MobileInputAction.MovePointer(
                    from = PointerPosition(50f, 100f),
                    to = PointerPosition(60f, 120f),
                ),
            ),
        ).isEmpty()
        assertThat(
            accumulator.accept(
                MobileInputAction.MovePointer(
                    from = PointerPosition(60f, 120f),
                    to = PointerPosition(80f, 180f),
                ),
            ),
        ).isEmpty()

        assertThat(accumulator.flush()).containsExactly(
            MobileInputAction.Swipe(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(80f, 180f),
                durationMs = 160L,
            ),
        )
    }

    @Test
    fun `non move action flushes accumulated move before itself`() {
        val accumulator = MobileMoveGestureAccumulator()

        accumulator.accept(
            MobileInputAction.MovePointer(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(50f, 180f),
            ),
        )

        assertThat(accumulator.accept(MobileInputAction.Global(SystemAction.BACK))).containsExactly(
            MobileInputAction.Swipe(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(50f, 180f),
                durationMs = 160L,
            ),
            MobileInputAction.Global(SystemAction.BACK),
        ).inOrder()
    }

    @Test
    fun `tiny accumulated move is dropped on flush`() {
        val accumulator = MobileMoveGestureAccumulator(minDistance = 8f)

        accumulator.accept(
            MobileInputAction.MovePointer(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(52f, 104f),
            ),
        )

        assertThat(accumulator.flush()).isEmpty()
    }

    @Test
    fun `reset drops accumulated move without flushing stale gesture`() {
        val accumulator = MobileMoveGestureAccumulator()
        accumulator.accept(
            MobileInputAction.MovePointer(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(90f, 160f),
            ),
        )

        accumulator.reset()

        assertThat(accumulator.flush()).isEmpty()
    }

    @Test
    fun `queue replaces stale pending actions for latency sensitive command`() {
        val queue = MobileActionQueue(maxPendingActions = 3)
        queue.enqueue(
            listOf(
                MobileInputAction.Swipe(PointerPosition(0f, 0f), PointerPosition(0f, 100f), 120L),
                MobileInputAction.Swipe(PointerPosition(0f, 100f), PointerPosition(0f, 200f), 120L),
            ),
            MobileActionQueuePolicy.APPEND,
        )

        val latest = MobileInputAction.Global(SystemAction.BACK)
        queue.enqueue(listOf(latest), MobileActionQueuePolicy.REPLACE_PENDING)

        assertThat(queue.size).isEqualTo(1)
        assertThat(queue.removeFirstOrNull()).isEqualTo(latest)
        assertThat(queue.removeFirstOrNull()).isNull()
    }

    @Test
    fun `queue drops oldest actions when producers outpace accessibility dispatch`() {
        val queue = MobileActionQueue(maxPendingActions = 2)
        val first = MobileInputAction.Global(SystemAction.BACK)
        val second = MobileInputAction.Global(SystemAction.HOME)
        val third = MobileInputAction.Global(SystemAction.RECENTS)

        queue.enqueue(listOf(first, second, third), MobileActionQueuePolicy.APPEND)

        assertThat(queue.removeFirstOrNull()).isEqualTo(second)
        assertThat(queue.removeFirstOrNull()).isEqualTo(third)
        assertThat(queue.removeFirstOrNull()).isNull()
    }

    @Test
    fun `video navigation replaces stale pending navigation actions`() {
        assertThat(mobileQueuePolicyFor(RemoteCommand.VideoAction(VideoActionKind.SWIPE_UP)))
            .isEqualTo(MobileActionQueuePolicy.REPLACE_PENDING_VIDEO_NAVIGATION)
        assertThat(mobileQueuePolicyFor(RemoteCommand.VideoAction(VideoActionKind.DOUBLE_TAP_LIKE)))
            .isEqualTo(MobileActionQueuePolicy.APPEND)
        assertThat(mobileQueuePolicyFor(RemoteCommand.ScreenGesture(ScreenGestureKind.TAP, 0.5, 0.5)))
            .isEqualTo(MobileActionQueuePolicy.APPEND)
    }

    @Test
    fun `video navigation replacement keeps non navigation short video actions`() {
        val queue = MobileActionQueue(maxPendingActions = 6)
        val staleNext = MobileInputAction.Swipe(
            PointerPosition(0f, 400f),
            PointerPosition(0f, 100f),
            durationMs = 120L,
            clearsVideoToggleCache = true,
        )
        val like = MobileInputAction.VideoToggle(VideoToggleKind.LIKE)
        val favorite = MobileInputAction.VideoToggle(VideoToggleKind.FAVORITE)
        val latestNext = MobileInputAction.Swipe(
            PointerPosition(0f, 420f),
            PointerPosition(0f, 120f),
            durationMs = 120L,
            clearsVideoToggleCache = true,
        )

        queue.enqueue(listOf(staleNext, like, favorite), MobileActionQueuePolicy.APPEND)
        queue.enqueue(
            listOf(latestNext),
            MobileActionQueuePolicy.REPLACE_PENDING_VIDEO_NAVIGATION,
        )

        assertThat(queue.removeFirstOrNull()).isEqualTo(like)
        assertThat(queue.removeFirstOrNull()).isEqualTo(favorite)
        assertThat(queue.removeFirstOrNull()).isEqualTo(latestNext)
        assertThat(queue.removeFirstOrNull()).isNull()
    }

    @Test
    fun `back keeps emergency replace behavior`() {
        assertThat(mobileQueuePolicyFor(RemoteCommand.VideoAction(VideoActionKind.BACK)))
            .isEqualTo(MobileActionQueuePolicy.REPLACE_PENDING)
        assertThat(mobileQueuePolicyFor(RemoteCommand.SystemActionCommand(SystemAction.BACK)))
            .isEqualTo(MobileActionQueuePolicy.REPLACE_PENDING)
    }

    @Test
    fun `queue keeps a normal short video burst in order`() {
        val queue = MobileActionQueue(maxPendingActions = 4)
        val next = MobileInputAction.Swipe(PointerPosition(0f, 400f), PointerPosition(0f, 100f), 120L)
        val like = MobileInputAction.VideoToggle(VideoToggleKind.LIKE)
        val favorite = MobileInputAction.VideoToggle(VideoToggleKind.FAVORITE)
        val pause = MobileInputAction.TapAt(PointerPosition(100f, 100f), showTrail = false)

        queue.enqueue(listOf(next, like, favorite, pause), MobileActionQueuePolicy.APPEND)

        assertThat(queue.removeFirstOrNull()).isEqualTo(next)
        assertThat(queue.removeFirstOrNull()).isEqualTo(like)
        assertThat(queue.removeFirstOrNull()).isEqualTo(favorite)
        assertThat(queue.removeFirstOrNull()).isEqualTo(pause)
        assertThat(queue.removeFirstOrNull()).isNull()
    }

    @Test
    fun `toggle cache refresh ignores irrelevant or too frequent accessibility events`() {
        assertThat(
            shouldRefreshToggleCache(
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                nowMs = 1_000L,
                lastRefreshAtMs = 0L,
            ),
        ).isFalse()
        assertThat(
            shouldRefreshToggleCache(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                nowMs = 1_100L,
                lastRefreshAtMs = 1_000L,
            ),
        ).isFalse()
        assertThat(
            shouldRefreshToggleCache(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                nowMs = 1_300L,
                lastRefreshAtMs = 1_000L,
            ),
        ).isTrue()
    }

    @Test
    fun `video toggle waits briefly after video navigation`() {
        assertThat(
            remainingVideoToggleSettleDelayMs(
                nowMs = 1_100L,
                lastVideoNavigationAtMs = 1_000L,
            ),
        ).isEqualTo(320L)

        assertThat(
            remainingVideoToggleSettleDelayMs(
                nowMs = 1_500L,
                lastVideoNavigationAtMs = 1_000L,
            ),
        ).isEqualTo(0L)

        assertThat(
            remainingVideoToggleSettleDelayMs(
                nowMs = 1_100L,
                lastVideoNavigationAtMs = 0L,
            ),
        ).isEqualTo(0L)
    }

    @Test
    fun `toggle candidate must be centered inside the current viewport`() {
        assertThat(
            isToggleBoundsOnCurrentViewport(
                bounds = ToggleBounds(left = 900, top = 1_200, right = 1_020, bottom = 1_320),
                viewportWidth = 1_080,
                viewportHeight = 2_400,
            ),
        ).isTrue()

        assertThat(
            isToggleBoundsOnCurrentViewport(
                bounds = ToggleBounds(left = 900, top = -400, right = 1_020, bottom = -280),
                viewportWidth = 1_080,
                viewportHeight = 2_400,
            ),
        ).isFalse()

        assertThat(
            isToggleBoundsOnCurrentViewport(
                bounds = ToggleBounds(left = 900, top = 2_520, right = 1_020, bottom = 2_640),
                viewportWidth = 1_080,
                viewportHeight = 2_400,
            ),
        ).isFalse()
    }
}
