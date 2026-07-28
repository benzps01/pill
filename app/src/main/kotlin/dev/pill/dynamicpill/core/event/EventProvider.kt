package dev.pill.dynamicpill.core.event

import dev.pill.dynamicpill.core.model.PillEvent

/**
 * A single source of truth for one kind of live event (call / message /
 * media). Push-based, not polled (CLAUDE.md rule 1) — implementations call
 * their listener whenever [currentEvent] changes; they never get asked to
 * check on a timer.
 */
interface EventProvider {
    val id: String
    val currentEvent: PillEvent?
    fun setListener(listener: () -> Unit)
}
