package dev.pill.dynamicpill.core.state

/**
 * Per the build plan (§6): HIDDEN = nothing to show (no active event, or a
 * manual swipe-up dismissal) — rendered as the collapsed circle over the
 * cutout, not true invisibility. IDLE is effectively unreachable now that
 * [PillStateMachine] resolves "no event" straight to HIDDEN rather than an
 * empty pill shape; kept in the enum since the build plan defines it and a
 * future manual-idle-pill affordance may want it back.
 *
 * COMPACT is still under-differentiated from a design standpoint — content
 * layout for it is being built out provider-by-provider (see design.md)
 * rather than as one generic look.
 */
enum class PillState {
    HIDDEN, IDLE, COMPACT, EXPANDED, TRANSIENT_POP
}
