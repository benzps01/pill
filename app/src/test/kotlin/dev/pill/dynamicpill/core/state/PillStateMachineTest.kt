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
    fun `expanding an empty pill is a no-op`() {
        val machine = PillStateMachine()
        machine.expand()
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `a live event surfaces compact, expanding opens it`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        assertEquals(PillState.COMPACT, machine.state)

        machine.expand()
        assertEquals(PillState.EXPANDED, machine.state)
    }

    @Test
    fun `expanding past the top stays expanded`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.expand()
        machine.expand()
        assertEquals(PillState.EXPANDED, machine.state)
    }

    @Test
    fun `expanded event ending auto-collapses to hidden`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.expand()
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onEvent(null)
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `collapse steps down one level at a time`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.expand()
        assertEquals(PillState.EXPANDED, machine.state)

        machine.collapse()
        assertEquals(PillState.COMPACT, machine.state)

        machine.collapse()
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `collapse and expand are inverses`() {
        val machine = PillStateMachine()
        machine.onEvent(event) // -> COMPACT
        machine.expand()
        machine.collapse()
        assertEquals(PillState.COMPACT, machine.state)

        machine.expand()
        assertEquals(PillState.EXPANDED, machine.state)
    }

    @Test
    fun `collapsing past the bottom stays hidden`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.collapse()
        machine.collapse()
        assertEquals(PillState.HIDDEN, machine.state)
    }

    @Test
    fun `expanding after a dismiss with the event still live reopens compact`() {
        val machine = PillStateMachine()
        machine.onEvent(event) // -> COMPACT
        machine.dismiss() // manual dismiss, but the song is still playing
        assertEquals(PillState.HIDDEN, machine.state)

        machine.expand()
        assertEquals(PillState.COMPACT, machine.state)
    }

    @Test
    fun `dismiss jumps to hidden from any state, skipping the levels between`() {
        val machine = PillStateMachine()
        machine.onEvent(event)
        machine.expand()
        assertEquals(PillState.EXPANDED, machine.state)

        machine.dismiss()
        assertEquals(PillState.HIDDEN, machine.state)
    }
}
