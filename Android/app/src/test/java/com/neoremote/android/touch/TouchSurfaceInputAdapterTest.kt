package com.neoremote.android.touch

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.MouseButtonKind
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.TouchPoint
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
    }

    @Test
    fun `single tap emits primary tap command`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)

        val output = adapter.touchEnded(1, TouchPoint(0f, 0f), 0.1)

        assertThat(output.commands).containsExactly(RemoteCommand.Tap(MouseButtonKind.PRIMARY))
        assertThat(output.semanticEvent).isEqualTo(TouchSurfaceSemanticEvent.TAP)
    }

    @Test
    fun `double tap drag emits drag started and ended`() {
        val adapter = TouchSurfaceInputAdapter()
        adapter.touchBegan(1, TouchPoint(0f, 0f), 0.0)
        adapter.touchEnded(1, TouchPoint(0f, 0f), 0.1)

        adapter.touchBegan(2, TouchPoint(0f, 0f), 0.2)
        val start = adapter.touchMoved(2, TouchPoint(20f, 0f), 0.25)
        val end = adapter.touchEnded(2, TouchPoint(20f, 0f), 0.3)

        assertThat(start.commands.first()).isEqualTo(RemoteCommand.Drag(DragState.STARTED, 0.0, 0.0))
        assertThat(end.commands).containsExactly(RemoteCommand.Drag(DragState.ENDED, 0.0, 0.0))
    }
}

