package dev.pill.dynamicpill.core.state

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
}
