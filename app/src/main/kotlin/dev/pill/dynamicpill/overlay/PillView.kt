package dev.pill.dynamicpill.overlay

import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.sin

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
 *
 * Content (icon/title/subtitle) is set via [setContent] and cross-fades
 * between two looks purely as a function of [progress] — no separate
 * Compact/Expanded draw-mode flag is needed: at progress=0 with content
 * present, the source icon + equalizer bars show (Compact/PS); as progress
 * rises toward 1, that fades out and album art + title/artist fades in
 * (Expanded/ES). No content set at all (IDLE) draws nothing.
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

        private const val ART_SIZE_DP = 48f
        private const val CONTENT_PADDING_DP = 20f
        private const val COMPACT_ICON_SIZE_DP = 18f
        private const val COMPACT_PADDING_DP = 10f
        private const val BAR_COUNT = 3
        private const val BAR_WIDTH_DP = 3f
        private const val BAR_GAP_DP = 2f
        private const val BAR_MAX_HEIGHT_DP = 14f
        private const val MARQUEE_SPEED_DP_PER_SEC = 30f
        private const val MARQUEE_GAP_DP = 24f

        // Shared with PillTouchView for hit-testing — single source of truth
        // for the transport-control layout so drawing and tap detection
        // can't drift apart.
        const val CONTROL_BUTTON_DIAMETER_DP = 40f
        const val CONTROL_BUTTON_SPACING_DP = 56f
        const val CONTROL_BUTTON_BOTTOM_MARGIN_DP = 20f
    }

    private val idleWidthPx = dp(IDLE_WIDTH_DP)
    private val idleHeightPx = dp(IDLE_HEIGHT_DP)
    private val expandedWidthPx = dp(EXPANDED_WIDTH_DP)
    private val expandedHeightPx = dp(EXPANDED_HEIGHT_DP)
    private val expandedCornerRadiusPx = dp(EXPANDED_CORNER_RADIUS_DP)
    private val circleWidthPx = dp(CIRCLE_WIDTH_DP)
    private val artSizePx = dp(ART_SIZE_DP)
    private val contentPaddingPx = dp(CONTENT_PADDING_DP)
    private val compactIconSizePx = dp(COMPACT_ICON_SIZE_DP)
    private val compactPaddingPx = dp(COMPACT_PADDING_DP)
    private val barWidthPx = dp(BAR_WIDTH_DP)
    private val barGapPx = dp(BAR_GAP_DP)
    private val barMaxHeightPx = dp(BAR_MAX_HEIGHT_DP)
    private val marqueeSpeedPxPerSec = dp(MARQUEE_SPEED_DP_PER_SEC)
    private val marqueeGapPx = dp(MARQUEE_GAP_DP)
    private val controlButtonRadiusPx = dp(CONTROL_BUTTON_DIAMETER_DP) / 2f
    private val controlButtonSpacingPx = dp(CONTROL_BUTTON_SPACING_DP)
    private val controlButtonBottomMarginPx = dp(CONTROL_BUTTON_BOTTOM_MARGIN_DP)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.LEFT
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        textAlign = Paint.Align.LEFT
        alpha = 200
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPath = android.graphics.Path()
    private val rect = RectF()
    private val bitmapDst = RectF()
    private val barRect = RectF()

    private var progress = 0f
    private var presence = 1f
    private var contentTitle: String? = null
    private var contentSubtitle: String? = null
    private var contentIcon: Bitmap? = null
    private var contentIsPlaying: Boolean = true
    private var sourceIcon: Bitmap? = null

    // Continuous, screen-on-only visual flourish (equalizer bars / marquee),
    // not an event source — distinct from the rule-1 "never poll" ban on
    // polling business state. Self-schedules via postOnAnimation, not a
    // Handler.postDelayed timer.
    private var screenOn = true
    private var animRunning = false
    private var animStartNanos = 0L
    private val tick: () -> Unit = {
        invalidate()
        if (screenOn && shouldAnimate()) {
            postOnAnimation(tickRunnable)
        } else {
            animRunning = false
        }
    }
    private val tickRunnable = Runnable { tick() }

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
        startAnimLoopIfNeeded()
    }

    /** Source app icon shown in Compact/PS (e.g. Spotify's icon) — set once, rarely changes. */
    fun setSourceIcon(icon: Bitmap?) {
        sourceIcon = icon
        invalidate()
    }

    /** Real event content. Pass title null to clear back to no-content (IDLE look). */
    fun setContent(title: String?, subtitle: String?, icon: Bitmap?, isPlaying: Boolean = true) {
        contentTitle = title
        contentSubtitle = subtitle
        contentIcon = icon
        contentIsPlaying = isPlaying
        invalidate()
        startAnimLoopIfNeeded()
    }

    /** Cancels in-flight springs and the visual-flourish loop — called on ACTION_SCREEN_OFF (rule 3). */
    fun freezeAnimations() {
        progressSpring.cancel()
        presenceSpring.cancel()
        screenOn = false
    }

    /** Called on ACTION_SCREEN_ON — nothing else re-triggers a frozen flourish loop. */
    fun resumeAnimationsIfNeeded() {
        screenOn = true
        startAnimLoopIfNeeded()
    }

    private fun startAnimLoopIfNeeded() {
        if (animRunning || !screenOn || !shouldAnimate()) return
        animRunning = true
        animStartNanos = System.nanoTime()
        postOnAnimation(tickRunnable)
    }

    private fun shouldAnimate(): Boolean {
        if (contentTitle == null) return false
        val barsAnimating = contentIsPlaying && progress < 0.98f
        return barsAnimating || textOverflows(contentTitle, titlePaint) || textOverflows(contentSubtitle, subtitlePaint)
    }

    private fun textColumnWidthPx(): Float {
        val textLeft = contentPaddingPx + artSizePx + dp(12f)
        return expandedWidthPx - textLeft - contentPaddingPx
    }

    private fun textOverflows(text: String?, paint: Paint): Boolean {
        if (text.isNullOrEmpty()) return false
        return paint.measureText(text) > textColumnWidthPx()
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

        val title = contentTitle ?: return
        val compactAlpha = ((1f - progress) * presence * 255f).toInt().coerceIn(0, 255)
        val expandedAlpha = (progress * presence * 255f).toInt().coerceIn(0, 255)
        val elapsedSeconds = (System.nanoTime() - animStartNanos) / 1_000_000_000f

        if (compactAlpha > 0) drawCompactContent(canvas, rect, compactAlpha, elapsedSeconds)
        if (expandedAlpha > 0) {
            drawExpandedContent(canvas, rect, expandedAlpha, title, contentSubtitle, contentIcon, elapsedSeconds)
            // Only for real provider content (contentIcon is only ever set from
            // a real PillEvent) — the no-event manual "Expanded" placeholder
            // path has nothing for these buttons to control.
            if (contentIcon != null) drawTransportControls(canvas, expandedAlpha)
        }
    }

    private fun drawCompactContent(canvas: Canvas, rect: RectF, alpha: Int, elapsedSeconds: Float) {
        val icon = sourceIcon
        if (icon != null) {
            bitmapPaint.alpha = alpha
            val iconTop = rect.top + (rect.height() - compactIconSizePx) / 2f
            val iconLeft = rect.left + compactPaddingPx
            bitmapDst.set(iconLeft, iconTop, iconLeft + compactIconSizePx, iconTop + compactIconSizePx)
            canvas.drawBitmap(icon, null, bitmapDst, bitmapPaint)
        }

        barPaint.alpha = alpha
        val barsTotalWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * barGapPx
        val barsLeft = rect.right - compactPaddingPx - barsTotalWidth
        val barCenterY = rect.top + rect.height() / 2f
        for (i in 0 until BAR_COUNT) {
            // Paused: literal "||" pause glyph — skip the middle bar, draw
            // the two outer bars at equal height. Playing: animated.
            if (!contentIsPlaying && i == 1) continue
            val fraction = if (contentIsPlaying) {
                val phase = elapsedSeconds * 4f + i * 1.3f
                0.35f + 0.65f * ((sin(phase) + 1f) / 2f)
            } else {
                0.9f
            }
            val barHeight = barMaxHeightPx * fraction
            val barLeft = barsLeft + i * (barWidthPx + barGapPx)
            barRect.set(barLeft, barCenterY - barHeight / 2f, barLeft + barWidthPx, barCenterY + barHeight / 2f)
            canvas.drawRoundRect(barRect, barWidthPx / 2f, barWidthPx / 2f, barPaint)
        }
    }

    private fun drawExpandedContent(
        canvas: Canvas,
        rect: RectF,
        alpha: Int,
        title: String,
        subtitle: String?,
        icon: Bitmap?,
        elapsedSeconds: Float
    ) {
        val artLeft = rect.left + contentPaddingPx
        val hasIcon = icon != null
        if (icon != null) {
            bitmapPaint.alpha = alpha
            val artTop = rect.top + (rect.height() - artSizePx) / 2f
            bitmapDst.set(artLeft, artTop, artLeft + artSizePx, artTop + artSizePx)
            canvas.drawBitmap(icon, null, bitmapDst, bitmapPaint)
        }

        val textLeft = if (hasIcon) artLeft + artSizePx + dp(12f) else rect.left + contentPaddingPx
        val textColumnWidth = rect.right - contentPaddingPx - textLeft
        titlePaint.alpha = alpha
        subtitlePaint.alpha = (alpha * 200 / 255)

        val centerY = rect.top + rect.height() / 2f
        if (subtitle != null) {
            drawMarqueeLine(canvas, titlePaint, title, textLeft, centerY - dp(4f), textColumnWidth, elapsedSeconds)
            drawMarqueeLine(canvas, subtitlePaint, subtitle, textLeft, centerY + dp(16f), textColumnWidth, elapsedSeconds)
        } else {
            val textY = centerY - (titlePaint.descent() + titlePaint.ascent()) / 2f
            drawMarqueeLine(canvas, titlePaint, title, textLeft, textY, textColumnWidth, elapsedSeconds)
        }
    }

    /** Play/pause + prev/next. Geometry here must match PillTouchView's hit-testing exactly. */
    private fun drawTransportControls(canvas: Canvas, alpha: Int) {
        val cx = width / 2f
        val cy = expandedHeightPx - controlButtonBottomMarginPx - controlButtonRadiusPx
        val prevX = cx - controlButtonSpacingPx
        val nextX = cx + controlButtonSpacingPx

        buttonBgPaint.alpha = (alpha * 0.14f).toInt()
        canvas.drawCircle(prevX, cy, controlButtonRadiusPx, buttonBgPaint)
        canvas.drawCircle(cx, cy, controlButtonRadiusPx, buttonBgPaint)
        canvas.drawCircle(nextX, cy, controlButtonRadiusPx, buttonBgPaint)

        iconPaint.alpha = alpha
        drawSkipIcon(canvas, prevX, cy, pointingRight = false)
        if (contentIsPlaying) drawPauseIcon(canvas, cx, cy) else drawPlayIcon(canvas, cx, cy)
        drawSkipIcon(canvas, nextX, cy, pointingRight = true)
    }

    private fun drawPlayIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = dp(7f)
        iconPath.reset()
        iconPath.moveTo(cx - s * 0.6f, cy - s)
        iconPath.lineTo(cx - s * 0.6f, cy + s)
        iconPath.lineTo(cx + s, cy)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float) {
        val barW = dp(3.5f)
        val barH = dp(14f)
        val gap = dp(4f)
        canvas.drawRoundRect(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f, barW / 2f, barW / 2f, iconPaint)
        canvas.drawRoundRect(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f, barW / 2f, barW / 2f, iconPaint)
    }

    private fun drawSkipIcon(canvas: Canvas, cx: Float, cy: Float, pointingRight: Boolean) {
        val s = dp(6f)
        val dir = if (pointingRight) 1f else -1f
        iconPath.reset()
        iconPath.moveTo(cx - dir * s * 0.8f, cy - s)
        iconPath.lineTo(cx - dir * s * 0.8f, cy + s)
        iconPath.lineTo(cx + dir * s * 0.4f, cy)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)

        val barW = dp(2.5f)
        val barX = cx + dir * s * 0.7f
        canvas.drawRoundRect(barX - barW / 2f, cy - s, barX + barW / 2f, cy + s, barW / 2f, barW / 2f, iconPaint)
    }

    private fun drawMarqueeLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        elapsedSeconds: Float
    ) {
        val textWidth = paint.measureText(text)
        if (textWidth <= maxWidth) {
            canvas.drawText(text, x, y, paint)
            return
        }
        canvas.save()
        canvas.clipRect(x, y - paint.textSize * 1.2f, x + maxWidth, y + paint.textSize * 0.6f)
        val scrollRangePx = textWidth + marqueeGapPx
        val offset = (elapsedSeconds * marqueeSpeedPxPerSec) % scrollRangePx
        canvas.drawText(text, x - offset, y, paint)
        canvas.drawText(text, x - offset + scrollRangePx, y, paint)
        canvas.restore()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
