package com.neoremote.android.receiver

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.ScreenGestureKind
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.VideoActionKind
import com.neoremote.android.core.receiver.MobileInputAction
import com.neoremote.android.core.receiver.MobileInputPlanner
import com.neoremote.android.core.receiver.PointerPosition
import com.neoremote.android.core.receiver.VideoToggleKind
import com.neoremote.android.core.receiver.shouldAcknowledgeMobileCommand
import com.neoremote.android.core.receiver.toPhysicalPixels
import org.junit.Test

class MobileInputPlannerTest {
    @Test
    fun `move command updates pointer inside viewport`() {
        val planner = MobileInputPlanner(100, 200)

        val actions = planner.apply(RemoteCommand.Move(dx = 20.0, dy = -30.0))

        assertThat(actions).containsExactly(
            MobileInputAction.MovePointer(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(70f, 70f),
            ),
        )
        assertThat(planner.currentPointer()).isEqualTo(PointerPosition(70f, 70f))
    }

    @Test
    fun `physical viewport factory keeps planner in logical dp coordinates`() {
        val planner = MobileInputPlanner.fromPhysicalViewport(
            widthPixels = 1080,
            heightPixels = 2400,
            density = 3f,
        )

        val actions = planner.apply(RemoteCommand.Move(dx = 90.0, dy = 60.0))

        assertThat(planner.currentPointer()).isEqualTo(PointerPosition(270f, 460f))
        assertThat(actions).containsExactly(
            MobileInputAction.MovePointer(
                from = PointerPosition(180f, 400f),
                to = PointerPosition(270f, 460f),
            ),
        )
    }

    @Test
    fun `pointer position converts logical coordinates to physical pixels`() {
        val position = PointerPosition(x = 120f, y = 80f)

        assertThat(position.toPhysicalPixels(2.5f)).isEqualTo(PointerPosition(x = 300f, y = 200f))
    }

    @Test
    fun `drag started is acknowledged even before a gesture action exists`() {
        val command = RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0, MouseButtonKind.PRIMARY)

        assertThat(shouldAcknowledgeMobileCommand(command, emptyList())).isTrue()
    }

    @Test
    fun `move command clamps pointer to screen bounds`() {
        val planner = MobileInputPlanner(100, 200)

        val actions = planner.apply(RemoteCommand.Move(dx = 500.0, dy = -500.0))

        assertThat(actions).containsExactly(
            MobileInputAction.MovePointer(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(100f, 0f),
            ),
        )
    }

    @Test
    fun `tap command targets current pointer position`() {
        val planner = MobileInputPlanner(100, 200)
        planner.apply(RemoteCommand.Move(dx = 10.0, dy = 10.0))

        val actions = planner.apply(RemoteCommand.Tap(MouseButtonKind.PRIMARY))

        assertThat(actions).containsExactly(
            MobileInputAction.TapAt(PointerPosition(60f, 110f)),
        )
    }

    @Test
    fun `drag command emits one swipe when drag ends`() {
        val planner = MobileInputPlanner(100, 200)

        assertThat(planner.apply(RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0))).isEmpty()
        planner.apply(RemoteCommand.Drag(DragState.CHANGED, 20.0, 10.0))
        val actions = planner.apply(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0))

        assertThat(actions).containsExactly(
            MobileInputAction.Swipe(
                from = PointerPosition(50f, 100f),
                to = PointerPosition(70f, 110f),
                durationMs = 220L,
            ),
        )
    }

    @Test
    fun `system action command maps to global action`() {
        val planner = MobileInputPlanner(100, 200)

        val actions = planner.apply(RemoteCommand.SystemActionCommand(SystemAction.HOME))

        assertThat(actions).containsExactly(MobileInputAction.Global(SystemAction.HOME))
    }

    @Test
    fun `video swipe up uses screen center and moves from lower to upper viewport`() {
        val planner = MobileInputPlanner(400, 800)

        val actions = planner.apply(RemoteCommand.VideoAction(VideoActionKind.SWIPE_UP))

        assertThat(actions).containsExactly(
            MobileInputAction.Swipe(
                from = PointerPosition(200f, 600f),
                to = PointerPosition(200f, 200f),
                durationMs = 120L,
                showTrail = false,
            ),
        )
    }

    @Test
    fun `video double tap like uses accessibility toggle action instead of coordinate fallback`() {
        val planner = MobileInputPlanner(400, 800)

        val actions = planner.apply(RemoteCommand.VideoAction(VideoActionKind.DOUBLE_TAP_LIKE))

        assertThat(actions).containsExactly(MobileInputAction.VideoToggle(VideoToggleKind.LIKE))
    }

    @Test
    fun `video favorite uses accessibility toggle action instead of coordinate fallback`() {
        val planner = MobileInputPlanner(400, 800)

        val actions = planner.apply(RemoteCommand.VideoAction(VideoActionKind.FAVORITE))

        assertThat(actions).containsExactly(MobileInputAction.VideoToggle(VideoToggleKind.FAVORITE))
    }

    @Test
    fun `screen gesture tap maps normalized coordinates to viewport`() {
        val planner = MobileInputPlanner(400, 800)

        val actions = planner.apply(
            RemoteCommand.ScreenGesture(
                kind = ScreenGestureKind.TAP,
                startX = 0.25,
                startY = 0.75,
            ),
        )

        assertThat(actions).containsExactly(
            MobileInputAction.TapAt(PointerPosition(100f, 600f), showTrail = false),
        )
    }

    @Test
    fun `screen gesture swipe maps normalized coordinates to viewport`() {
        val planner = MobileInputPlanner(400, 800)

        val actions = planner.apply(
            RemoteCommand.ScreenGesture(
                kind = ScreenGestureKind.SWIPE,
                startX = 0.25,
                startY = 0.75,
                endX = 0.75,
                endY = 0.25,
                durationMs = 260L,
            ),
        )

        assertThat(actions).containsExactly(
            MobileInputAction.Swipe(
                from = PointerPosition(100f, 600f),
                to = PointerPosition(300f, 200f),
                durationMs = 260L,
                showTrail = false,
            ),
        )
    }
}
