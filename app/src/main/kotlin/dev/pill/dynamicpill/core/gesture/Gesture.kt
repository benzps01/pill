package dev.pill.dynamicpill.core.gesture

/**
 * A raw input gesture, with no opinion about what it does — the touch layer
 * detects and reports these, [GestureBindings] decides what each one means
 * in the current state.
 *
 * [TAP_OUTSIDE] is the odd one out: it isn't a gesture *on* the pill but a
 * touch anywhere else on the screen. It exists so "tap away to close" is
 * expressible in the same table as everything else. Crucially it must never
 * consume that touch — see PillTouchView's FLAG_WATCH_OUTSIDE_TOUCH handling
 * and CLAUDE.md rule 6.
 */
enum class Gesture {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    TAP_OUTSIDE,
}
