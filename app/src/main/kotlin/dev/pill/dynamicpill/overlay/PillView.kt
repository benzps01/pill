package dev.pill.dynamicpill.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.pill.dynamicpill.core.state.PillState

/**
 * Pure renderer. Its window is sized to the max-expanded bounds once, in
 * OverlayService, and never resized again (CLAUDE.md rule 4) — the window is also
 * FLAG_NOT_TOUCHABLE, so it can stay fixed forever without ever blocking a tap
 * (touch handling lives in the separate, small PillTouchView instead; see that
 * class's doc for why touch pass-through needs a second window).
 *
 * Two floats drive the drawn shape, both physics springs retargetable mid-flight
 * (rule 5):
 *  - [progress] 0=idle size, 1=expanded size
 *  - [presence] 0=hidden (invisible), 1=fully shown
 */
class PillView(context: Context) : View(context) {

    companion object {
        const val IDLE_WIDTH_DP = 130f
        const val IDLE_HEIGHT_DP = 40f
        const val EXPANDED_WIDTH_DP = 380f
        const val EXPANDED_HEIGHT_DP = 120f
    }

    private val idleWidthPx = dp(IDLE_WIDTH_DP)
    private val idleHeightPx = dp(IDLE_HEIGHT_DP)
    private val expandedWidthPx = dp(EXPANDED_WIDTH_DP)
    private val expandedHeightPx = dp(EXPANDED_HEIGHT_DP)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

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
            dampingRatio = 0.65f
            stiffness = 180f
        }
    }

    private val presenceSpring = SpringAnimation(this, presenceProperty).apply {
        spring = SpringForce(1f).apply {
            dampingRatio = 0.65f
            stiffness = 180f
        }
    }

    init {
        applyState(PillState.IDLE, animate = false)
    }

    /** Retargets the springs mid-flight if already animating (rule 5). */
    fun applyState(state: PillState, animate: Boolean) {
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
            invalidate()
        }
    }

    /** Cancels in-flight springs without changing state — called on ACTION_SCREEN_OFF. */
    fun freezeAnimations() {
        progressSpring.cancel()
        presenceSpring.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(expandedWidthPx.toInt(), expandedHeightPx.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = (idleWidthPx + (expandedWidthPx - idleWidthPx) * progress) * presence
        val h = (idleHeightPx + (expandedHeightPx - idleHeightPx) * progress) * presence
        if (w < 1f || h < 1f) return

        val left = (width - w) / 2f
        val top = (height - h) / 2f
        rect.set(left, top, left + w, top + h)
        paint.alpha = (255 * presence).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)

        val textAlpha = (progress * presence * 255).toInt().coerceIn(0, 255)
        if (textAlpha > 0) {
            textPaint.alpha = textAlpha
            val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("Expanded", width / 2f, textY, textPaint)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
