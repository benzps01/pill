package dev.pill.dynamicpill.core.gesture

import dev.pill.dynamicpill.core.state.PillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureBindingsTest {

    private val bindings = GestureBindings()

    @Test
    fun `tap means something different at each level`() {
        assertEquals(GestureAction.EXPAND, bindings.actionFor(PillState.HIDDEN, Gesture.TAP))
        assertEquals(GestureAction.EXPAND, bindings.actionFor(PillState.COMPACT, Gesture.TAP))
        assertEquals(GestureAction.OPEN_APP, bindings.actionFor(PillState.EXPANDED, Gesture.TAP))
    }

    @Test
    fun `the vertical swipe axis is symmetric`() {
        assertEquals(GestureAction.EXPAND, bindings.actionFor(PillState.HIDDEN, Gesture.SWIPE_DOWN))
        assertEquals(GestureAction.EXPAND, bindings.actionFor(PillState.COMPACT, Gesture.SWIPE_DOWN))

        assertEquals(GestureAction.COLLAPSE, bindings.actionFor(PillState.EXPANDED, Gesture.SWIPE_UP))
        assertEquals(GestureAction.COLLAPSE, bindings.actionFor(PillState.COMPACT, Gesture.SWIPE_UP))
    }

    @Test
    fun `tapping outside only closes the expanded card`() {
        assertEquals(GestureAction.COLLAPSE, bindings.actionFor(PillState.EXPANDED, Gesture.TAP_OUTSIDE))
        assertEquals(GestureAction.NONE, bindings.actionFor(PillState.COMPACT, Gesture.TAP_OUTSIDE))
        assertEquals(GestureAction.NONE, bindings.actionFor(PillState.HIDDEN, Gesture.TAP_OUTSIDE))
    }

    @Test
    fun `double-tap, long-press and horizontal swipes are unbound by default`() {
        for (state in listOf(PillState.HIDDEN, PillState.COMPACT, PillState.EXPANDED)) {
            for (gesture in listOf(
                Gesture.DOUBLE_TAP,
                Gesture.LONG_PRESS,
                Gesture.SWIPE_LEFT,
                Gesture.SWIPE_RIGHT
            )) {
                assertEquals(
                    "$gesture in $state should be unbound",
                    GestureAction.NONE,
                    bindings.actionFor(state, gesture)
                )
                assertFalse(bindings.isBound(state, gesture))
            }
        }
    }

    @Test
    fun `unbound combinations resolve to NONE rather than throwing`() {
        assertEquals(GestureAction.NONE, bindings.actionFor(PillState.IDLE, Gesture.TAP))
        assertEquals(GestureAction.NONE, bindings.actionFor(PillState.TRANSIENT_POP, Gesture.SWIPE_UP))
    }

    @Test
    fun `isBound reflects the table`() {
        assertTrue(bindings.isBound(PillState.COMPACT, Gesture.TAP))
        assertFalse(bindings.isBound(PillState.HIDDEN, Gesture.SWIPE_UP))
    }

    @Test
    fun `a custom table overrides the defaults entirely`() {
        val custom = GestureBindings(
            mapOf((PillState.COMPACT to Gesture.DOUBLE_TAP) to GestureAction.PLAY_PAUSE)
        )
        assertEquals(GestureAction.PLAY_PAUSE, custom.actionFor(PillState.COMPACT, Gesture.DOUBLE_TAP))
        // Not merged with DEFAULTS — the caller supplies the whole table.
        assertEquals(GestureAction.NONE, custom.actionFor(PillState.COMPACT, Gesture.TAP))
    }
}
