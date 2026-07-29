package dev.pill.dynamicpill.core.state

import dev.pill.dynamicpill.core.model.PillEvent

/**
 * Pure Kotlin, no Android deps — unit-testable in isolation (CLAUDE.md `core` rule).
 *
 * Transitions are named after *intent* ([expand] / [collapse] / [dismiss]),
 * not after the gesture that triggered them, because gestures are
 * user-bindable now (see `core.gesture.GestureBindings`) — "swipe up" is no
 * longer synonymous with any particular movement through the states.
 *
 * [expand] and [collapse] are exact inverses, stepping one level at a time:
 *
 *     HIDDEN  --expand-->    COMPACT  --expand-->    EXPANDED
 *     HIDDEN  <--collapse--  COMPACT  <--collapse--  EXPANDED
 *
 * With no active event the pill rests as HIDDEN (the collapsed circle) —
 * there's nothing to show, so expanding is a no-op rather than opening a
 * placeholder. That means EXPANDED can only ever be reached from COMPACT
 * (i.e. a live event was open), so it's always safe to auto-collapse back to
 * HIDDEN the moment that event ends — no "was this a manual expand?"
 * tracking needed.
 */
class PillStateMachine(initial: PillState = PillState.HIDDEN) {

    var state: PillState = initial
        private set

    private var hasActiveEvent = false

    /**
     * Steps up one level. No-op at the top.
     *
     * A live event can still be playing after a manual dismiss, so expanding
     * out of HIDDEN reveals it again rather than staying stuck; only a
     * genuinely empty pill (no event at all) no-ops.
     */
    fun expand(): PillState {
        state = when (state) {
            PillState.HIDDEN, PillState.IDLE -> if (hasActiveEvent) PillState.COMPACT else state
            PillState.COMPACT, PillState.TRANSIENT_POP -> PillState.EXPANDED
            PillState.EXPANDED -> PillState.EXPANDED
        }
        return state
    }

    /**
     * Steps down one level. Bottoms out at HIDDEN.
     *
     * This can momentarily leave COMPACT showing with no live event, which
     * doesn't matter: [onEvent] resolves a null event straight to HIDDEN, so
     * the state can't linger there.
     */
    fun collapse(): PillState {
        state = when (state) {
            PillState.EXPANDED, PillState.TRANSIENT_POP -> PillState.COMPACT
            PillState.COMPACT, PillState.IDLE, PillState.HIDDEN -> PillState.HIDDEN
        }
        return state
    }

    /** Straight back to the resting circle from anywhere, skipping the levels between. */
    fun dismiss(): PillState {
        state = PillState.HIDDEN
        return state
    }

    /**
     * Renderer-facing mapping from the Arbiter's winner to a pill state
     * (build plan Phase 3). A live event surfaces the pill as COMPACT; no
     * event collapses it to HIDDEN, including out of EXPANDED — since
     * EXPANDED is only reachable via a live event, there's no manual
     * "expanded with nothing playing" case to protect.
     *
     * Deliberately does *not* touch EXPANDED while an event is still live:
     * metadata churn (track change, play/pause) must never yank a card shut
     * under the user. Finer semantics — TRANSIENT_POP — are deferred; see
     * the note on [PillState].
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
