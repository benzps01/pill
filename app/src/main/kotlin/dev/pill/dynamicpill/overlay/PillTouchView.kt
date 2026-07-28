package dev.pill.dynamicpill.overlay

import android.content.Context
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
/**
 * Invisible hit-target window, kept separate from PillView (the renderer) so the
 * renderer's window can stay genuinely fixed at max-expanded bounds forever
 * (CLAUDE.md rule 4). This window's bounds instead track the current logical
 * state — Idle/Hidden get a small hotspot, Expanded gets the full bounds — which is
 * the only way to get exact touch pass-through (rule 6) without the hidden
 * `ViewTreeObserver.addOnComputeInternalInsetsListener` / `InternalInsetsInfo` API,
 * which isn't present in the public SDK stub.
 *
 * Owns no state machine — just reports gestures upward. The single owner is
 * PillAccessibilityService, since Phase 3 event-driven transitions (Arbiter
 * winner changes) need to drive the same state as touch, not a separate copy.
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
    private val onTap: () -> Unit,
    private val onSwipeUp: () -> Unit
) : View(context) {

    private var windowExpanded = false

    private val swipeMinDistancePx = dp(24f)
    private val swipeMinVelocityPx = dp(400f)

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val distance = start.y - e2.y
                if (distance > swipeMinDistancePx && -velocityY > swipeMinVelocityPx) {
                    onSwipeUp()
                    return true
                }
                return false
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Called by PillAccessibilityService after every transition it applies. */
    fun resizeWindow(expanded: Boolean) {
        if (windowExpanded == expanded) return
        windowExpanded = expanded
        // Deferred: resizing synchronously inside touch-event dispatch corrupts the
        // input channel (observed as "InputDispatcher: dropping inconsistent event"
        // storms on-device). This window is invisible, so the one-frame delay is
        // imperceptible either way.
        post {
            val height = if (expanded) expandedHeightPx else idleHeightPx
            layoutParams.width = if (expanded) expandedWidthPx else idleWidthPx
            layoutParams.height = height
            // The renderer anchors content to its own top and grows downward
            // (see PillView.onDraw), so this window stays top-aligned too.
            layoutParams.y = topOffsetPx
            windowManager.updateViewLayout(this, layoutParams)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
