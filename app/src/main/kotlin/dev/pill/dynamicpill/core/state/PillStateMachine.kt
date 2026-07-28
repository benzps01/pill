package dev.pill.dynamicpill.core.state

import dev.pill.dynamicpill.core.model.PillEvent

/**
 * Pure Kotlin, no Android deps — unit-testable in isolation (CLAUDE.md `core` rule).
 *
 * With no active event, the pill rests as HIDDEN (the collapsed circle) —
 * there's nothing to show, so tapping it is a no-op rather than opening a
 * placeholder EXPANDED. That means EXPANDED can now only ever be reached
 * from COMPACT (i.e. a live event was open when tapped), so it's always
 * safe to auto-collapse back to HIDDEN the moment that event ends — no
 * separate "was this a manual expand?" tracking needed.
 */
class PillStateMachine(initial: PillState = PillState.HIDDEN) {

    var state: PillState = initial
        private set

    private var hasActiveEvent = false

    fun onTap(): PillState {
        state = when (state) {
            // A live event can still be playing after a manual swipe-up
            // dismiss (HIDDEN) — tap should reveal it again, not stay stuck.
            // Only a genuinely empty pill (no event at all) no-ops.
            PillState.HIDDEN, PillState.IDLE -> if (hasActiveEvent) PillState.COMPACT else state
            PillState.EXPANDED -> if (hasActiveEvent) PillState.COMPACT else PillState.HIDDEN
            PillState.COMPACT -> PillState.EXPANDED
            PillState.TRANSIENT_POP -> PillState.EXPANDED
        }
        return state
    }

    fun onSwipeUp(): PillState {
        state = PillState.HIDDEN
        return state
    }

    /**
     * Renderer-facing mapping from the Arbiter's winner to a pill state
     * (build plan Phase 3). A live event surfaces the pill as COMPACT; no
     * event collapses it to HIDDEN, including out of EXPANDED — since
     * EXPANDED is now only reachable via a live event, there's no manual
     * "expanded with nothing playing" case to protect. Finer semantics —
     * TRANSIENT_POP — are deferred; see the note on [PillState].
     */
    fun onEvent(event: PillEvent?): PillState {
        hasActiveEvent = event != null
        if (state == PillState.EXPANDED) {
            if (!hasActiveEvent) state = PillState.HIDDEN
            return state
        }
        state = if (hasActiveEvent) PillState.COMPACT else PillState.HIDDEN
        return state
    }
}
