package dev.pill.dynamicpill.core.state

import dev.pill.dynamicpill.core.model.PillEvent

/**
 * Pure Kotlin, no Android deps — unit-testable in isolation (CLAUDE.md `core` rule).
 * Phase 1 only wires tap and swipe-up; COMPACT/TRANSIENT_POP are reachable states
 * reserved for the event arbiter landing in Phase 3.
 */
class PillStateMachine(initial: PillState = PillState.IDLE) {

    var state: PillState = initial
        private set

    fun onTap(): PillState {
        state = when (state) {
            PillState.HIDDEN -> PillState.IDLE
            PillState.IDLE -> PillState.EXPANDED
            PillState.EXPANDED -> PillState.IDLE
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
     * (build plan Phase 3). Deliberately minimal: a live event surfaces the
     * pill as COMPACT, no event drops it back to IDLE, and EXPANDED (user is
     * actively looking at it) is left alone either way. Finer semantics —
     * TRANSIENT_POP, staying pill-shaped through a Spotify session instead
     * of collapsing — are deferred; see the note on [PillState].
     */
    fun onEvent(event: PillEvent?): PillState {
        if (state == PillState.EXPANDED) return state
        state = if (event != null) PillState.COMPACT else PillState.IDLE
        return state
    }
}
