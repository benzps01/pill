package dev.pill.dynamicpill.overlay

import android.content.Context
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.pill.dynamicpill.core.state.PillState
import dev.pill.dynamicpill.core.state.PillStateMachine

/**
 * Invisible hit-target window, kept separate from PillView (the renderer) so the
 * renderer's window can stay genuinely fixed at max-expanded bounds forever
 * (CLAUDE.md rule 4). This window's bounds instead track the current logical
 * state — Idle/Hidden get a small hotspot, Expanded gets the full bounds — which is
 * the only way to get exact touch pass-through (rule 6) without the hidden
 * `ViewTreeObserver.addOnComputeInternalInsetsListener` / `InternalInsetsInfo` API,
 * which isn't present in the public SDK stub.
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
    private val onStateChanged: (PillState, Boolean) -> Unit
) : View(context) {

    private val stateMachine = PillStateMachine(PillState.IDLE)
    private var windowExpanded = false

    private val swipeMinDistancePx = dp(24f)
    private val swipeMinVelocityPx = dp(400f)

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                transition(stateMachine.onTap())
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
                    transition(stateMachine.onSwipeUp())
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

    private fun transition(state: PillState) {
        onStateChanged(state, true)
        resizeWindow(expanded = state == PillState.EXPANDED || state == PillState.TRANSIENT_POP)
    }

    private fun resizeWindow(expanded: Boolean) {
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
            // The renderer always draws centered inside its fixed max-height canvas, so
            // this window's vertical center must match that canvas's center too —
            // otherwise a shorter (Idle) hit-target top-aligned at the same y sits
            // above where the pill is actually drawn.
            layoutParams.y = topOffsetPx + (expandedHeightPx - height) / 2
            windowManager.updateViewLayout(this, layoutParams)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
