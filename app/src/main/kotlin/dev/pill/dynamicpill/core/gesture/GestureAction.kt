package dev.pill.dynamicpill.core.gesture

/**
 * What a [Gesture] does when it fires. Split into two families by who
 * executes them:
 *
 *  - **State actions** ([EXPAND], [COLLAPSE], [DISMISS]) drive
 *    `PillStateMachine`, and are always available.
 *  - **Content actions** ([PLAY_PAUSE], [SKIP_NEXT], [SKIP_PREVIOUS],
 *    [OPEN_APP]) are delegated to the current winner `PillEvent`'s closures,
 *    so they're only meaningful when an event that supplies them is showing.
 *    A binding to one of these with no matching closure is simply a no-op —
 *    binding a media action while a call is on screen shouldn't do something
 *    surprising, it should do nothing.
 *
 * [COLLAPSE] steps down exactly one level (EXPANDED -> COMPACT -> HIDDEN);
 * [DISMISS] jumps straight to HIDDEN regardless of level.
 */
enum class GestureAction {
    NONE,
    EXPAND,
    COLLAPSE,
    DISMISS,
    PLAY_PAUSE,
    SKIP_NEXT,
    SKIP_PREVIOUS,
    OPEN_APP,
}
