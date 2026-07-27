package dev.pill.dynamicpill.core.state

/**
 * DEFERRED (Phase 3/4, needs the real Arbiter/EventProvider):
 *
 * Per the build plan (§6), HIDDEN means "nothing" — reserved for system
 * context (fullscreen/immersive app, screen off, landscape), not a manual
 * gesture. Right now [PillTouchView]'s swipe-up drives HIDDEN as a Phase-1
 * placeholder, and [dev.pill.dynamicpill.overlay.PillView] renders it as a
 * collapsed circle over the cutout (not true invisibility) since that's the
 * more useful placeholder visual. Once the Arbiter exists, true full-hide
 * (screen off / fullscreen app) should be a separate visibility mechanism
 * from this circle-collapse, and swipe-up's real meaning needs revisiting —
 * see build plan §6 for the intended Idle/Compact/Expanded semantics
 * (Compact in particular is unbuilt: PillView never renders it distinctly
 * from Idle yet, and "stay Compact while media is playing instead of
 * returning to Idle" requires the Arbiter to know about live sessions).
 */
enum class PillState {
    HIDDEN, IDLE, COMPACT, EXPANDED, TRANSIENT_POP
}
