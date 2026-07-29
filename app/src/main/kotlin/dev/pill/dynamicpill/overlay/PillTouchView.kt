package dev.pill.dynamicpill.overlay

import android.content.Context
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.pill.dynamicpill.core.gesture.Gesture

/**
 * Invisible hit-target window, kept separate from PillView (the renderer) so the
 * renderer's window can stay genuinely fixed at max-expanded bounds forever
 * (CLAUDE.md rule 4). This window's bounds instead track the current logical
 * state — Idle/Hidden get a small hotspot, Expanded gets the full bounds — which is
 * the only way to get exact touch pass-through (rule 6) without the hidden
 * `ViewTreeObserver.addOnComputeInternalInsetsListener` / `InternalInsetsInfo` API,
 * which isn't present in the public SDK stub.
 *
 * Reports **raw gestures only** — it owns no state machine and knows nothing
 * about what any gesture means. PillAccessibilityService resolves each one
 * through `GestureBindings` for the current state. The single exception is
 * transport-button hits, which are resolved here because they're a question
 * about *where* the touch landed rather than what it meant.
 */
class PillTouchView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val idleWidthPx: Int,
    private val idleHeightPx: Int,
    private val expandedWidthPx: Int,
    private val expandedHeightPx: Int,
    private val topOffsetPx: Int,
    private val onGesture: (Gesture) -> Unit,
    private val hasControls: () -> Boolean = { false },
    private val onPlayPause: () -> Unit = {},
    private val onSkipPrevious: () -> Unit = {},
    private val onSkipNext: () -> Unit = {},
    /**
     * Whether a gesture is worth detecting in the current state. Detection
     * isn't free: running double-tap detection makes every single tap wait to
     * see whether a second follows, and running horizontal-fling detection
     * lets a slightly-sideways press be swallowed as a fling instead of
     * reaching the tap handler. Both are skipped unless something is actually
     * bound to them.
     */
    private val isGestureBound: (Gesture) -> Boolean = { false }
) : View(context) {

    private enum class ControlAction { PREV, PLAY_PAUSE, NEXT }

    private var windowExpanded = false

    private val swipeMinDistancePx = dp(24f)
    private val swipeMinVelocityPx = dp(400f)

    private val density = resources.displayMetrics.density

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Only meaningful when double-tap is unbound; otherwise taps
                // are delivered via onSingleTapConfirmed below.
                if (isGestureBound(Gesture.DOUBLE_TAP)) return false
                return handleTap(e)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isGestureBound(Gesture.DOUBLE_TAP)) return false
                return handleTap(e)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isGestureBound(Gesture.DOUBLE_TAP)) return false
                onGesture(Gesture.DOUBLE_TAP)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isGestureBound(Gesture.LONG_PRESS)) return
                onGesture(Gesture.LONG_PRESS)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dy = start.y - e2.y
                val dx = e2.x - start.x

                if (dy > swipeMinDistancePx && -velocityY > swipeMinVelocityPx) {
                    onGesture(Gesture.SWIPE_UP)
                    return true
                }
                if (-dy > swipeMinDistancePx && velocityY > swipeMinVelocityPx) {
                    onGesture(Gesture.SWIPE_DOWN)
                    return true
                }

                val horizontalBound =
                    isGestureBound(Gesture.SWIPE_LEFT) || isGestureBound(Gesture.SWIPE_RIGHT)
                if (horizontalBound) {
                    if (-dx > swipeMinDistancePx && -velocityX > swipeMinVelocityPx) {
                        onGesture(Gesture.SWIPE_LEFT)
                        return true
                    }
                    if (dx > swipeMinDistancePx && velocityX > swipeMinVelocityPx) {
                        onGesture(Gesture.SWIPE_RIGHT)
                        return true
                    }
                }
                return false
            }
        }
    )

    /** Buttons win over the tap binding when the press actually lands on one. */
    private fun handleTap(e: MotionEvent): Boolean {
        if (windowExpanded && hasControls()) {
            when (hitTestControl(e.x, e.y)) {
                ControlAction.PREV -> { onSkipPrevious(); return true }
                ControlAction.PLAY_PAUSE -> { onPlayPause(); return true }
                ControlAction.NEXT -> { onSkipNext(); return true }
                null -> {}
            }
        }
        onGesture(Gesture.TAP)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Delivered only because the window sets FLAG_WATCH_OUTSIDE_TOUCH (see
        // resizeWindow). Critically, this event is a *copy* — the real touch
        // still goes to whatever is underneath, so reacting to it doesn't
        // break touch pass-through (rule 6).
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onGesture(Gesture.TAP_OUTSIDE)
            return false
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Called by PillAccessibilityService after every transition it applies. */
    fun resizeWindow(expanded: Boolean, watchOutsideTouch: Boolean) {
        val flagsChanged = watchOutsideTouch != currentlyWatchingOutside
        if (windowExpanded == expanded && !flagsChanged) return
        windowExpanded = expanded
        currentlyWatchingOutside = watchOutsideTouch
        // Deferred: resizing synchronously inside touch-event dispatch corrupts the
        // input channel (observed as "InputDispatcher: dropping inconsistent event"
        // storms on-device). This window is invisible, so the one-frame delay is
        // imperceptible either way.
        post {
            val height = if (expanded) expandedHeightPx else idleHeightPx
            layoutParams.width = if (expanded) expandedWidthPx else idleWidthPx
            layoutParams.height = height
            // Only ask for outside touches in states that actually do
            // something with them, rather than taking a constant stream of
            // events the bindings would discard anyway.
            layoutParams.flags = if (watchOutsideTouch) {
                layoutParams.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            } else {
                layoutParams.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
            }
            // The renderer anchors content to its own top and grows downward
            // (see PillView.onDraw), so this window stays top-aligned too.
            layoutParams.y = topOffsetPx
            windowManager.updateViewLayout(this, layoutParams)
        }
    }

    private var currentlyWatchingOutside = false

    private fun hitTestControl(x: Float, y: Float): ControlAction? {
        val cy = PillView.controlCenterY(density)
        val radius = PillView.controlRadiusPx(density)
        val hitRadius = radius * 1.2f // slightly generous over the drawn circle
        val actions = listOf(ControlAction.PREV, ControlAction.PLAY_PAUSE, ControlAction.NEXT)
        for (i in 0 until PillView.CONTROL_COUNT) {
            val dx = x - PillView.controlCenterX(i, expandedWidthPx.toFloat(), density)
            val dy = y - cy
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return actions[i]
        }
        return null
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
