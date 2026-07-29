package dev.pill.dynamicpill.core.event

import dev.pill.dynamicpill.core.model.EventType
import dev.pill.dynamicpill.core.model.PillEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeProvider(override val id: String) : EventProvider {
    override var currentEvent: PillEvent? = null
    private var listener: (() -> Unit)? = null

    var started = false
        private set

    override fun setListener(listener: () -> Unit) {
        this.listener = listener
    }

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
    }

    fun emit(event: PillEvent?) {
        currentEvent = event
        listener?.invoke()
    }
}

class ArbiterTest {

    @Test
    fun `higher priority event wins`() {
        val low = FakeProvider("low")
        val high = FakeProvider("high")
        val arbiter = Arbiter(listOf(low, high))
        var winner: PillEvent? = null
        arbiter.setOnWinnerChanged { winner = it }

        low.emit(PillEvent("low", EventType.MESSAGE, priority = 1, title = "Message"))
        assertEquals("low", winner?.providerId)

        high.emit(PillEvent("high", EventType.CALL, priority = 10, title = "Call"))
        assertEquals("high", winner?.providerId)

        high.emit(null)
        assertEquals("low", winner?.providerId)

        low.emit(null)
        assertNull(winner)
    }
}
