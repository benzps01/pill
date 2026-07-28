package dev.pill.dynamicpill.core.state

import dev.pill.dynamicpill.core.model.EventType
import dev.pill.dynamicpill.core.model.PillEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PillStateMachineTest {

    private val event = PillEvent("spotify", EventType.MEDIA, priority = 5, title = "Song")

    @Test
    fun `starts hidden and stays hidden with no event`() {
        val machine = PillStateMachine()
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `tapping an empty pill is a no-op`() {
        val machine = PillStateMachine()
        machine.onTap()
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `a live event surfaces compact, tap expands it`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        assertEquals(PillState.COMPACT, machine.state)

        machine.onTap()
        assertEquals(PillState.EXPANDED, machine.state)
    }

    @Test
    fun `expanded event ending auto-collapses to hidden`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.onTap()
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onEvent(null)
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `tapping to collapse expanded returns to compact while event still live`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.onTap() // -> EXPANDED
        machine.onTap() // manual collapse, event still live
        assertEquals(PillState.COMPACT, machine.state)
    }

    @Test
    fun `tapping after a swipe-up dismiss with the event still live reopens compact`() {
        val machine = PillStateMachine()
        machine.onEvent(event) // -> COMPACT
        machine.onSwipeUp() // manual dismiss -> HIDDEN, but the song is still playing
        assertEquals(PillState.HIDDEN, machine.state)

        machine.onTap()
        assertEquals(PillState.COMPACT, machine.state)
    }

    @Test
    fun `swipe up dismisses to hidden from any state`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.onTap()
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onSwipeUp()
        assertEquals(PillState.HIDDEN, machine.state)
    }
}
