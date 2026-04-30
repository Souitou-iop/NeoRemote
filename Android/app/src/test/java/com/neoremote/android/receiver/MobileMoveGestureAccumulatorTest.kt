package com.neoremote.android.receiver

import com.google.common.truth.Truth.assertThat
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.receiver.MobileInputAction
import com.neoremote.android.core.receiver.MobileMoveGestureAccumulator
import com.neoremote.android.core.receiver.PointerPosition
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
}
