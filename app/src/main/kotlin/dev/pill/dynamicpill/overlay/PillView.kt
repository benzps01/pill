package dev.pill.dynamicpill.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.pill.dynamicpill.core.state.PillState
import dev.pill.dynamicpill.core.state.PillStateMachine

/**
 * The window this view lives in is always sized to the max-expanded bounds and never
 * resized (CLAUDE.md rule 4). Only two animated floats drive the drawn shape:
 *  - [progress] 0=idle size, 1=expanded size
 *  - [presence] 0=hidden (invisible), 1=fully shown
 * Both are physics springs, retargetable mid-flight (rule 5) — a new tap redirects
 * the current motion instead of restarting it.
 *
 * Touch pass-through (rule 6) is done via the window's internal-insets touchable
 * region, not by resizing the window: only the currently-relevant sub-rect of this
 * fixed-size window actually receives touches, so anything outside it reaches the
 * app underneath untouched. HIDDEN still keeps a small hotspot at the idle bounds
 * (invisible) so a tap there can bring the pill back.
 */
class PillView(context: Context) : View(context) {

    companion object {
        const val IDLE_WIDTH_DP = 80f
        const val IDLE_HEIGHT_DP = 28f
        const val EXPANDED_WIDTH_DP = 320f
        const val EXPANDED_HEIGHT_DP = 90f

        private const val SWIPE_UP_MIN_DISTANCE_DP = 24f
        private const val SWIPE_UP_MIN_VELOCITY_DP = 400f
    }

    private val stateMachine = PillStateMachine(PillState.IDLE)

    private val maxWidthPx = dp(EXPANDED_WIDTH_DP)
    private val maxHeightPx = dp(EXPANDED_HEIGHT_DP)
    private val idleWidthPx = dp(IDLE_WIDTH_DP)
    private val idleHeightPx = dp(IDLE_HEIGHT_DP)
    private val swipeMinDistancePx = dp(SWIPE_UP_MIN_DISTANCE_DP)
    private val swipeMinVelocityPx = dp(SWIPE_UP_MIN_VELOCITY_DP)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val touchableRect = Rect()

    private var progress = 0f
    private var presence = 1f

    private val progressProperty = object : FloatPropertyCompat<PillView>("pillProgress") {
        override fun getValue(view: PillView) = view.progress
        override fun setValue(view: PillView, value: Float) {
            view.progress = value
            view.invalidate()
        }
    }

    private val presenceProperty = object : FloatPropertyCompat<PillView>("pillPresence") {
        override fun getValue(view: PillView) = view.presence
        override fun setValue(view: PillView, value: Float) {
            view.presence = value
            view.invalidate()
        }
    }

    private val progressSpring = SpringAnimation(this, progressProperty).apply {
        spring = SpringForce(0f).apply {
            dampingRatio = 0.55f
            stiffness = 500f
        }
        addEndListener { _, _, _, _ -> updateTouchableRegion() }
    }

    private val presenceSpring = SpringAnimation(this, presenceProperty).apply {
        spring = SpringForce(1f).apply {
            dampingRatio = 0.6f
            stiffness = 500f
        }
        addEndListener { _, _, _, _ -> updateTouchableRegion() }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                applyState(stateMachine.onTap(), animate = true)
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
                    applyState(stateMachine.onSwipeUp(), animate = true)
                    return true
                }
                return false
            }
        }
    )

    init {
        viewTreeObserver.addOnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(touchableRect)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyState(stateMachine.state, animate = false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(maxWidthPx.toInt(), maxHeightPx.toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Cancels in-flight springs without changing state — called on ACTION_SCREEN_OFF. */
    fun freezeAnimations() {
        progressSpring.cancel()
        presenceSpring.cancel()
    }

    private fun applyState(state: PillState, animate: Boolean) {
        val targetProgress = if (state == PillState.EXPANDED) 1f else 0f
        val targetPresence = if (state == PillState.HIDDEN) 0f else 1f

        if (animate) {
            progressSpring.animateToFinalPosition(targetProgress)
            presenceSpring.animateToFinalPosition(targetPresence)
        } else {
            progressSpring.cancel()
            presenceSpring.cancel()
            progress = targetProgress
            presence = targetPresence
            updateTouchableRegion()
            invalidate()
        }
    }

    private fun updateTouchableRegion() {
        val expanded = stateMachine.state == PillState.EXPANDED
        val w = if (expanded) maxWidthPx else idleWidthPx
        val h = if (expanded) maxHeightPx else idleHeightPx
        val left = ((maxWidthPx - w) / 2f).toInt()
        val top = ((maxHeightPx - h) / 2f).toInt()
        touchableRect.set(left, top, left + w.toInt(), top + h.toInt())
        requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = (idleWidthPx + (maxWidthPx - idleWidthPx) * progress) * presence
        val h = (idleHeightPx + (maxHeightPx - idleHeightPx) * progress) * presence
        if (w < 1f || h < 1f) return

        val left = (maxWidthPx - w) / 2f
        val top = (maxHeightPx - h) / 2f
        rect.set(left, top, left + w, top + h)
        paint.alpha = (255 * presence).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

        val textAlpha = (progress * presence * 255).toInt().coerceIn(0, 255)
        if (textAlpha > 0) {
            textPaint.alpha = textAlpha
            val textY = maxHeightPx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("Expanded", maxWidthPx / 2f, textY, textPaint)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
