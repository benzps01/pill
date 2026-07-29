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

    /**
     * Begin observing the underlying source. On the interface rather than
     * each concrete provider so hosts can drive a `List<EventProvider>`
     * without knowing what's in it (CLAUDE.md rule 7) — needing the concrete
     * type just to call start/stop was what kept `overlay` importing
     * `providers.spotify`.
     */
    fun start()

    /** Release everything [start] acquired. Must be safe to call without a prior [start]. */
    fun stop()
}
