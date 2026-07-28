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

    // True only when the current EXPANDED was reached by tapping a live
    // event open (COMPACT -> EXPANDED). Lets onEvent tell "tapped open an
    // event, then it ended" (should auto-collapse) apart from "manually
    // expanded with nothing playing" (should stay open regardless of
    // unrelated event churn).
    private var expandedViaEvent = false

    fun onTap(): PillState {
        state = when (state) {
            PillState.HIDDEN -> PillState.IDLE
            PillState.IDLE -> {
                expandedViaEvent = false
                PillState.EXPANDED
            }
            PillState.EXPANDED -> PillState.IDLE
            PillState.COMPACT -> {
                expandedViaEvent = true
                PillState.EXPANDED
            }
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
     * (build plan Phase 3). A live event surfaces the pill as COMPACT, no
     * event drops it back to IDLE. EXPANDED is left alone unless it was
     * reached by tapping open a live event ([expandedViaEvent]) and that
     * event has now ended — a manually-opened EXPANDED (nothing was
     * playing) stays open regardless of unrelated event churn. Finer
     * semantics — TRANSIENT_POP, staying pill-shaped through a Spotify
     * session instead of collapsing — are deferred; see the note on
     * [PillState].
     */
    fun onEvent(event: PillEvent?): PillState {
        if (state == PillState.EXPANDED) {
            if (expandedViaEvent && event == null) {
                expandedViaEvent = false
                state = PillState.IDLE
            }
            return state
        }
        state = if (event != null) PillState.COMPACT else PillState.IDLE
        return state
    }
}
