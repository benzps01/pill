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
 * PillAccessibilityService, and never resized again (CLAUDE.md rule 4) — the window is also
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
        const val IDLE_HEIGHT_DP = 30f
        const val EXPANDED_WIDTH_DP = 300f
        const val EXPANDED_HEIGHT_DP = 130f
        private const val EXPANDED_CORNER_RADIUS_DP = 28f
        // Collapsed (presence=0) width — smaller than idle height, so the
        // resting circle over the cutout reads as a small dot, not a disc as
        // wide as the pill is tall.
        private const val CIRCLE_WIDTH_DP = 30f
    }

    private val idleWidthPx = dp(IDLE_WIDTH_DP)
    private val idleHeightPx = dp(IDLE_HEIGHT_DP)
    private val expandedWidthPx = dp(EXPANDED_WIDTH_DP)
    private val expandedHeightPx = dp(EXPANDED_HEIGHT_DP)
    private val expandedCornerRadiusPx = dp(EXPANDED_CORNER_RADIUS_DP)
    private val circleWidthPx = dp(CIRCLE_WIDTH_DP)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var progress = 0f
    private var presence = 1f
    private var contentTitle: String? = "Expanded"
    private var contentSubtitle: String? = null

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
            // Critically damped: settles exactly at the target size with no
            // overshoot (unlike presenceSpring, this transition shouldn't wobble).
            dampingRatio = 1f
            stiffness = 25f
        }
        // progress is a normalized 0..1 value, not pixels — without this the
        // default rest threshold (1f) is the whole animation range, so the
        // spring self-cancels on the first frame regardless of stiffness.
        setMinimumVisibleChange(1f / 500f)
    }

    private val presenceSpring = SpringAnimation(this, presenceProperty).apply {
        spring = SpringForce(1f).apply {
            dampingRatio = 0.8f
            stiffness = 26.2f
        }
        setMinimumVisibleChange(1f / 500f)
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

    /** Plain-text placeholder content until real Expanded-state UI (art, transport) exists. */
    fun setContent(title: String?, subtitle: String?) {
        contentTitle = title
        contentSubtitle = subtitle
        invalidate()
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
        // At presence=0 the pill collapses to a small circle over the cutout
        // (width == height == circleWidthPx) instead of shrinking to nothing;
        // `presence` grows it from that circle out to the full pill shape.
        // This is a small, bounded move (circleWidthPx -> full height), not
        // the old "grow from nothing" — width shrinks the same amount so it
        // stays round, not an oval, at rest.
        val fullHeight = idleHeightPx + (expandedHeightPx - idleHeightPx) * progress
        val fullWidth = idleWidthPx + (expandedWidthPx - idleWidthPx) * progress
        val h = circleWidthPx + (fullHeight - circleWidthPx) * presence
        val w = circleWidthPx + (fullWidth - circleWidthPx) * presence
        if (w < 1f || h < 1f) return

        val left = (width - w) / 2f
        // Top=0 only at presence=1 (PS/ES), so those still grow strictly
        // downward from the cutout. Below that, top shifts down just enough
        // to keep the shape's vertical center fixed at idleHeightPx/2 as it
        // shrinks toward the circle, so CS stays concentric with the camera
        // instead of sharing PS's top edge (which makes a shorter CS sit
        // higher than PS). Deliberately anchored to idleHeightPx, not the
        // live fullHeight — fullHeight tracks the progress spring (PS<->ES),
        // and during ES->CS (swipe up) both springs move at once, so using
        // fullHeight here made the circle dip down while progress was still
        // settling from 1->0, then correct back up. CS/PS transitions only
        // happen at progress=0 anyway, so idleHeightPx is the right anchor
        // and has zero dependency on the progress spring.
        val top = (idleHeightPx - circleWidthPx) / 2f * (1f - presence)
        rect.set(left, top, left + w, top + h)
        // Presence no longer drives visibility — the collapsed circle stays
        // fully opaque. True full-invisibility (fullscreen app/screen off,
        // the real build-plan HIDDEN state) is a separate, not-yet-built
        // concept — see the note on PillState.HIDDEN.
        paint.alpha = 255
        // Idle is a full capsule (radius = half height); Expanded is a flatter
        // rounded-rect look, not a stadium shape. At progress=0 this equals
        // h/2, which combined with w==h at presence=0 draws a perfect circle.
        val idleRadius = idleHeightPx / 2f
        val cornerRadius = idleRadius + (expandedCornerRadiusPx - idleRadius) * progress
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        val textAlpha = (progress * 255).toInt().coerceIn(0, 255)
        val title = contentTitle
        if (textAlpha > 0 && title != null) {
            textPaint.alpha = textAlpha
            val subtitle = contentSubtitle
            if (subtitle != null) {
                canvas.drawText(title, width / 2f, top + h / 2f - dp(2f), textPaint)
                val subtitlePaint = Paint(textPaint).apply { textSize = dp(12f) }
                canvas.drawText(subtitle, width / 2f, top + h / 2f + dp(16f), subtitlePaint)
            } else {
                val textY = top + h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(title, width / 2f, textY, textPaint)
            }
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
