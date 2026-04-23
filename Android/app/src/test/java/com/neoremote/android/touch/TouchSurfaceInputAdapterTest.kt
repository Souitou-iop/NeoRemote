package com.neoremote.android.touch

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.TouchPoint
import com.neoremote.android.core.model.TouchSurfaceOutput
import com.neoremote.android.core.model.TouchSurfaceSemanticEvent
import com.neoremote.android.core.touch.TouchSurfaceInputAdapter
import org.junit.Test

class TouchSurfaceInputAdapterTest {
    @Test
    fun `single pointer move emits move command`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)

        val output = adapter.touchMoved(1, TouchPoint(20f, 10f), 0.1)

        assertThat(output.commands).containsExactly(RemoteCommand.Move(20.0, 10.0))
        assertThat(output.semanticEvent).isNull()
    }

    @Test
    fun `single tap emits primary tap command`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)

        val output = adapter.touchEnded(1, TouchPoint(0f, 0f), 0.1)

        assertThat(output.commands).containsExactly(RemoteCommand.Tap(MouseButtonKind.PRIMARY))
        assertThat(output.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.PRIMARY_TAP)
    }

    @Test
    fun `double tap drag emits primary drag lifecycle`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)
        adapter.touchEnded(1, TouchPoint(0f, 0f), 0.1)

        adapter.touchBegan(2, TouchPoint(0f, 0f), 0.2)
        val start = adapter.touchMoved(2, TouchPoint(20f, 0f), 0.25)
        val end = adapter.touchEnded(2, TouchPoint(20f, 0f), 0.3)

        assertThat(start.commands.first()).isEqualTo(
            RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0, MouseButtonKind.PRIMARY),
        )
        assertThat(start.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.PRIMARY_DRAG_STARTED)
        assertThat(end.commands).containsExactly(
            RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.PRIMARY),
        )
    }

    @Test
    fun `two finger tap waits for both fingers and emits secondary tap`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)
        adapter.touchBegan(2, TouchPoint(20f, 0f), 0.02)

        val firstEnd = adapter.touchEnded(1, TouchPoint(0f, 0f), 0.08)
        val secondEnd = adapter.touchEnded(2, TouchPoint(20f, 0f), 0.1)

        assertThat(firstEnd).isEqualTo(TouchSurfaceOutput())
        assertThat(secondEnd.commands).containsExactly(RemoteCommand.Tap(MouseButtonKind.SECONDARY))
        assertThat(secondEnd.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.SECONDARY_TAP)
    }

    @Test
    fun `two finger vertical move emits scroll instead of secondary tap`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 100f), 0.0)
        adapter.touchBegan(2, TouchPoint(20f, 100f), 0.02)

        val output = adapter.touchMoved(1, TouchPoint(0f, 70f), 0.06)

        assertThat(output.commands).containsExactly(RemoteCommand.Scroll(15.0))
        assertThat(output.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.SCROLLING)
    }

    @Test
    fun `two finger hold and move emits secondary drag lifecycle`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)
        adapter.touchBegan(2, TouchPoint(20f, 0f), 0.0)

        adapter.touchMoved(1, TouchPoint(16f, 0f), 0.24)
        val start = adapter.touchMoved(2, TouchPoint(36f, 0f), 0.25)
        val end = adapter.touchEnded(1, TouchPoint(16f, 0f), 0.3)

        assertThat(start.commands.first()).isEqualTo(
            RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0, MouseButtonKind.SECONDARY),
        )
        assertThat(start.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.SECONDARY_DRAG_STARTED)
        assertThat(end.commands).containsExactly(
            RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.SECONDARY),
        )
    }

    @Test
    fun `three finger tap emits middle tap`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)
        adapter.touchBegan(2, TouchPoint(20f, 0f), 0.01)
        adapter.touchBegan(3, TouchPoint(40f, 0f), 0.02)

        adapter.touchEnded(1, TouchPoint(0f, 0f), 0.08)
        adapter.touchEnded(2, TouchPoint(20f, 0f), 0.09)
        val output = adapter.touchEnded(3, TouchPoint(40f, 0f), 0.1)

        assertThat(output.commands).containsExactly(RemoteCommand.Tap(MouseButtonKind.MIDDLE))
        assertThat(output.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.MIDDLE_TAP)
    }

    @Test
    fun `cancel during secondary drag emits drag ended`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)
        adapter.touchBegan(2, TouchPoint(20f, 0f), 0.0)

        adapter.touchMoved(1, TouchPoint(16f, 0f), 0.24)
        adapter.touchMoved(2, TouchPoint(36f, 0f), 0.25)
        val output = adapter.cancelAllTouches()

        assertThat(output.commands).containsExactly(
            RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0, MouseButtonKind.SECONDARY),
        )
        assertThat(output.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.SECONDARY_DRAG_ENDED)
    }
}
