package dev.pill.dynamicpill.core.gesture

import dev.pill.dynamicpill.core.state.PillState

/**
 * Maps `(state, gesture) -> action`. The whole point is that no behavior is
 * hardcoded in a `when` branch in the touch or overlay layer: the pill does
 * whatever this table says, so Phase 5's settings UI has something real to
 * edit and the touch layer never has to change again to add a binding.
 *
 * Keyed by **state as well as gesture** because a gesture already means
 * different things at different levels — a tap reveals the pill from the
 * circle, expands it from compact, and opens the source app when expanded.
 * A gesture-only table couldn't express that.
 *
 * Pure Kotlin, no Android types (CLAUDE.md module table) — unit-testable
 * alongside `Arbiter` and `PillStateMachine`.
 *
 * Unbound cells resolve to [GestureAction.NONE], so [bindings] only needs to
 * carry the non-default entries and the touch layer can ask about any
 * combination without the map having to be exhaustive.
 */
class GestureBindings(
    private val bindings: Map<Pair<PillState, Gesture>, GestureAction> = DEFAULTS
) {

    fun actionFor(state: PillState, gesture: Gesture): GestureAction =
        bindings[state to gesture] ?: GestureAction.NONE

    /**
     * True if [gesture] does anything at all in [state]. The touch layer uses
     * this to avoid detecting gestures nobody asked for — see
     * PillTouchView's double-tap and horizontal-fling guards, where running
     * detection unconditionally would cost tap latency and cause
     * false positives respectively.
     */
    fun isBound(state: PillState, gesture: Gesture): Boolean =
        actionFor(state, gesture) != GestureAction.NONE

    companion object {
        /**
         * Vertical axis is symmetric: swipe down goes up a level
         * (HIDDEN -> COMPACT -> EXPANDED), swipe up steps back down. Tap is
         * the primary action at each level. Tapping away closes the expanded
         * card.
         *
         * DOUBLE_TAP and LONG_PRESS are deliberately left unbound rather than
         * given a sensible default: enabling double-tap anywhere forces every
         * single tap in that state to wait and see whether a second one
         * follows, so that responsiveness cost should be something the user
         * opts into. Per-state keying means it only ever costs in the states
         * they actually bind it in.
         *
         * Horizontal swipes are likewise unbound — detecting them when
         * nothing is listening risks a flick being read as a fling instead of
         * the tap it was meant to be.
         */
        val DEFAULTS: Map<Pair<PillState, Gesture>, GestureAction> = mapOf(
            // Circle: nothing on screen but the resting dot. Surfacing the
            // pill is the only thing to do. (PillStateMachine still gates
            // this on there actually being a live event — the table says
            // "try to expand", not "there is definitely something to show".)
            (PillState.HIDDEN to Gesture.TAP) to GestureAction.EXPAND,
            (PillState.HIDDEN to Gesture.SWIPE_DOWN) to GestureAction.EXPAND,

            // Compact: open it up, or push it back to the circle.
            (PillState.COMPACT to Gesture.TAP) to GestureAction.EXPAND,
            (PillState.COMPACT to Gesture.SWIPE_DOWN) to GestureAction.EXPAND,
            (PillState.COMPACT to Gesture.SWIPE_UP) to GestureAction.COLLAPSE,

            // Expanded: everything is already visible, so a tap hands off to
            // the source app. (Taps that land on a transport button are
            // handled before the binding is consulted — see PillTouchView.)
            (PillState.EXPANDED to Gesture.TAP) to GestureAction.OPEN_APP,
            (PillState.EXPANDED to Gesture.SWIPE_UP) to GestureAction.COLLAPSE,
            (PillState.EXPANDED to Gesture.TAP_OUTSIDE) to GestureAction.COLLAPSE,
        )
    }
}
