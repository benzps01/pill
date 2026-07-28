package dev.pill.dynamicpill.core.state

import dev.pill.dynamicpill.core.model.EventType
import dev.pill.dynamicpill.core.model.PillEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PillStateMachineTest {

    private val event = PillEvent("spotify", EventType.MEDIA, priority = 5, title = "Song")

    @Test
    fun `expanding a live event then losing it collapses to idle`() {
        val machine = PillStateMachine(PillState.IDLE)
        machine.onEvent(event) // -> COMPACT
        machine.onTap() // COMPACT -> EXPANDED, expandedViaEvent = true
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onEvent(null) // event ended while looking at it
        assertEquals(PillState.IDLE, machine.state)
    }

    @Test
    fun `manually expanding with nothing playing stays open through event churn`() {
        val machine = PillStateMachine(PillState.IDLE)
        machine.onTap() // IDLE -> EXPANDED, expandedViaEvent = false
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onEvent(event)
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onEvent(null)
        assertEquals(PillState.EXPANDED, machine.state)
    }

    @Test
    fun `expanded event still playing is unaffected by onEvent`() {
        val machine = PillStateMachine(PillState.IDLE)
        machine.onEvent(event)
        machine.onTap()
        assertEquals(PillState.EXPANDED, machine.state)

        machine.onEvent(event)
        assertEquals(PillState.EXPANDED, machine.state)
    }
}
