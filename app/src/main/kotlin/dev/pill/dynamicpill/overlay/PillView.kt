package dev.pill.dynamicpill.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.pill.dynamicpill.core.model.PillEvent
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
 * Expanded draws as **two cards**: the main card (which is what the pill
 * morphs into) and a detached scrubber card floating below it. The scrubber
 * card takes no part in the size morph — it fades and slides out from behind
 * the main card as [progress] rises, which is what keeps the main card short
 * enough that its contents fit on one row.
 *
 * Content is set via [setContent] and cross-fades between the two looks purely
 * as a function of [progress] — no separate Compact/Expanded draw-mode flag
 * is needed: at progress=0 with content present, the source icon +
 * equalizer bars show (Compact/PS); as progress rises toward 1, that fades
 * out and the two expanded cards fade in. No content set at all
 * (HIDDEN/IDLE) draws nothing.
 */
class PillView(context: Context) : View(context) {

    companion object {
        const val IDLE_WIDTH_DP = 130f
        const val IDLE_HEIGHT_DP = 30f
        const val EXPANDED_WIDTH_DP = 380f

        /**
         * The main card's height — what the pill shape morphs into. Distinct
         * from [EXPANDED_HEIGHT_DP], which is the whole *window* and has to
         * also cover the detached scrubber card below.
         */
        private const val EXPANDED_CARD_HEIGHT_DP = 140f
        private const val SCRUBBER_CARD_HEIGHT_DP = 52f
        private const val CARD_GAP_DP = 8f

        /** Total window height: main card + gap + scrubber card. */
        const val EXPANDED_HEIGHT_DP =
            EXPANDED_CARD_HEIGHT_DP + CARD_GAP_DP + SCRUBBER_CARD_HEIGHT_DP

        private const val EXPANDED_CORNER_RADIUS_DP = 28f
        private const val SCRUBBER_CARD_CORNER_RADIUS_DP = 22f
        // Collapsed (presence=0) width — smaller than idle height, so the
        // resting circle over the cutout reads as a small dot, not a disc as
        // wide as the pill is tall.
        private const val CIRCLE_WIDTH_DP = 30f

        private const val CONTENT_PADDING_DP = 16f

        /**
         * Half-width of the region reserved for the physical punch-hole at
         * the card's top centre. The header row is split around it — nothing
         * is drawn between `centre ± this`. Same reasoning as Compact keeping
         * its middle clear between the logo and the bars.
         */
        private const val CUTOUT_HALF_WIDTH_DP = 26f

        /** Header row ("Spotify" · album) baseline, level with the cutout. */
        private const val HEADER_BASELINE_DP = 21f
        private const val HEADER_ICON_SIZE_DP = 13f

        /** Vertical centre of the art / text / buttons row, below the cutout. */
        private const val CONTENT_ROW_CENTER_DP = 95f

        private const val ART_SIZE_DP = 56f
        private const val BADGE_SIZE_DP = 17f
        private const val TEXT_GAP_DP = 12f

        /**
         * The accent-tinted blob filling the card's lower portion, with a
         * wavy top edge that scrolls sideways (see `references/img21.jpeg`).
         * Content draws on top of it.
         */
        private const val BLOB_TOP_DP = 74f
        private const val BLOB_AMPLITUDE_DP = 5f
        private const val BLOB_WAVE_LENGTH_DP = 130f
        private const val BLOB_SPEED_DP_PER_SEC = 11f
        private const val BLOB_ALPHA = 68

        private const val TRACK_HEIGHT_DP = 3f
        private const val WAVE_AMPLITUDE_DP = 3.5f
        private const val WAVE_LENGTH_DP = 18f
        private const val WAVE_SPEED_DP_PER_SEC = 14f
        private const val WAVE_SAMPLE_STEP_DP = 1.5f
        private const val SCRUBBER_DOT_RADIUS_DP = 4f
        private const val TIME_LABEL_GAP_DP = 10f

        private const val BAR_COUNT = 3
        private const val BAR_WIDTH_DP = 3f
        private const val BAR_GAP_DP = 2f
        private const val BAR_MAX_HEIGHT_DP = 14f
        private const val MARQUEE_SPEED_DP_PER_SEC = 30f
        private const val MARQUEE_GAP_DP = 24f

        private const val CONTROL_BUTTON_DIAMETER_DP = 38f
        private const val CONTROL_BUTTON_SPACING_DP = 44f
        private const val CONTROL_BUTTON_RIGHT_MARGIN_DP = 18f

        /**
         * Transport-control geometry, shared with PillTouchView's hit-testing.
         * Kept as functions rather than raw dp constants so drawing and tap
         * detection consume the *same* layout maths — previously each derived
         * the button centres itself from the constants, which is exactly the
         * kind of duplication that silently drifts when the layout moves.
         *
         * [index] is 0=previous, 1=play/pause, 2=next.
         */
        fun controlCenterX(index: Int, widthPx: Float, density: Float): Float {
            val rightMargin = CONTROL_BUTTON_RIGHT_MARGIN_DP * density
            val spacing = CONTROL_BUTTON_SPACING_DP * density
            val radius = CONTROL_BUTTON_DIAMETER_DP * density / 2f
            val nextCx = widthPx - rightMargin - radius
            return nextCx - (2 - index) * spacing
        }

        fun controlCenterY(density: Float): Float = CONTENT_ROW_CENTER_DP * density

        fun controlRadiusPx(density: Float): Float = CONTROL_BUTTON_DIAMETER_DP * density / 2f

        const val CONTROL_COUNT = 3
    }

    private val density = resources.displayMetrics.density

    private val idleWidthPx = dp(IDLE_WIDTH_DP)
    private val idleHeightPx = dp(IDLE_HEIGHT_DP)
    private val expandedWidthPx = dp(EXPANDED_WIDTH_DP)
    private val expandedCardHeightPx = dp(EXPANDED_CARD_HEIGHT_DP)
    private val scrubberCardHeightPx = dp(SCRUBBER_CARD_HEIGHT_DP)
    private val cardGapPx = dp(CARD_GAP_DP)
    private val expandedHeightPx = dp(EXPANDED_HEIGHT_DP)
    private val expandedCornerRadiusPx = dp(EXPANDED_CORNER_RADIUS_DP)
    private val scrubberCardCornerRadiusPx = dp(SCRUBBER_CARD_CORNER_RADIUS_DP)
    private val circleWidthPx = dp(CIRCLE_WIDTH_DP)

    private val contentPaddingPx = dp(CONTENT_PADDING_DP)
    private val cutoutHalfWidthPx = dp(CUTOUT_HALF_WIDTH_DP)
    private val headerBaselinePx = dp(HEADER_BASELINE_DP)
    private val headerIconSizePx = dp(HEADER_ICON_SIZE_DP)
    private val contentRowCenterPx = dp(CONTENT_ROW_CENTER_DP)
    private val artSizePx = dp(ART_SIZE_DP)
    private val badgeSizePx = dp(BADGE_SIZE_DP)
    private val textGapPx = dp(TEXT_GAP_DP)

    private val blobTopPx = dp(BLOB_TOP_DP)
    private val blobAmplitudePx = dp(BLOB_AMPLITUDE_DP)
    private val blobWaveLengthPx = dp(BLOB_WAVE_LENGTH_DP)
    private val blobSpeedPxPerSec = dp(BLOB_SPEED_DP_PER_SEC)

    private val trackHeightPx = dp(TRACK_HEIGHT_DP)
    private val waveAmplitudePx = dp(WAVE_AMPLITUDE_DP)
    private val waveLengthPx = dp(WAVE_LENGTH_DP)
    private val waveSpeedPxPerSec = dp(WAVE_SPEED_DP_PER_SEC)
    private val waveSampleStepPx = dp(WAVE_SAMPLE_STEP_DP)
    private val scrubberDotRadiusPx = dp(SCRUBBER_DOT_RADIUS_DP)
    private val timeLabelGapPx = dp(TIME_LABEL_GAP_DP)

    private val barWidthPx = dp(BAR_WIDTH_DP)
    private val barGapPx = dp(BAR_GAP_DP)
    private val barMaxHeightPx = dp(BAR_MAX_HEIGHT_DP)
    private val marqueeSpeedPxPerSec = dp(MARQUEE_SPEED_DP_PER_SEC)
    private val marqueeGapPx = dp(MARQUEE_GAP_DP)
    private val controlButtonRadiusPx = controlRadiusPx(density)

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
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(10f)
        alpha = 180
    }
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 60
        style = Paint.Style.STROKE
        strokeWidth = trackHeightPx
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = trackHeightPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        alpha = 170
    }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPath = Path()
    // Reused every frame via rewind() — never allocate a Path inside onDraw.
    private val wavePath = Path()
    private val blobPath = Path()
    private val clipPath = Path()
    private val rect = RectF()
    private val scrubberRect = RectF()
    private val bitmapDst = RectF()
    private val barRect = RectF()

    private var progress = 0f
    private var presence = 1f
    private var event: PillEvent? = null
    private var sourceIcon: Bitmap? = null
    private var sourceLabel: String? = null

    // Continuous, screen-on-only visual flourish (equalizer bars / marquee /
    // scrubber advance), not an event source — distinct from the rule-1
    // "never poll" ban on polling business state. Self-schedules via
    // postOnAnimation, not a Handler.postDelayed timer.
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
        applyState(PillState.HIDDEN, animate = false)
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

    /** Source app icon shown in Compact/PS and as the ES badge (e.g. Spotify's icon) — set once, rarely changes. */
    fun setSourceIcon(icon: Bitmap?) {
        sourceIcon = icon
        invalidate()
    }

    /**
     * Source app's display name for the ES header (e.g. "Spotify"). Like
     * [setSourceIcon] this is per-provider and effectively constant, so it
     * doesn't travel on the per-event [PillEvent].
     */
    fun setSourceLabel(label: String?) {
        sourceLabel = label
        invalidate()
    }

    /** Real event content. Pass null to clear back to no-content (nothing drawn). */
    fun setContent(event: PillEvent?) {
        this.event = event
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
        val e = event ?: return false
        val barsAnimating = e.isPlaying && progress < 0.98f
        val scrubberAdvancing = e.isPlaying && progress > 0.02f
        return barsAnimating || scrubberAdvancing ||
            textOverflows(e.title, titlePaint) || textOverflows(e.subtitle, subtitlePaint)
    }

    private fun textColumnWidthPx(): Float {
        val textLeft = contentPaddingPx + artSizePx + textGapPx
        val controlsLeft = controlCenterX(0, expandedWidthPx, density) - controlButtonRadiusPx
        return controlsLeft - textGapPx - textLeft
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
        val fullHeight = idleHeightPx + (expandedCardHeightPx - idleHeightPx) * progress
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
        paint.alpha = 255
        // Idle is a full capsule (radius = half height); Expanded is a flatter
        // rounded-rect look, not a stadium shape. At progress=0 this equals
        // h/2, which combined with w==h at presence=0 draws a perfect circle.
        val idleRadius = idleHeightPx / 2f
        val cornerRadius = idleRadius + (expandedCornerRadiusPx - idleRadius) * progress
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        val e = event ?: return
        val compactAlpha = ((1f - progress) * presence * 255f).toInt().coerceIn(0, 255)
        val expandedAlpha = (progress * presence * 255f).toInt().coerceIn(0, 255)
        val elapsedSeconds = (System.nanoTime() - animStartNanos) / 1_000_000_000f

        if (compactAlpha > 0) drawCompactContent(canvas, rect, e, compactAlpha, elapsedSeconds)
        if (expandedAlpha > 0) {
            drawExpandedContent(canvas, rect, e, expandedAlpha, elapsedSeconds)
            drawScrubberCard(canvas, e, expandedAlpha, elapsedSeconds)
        }
    }

    /**
     * Compact: `[source logo | camera cutout | bars]`. The middle is left
     * deliberately empty — the physical punch-hole sits there, so anything
     * drawn in it would be obscured.
     */
    private fun drawCompactContent(canvas: Canvas, rect: RectF, e: PillEvent, alpha: Int, elapsedSeconds: Float) {
        val icon = sourceIcon
        if (icon != null) {
            bitmapPaint.alpha = alpha
            val iconSize = dp(18f)
            val iconTop = rect.top + (rect.height() - iconSize) / 2f
            val iconLeft = rect.left + dp(10f)
            bitmapDst.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            canvas.drawBitmap(icon, null, bitmapDst, bitmapPaint)
        }

        barPaint.alpha = alpha
        val barsTotalWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * barGapPx
        val barsLeft = rect.right - dp(10f) - barsTotalWidth
        val barCenterY = rect.top + rect.height() / 2f
        for (i in 0 until BAR_COUNT) {
            // Paused: literal "||" pause glyph — skip the middle bar, draw
            // the two outer bars at equal height. Playing: animated.
            if (!e.isPlaying && i == 1) continue
            val fraction = if (e.isPlaying) {
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

    /**
     * Main card: `[art + badge] [title / artist] [prev · play/pause · next]`
     * on one row, starting below [CUTOUT_CLEARANCE_DP] so nothing hides
     * behind the camera.
     */
    private fun drawExpandedContent(canvas: Canvas, rect: RectF, e: PillEvent, alpha: Int, elapsedSeconds: Float) {
        drawAccentBlob(canvas, rect, e, alpha, elapsedSeconds)
        drawHeaderRow(canvas, rect, e, alpha)

        val rowCenterY = rect.top + contentRowCenterPx

        val artLeft = rect.left + contentPaddingPx
        val artTop = rowCenterY - artSizePx / 2f
        val hasArt = e.icon != null
        if (e.icon != null) {
            bitmapPaint.alpha = alpha
            bitmapDst.set(artLeft, artTop, artLeft + artSizePx, artTop + artSizePx)
            canvas.drawBitmap(e.icon, null, bitmapDst, bitmapPaint)

            // Provider badge rides the art's bottom-right corner, so the
            // source stays identifiable without spending a separate slot.
            val badge = sourceIcon
            if (badge != null) {
                val badgeLeft = artLeft + artSizePx - badgeSizePx * 0.75f
                val badgeTop = artTop + artSizePx - badgeSizePx * 0.75f
                bitmapDst.set(badgeLeft, badgeTop, badgeLeft + badgeSizePx, badgeTop + badgeSizePx)
                canvas.drawBitmap(badge, null, bitmapDst, bitmapPaint)
            }
        }

        val textLeft = if (hasArt) artLeft + artSizePx + textGapPx else artLeft
        val controlsLeft = controlCenterX(0, expandedWidthPx, density) - controlButtonRadiusPx
        val textColumnWidth = controlsLeft - textGapPx - textLeft

        // Accent tints the title only (the card itself stays black) — a full
        // background wash fought with the text for contrast.
        titlePaint.color = e.accentColor ?: Color.WHITE
        titlePaint.alpha = alpha
        subtitlePaint.color = Color.WHITE
        subtitlePaint.alpha = (alpha * 200 / 255)

        val subtitle = e.subtitle
        if (subtitle != null) {
            drawMarqueeLine(canvas, titlePaint, e.title, textLeft, rowCenterY - dp(3f), textColumnWidth, elapsedSeconds)
            drawMarqueeLine(canvas, subtitlePaint, subtitle, textLeft, rowCenterY + dp(15f), textColumnWidth, elapsedSeconds)
        } else {
            drawMarqueeLine(canvas, titlePaint, e.title, textLeft, rowCenterY + dp(5f), textColumnWidth, elapsedSeconds)
        }

        drawTransportControls(canvas, rect, e, alpha)
    }

    /**
     * The accent-tinted blob filling the card's lower portion, with a wavy
     * top edge that scrolls sideways — `references/img21.jpeg`'s treatment.
     * Drawn first so all content sits on top of it.
     *
     * Clipped to the card's rounded rect so the fill can't spill past the
     * corners, and drawn at a low alpha so title/artist keep their contrast
     * against it (the reason the earlier full-card gradient was dropped).
     */
    private fun drawAccentBlob(canvas: Canvas, rect: RectF, e: PillEvent, alpha: Int, elapsedSeconds: Float) {
        val accent = e.accentColor ?: return

        blobPath.rewind()
        val top = rect.top + blobTopPx
        val phase = if (e.isPlaying) elapsedSeconds * blobSpeedPxPerSec else 0f
        blobPath.moveTo(rect.left, rect.bottom)
        blobPath.lineTo(rect.left, top)
        var x = rect.left
        while (x < rect.right) {
            x = (x + waveSampleStepPx).coerceAtMost(rect.right)
            val angle = (x + phase) / blobWaveLengthPx * 2f * Math.PI.toFloat()
            blobPath.lineTo(x, top + sin(angle) * blobAmplitudePx)
        }
        blobPath.lineTo(rect.right, rect.bottom)
        blobPath.close()

        val idleRadius = idleHeightPx / 2f
        val cornerRadius = idleRadius + (expandedCornerRadiusPx - idleRadius) * progress
        clipPath.rewind()
        clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        blobPaint.color = accent
        blobPaint.alpha = BLOB_ALPHA * alpha / 255
        canvas.drawPath(blobPath, blobPaint)
        canvas.restore()
    }

    /**
     * Header line across the card's top, **split around the punch-hole**:
     * source app on the left, album on the right, nothing drawn within
     * [CUTOUT_HALF_WIDTH_DP] of centre. This uses space that would otherwise
     * be dead, since the camera already forbids content there.
     */
    private fun drawHeaderRow(canvas: Canvas, rect: RectF, e: PillEvent, alpha: Int) {
        headerPaint.alpha = 170 * alpha / 255
        val baselineY = rect.top + headerBaselinePx
        val centerX = rect.centerX()

        var leftCursor = rect.left + contentPaddingPx
        val icon = sourceIcon
        if (icon != null) {
            bitmapPaint.alpha = alpha
            val iconTop = baselineY - headerIconSizePx + dp(2f)
            bitmapDst.set(leftCursor, iconTop, leftCursor + headerIconSizePx, iconTop + headerIconSizePx)
            canvas.drawBitmap(icon, null, bitmapDst, bitmapPaint)
            leftCursor += headerIconSizePx + dp(6f)
        }
        val label = sourceLabel
        if (label != null) {
            headerPaint.textAlign = Paint.Align.LEFT
            val room = centerX - cutoutHalfWidthPx - leftCursor
            if (room > 0) {
                canvas.drawText(ellipsize(label, headerPaint, room), leftCursor, baselineY, headerPaint)
            }
        }

        val context = e.contextLabel
        if (context != null) {
            headerPaint.textAlign = Paint.Align.RIGHT
            val right = rect.right - contentPaddingPx
            val room = right - (centerX + cutoutHalfWidthPx)
            if (room > 0) {
                canvas.drawText(ellipsize(context, headerPaint, room), right, baselineY, headerPaint)
            }
        }
    }

    /** Header lines are short and static — truncated rather than marqueed, unlike title/artist. */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val room = maxWidth - paint.measureText(ellipsis)
        if (room <= 0f) return ellipsis
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) > room) end--
        return text.substring(0, end).trimEnd() + ellipsis
    }

    /**
     * The detached second card. It doesn't participate in the size morph —
     * it fades in and slides down from behind the main card as [progress]
     * rises, which is what lets the main card stay one row tall.
     */
    private fun drawScrubberCard(canvas: Canvas, e: PillEvent, alpha: Int, elapsedSeconds: Float) {
        if (e.durationMs <= 0) return

        val settledTop = expandedCardHeightPx + cardGapPx
        // Slides out from under the main card rather than appearing in place.
        val cardTop = settledTop - (1f - progress) * cardGapPx * 2f
        val cardLeft = (width - expandedWidthPx) / 2f
        scrubberRect.set(cardLeft, cardTop, cardLeft + expandedWidthPx, cardTop + scrubberCardHeightPx)

        paint.alpha = alpha
        canvas.drawRoundRect(scrubberRect, scrubberCardCornerRadiusPx, scrubberCardCornerRadiusPx, paint)
        paint.alpha = 255

        // Rule 10: extrapolate from the last known position rather than
        // asking PlaybackState again on a timer.
        val positionMs = if (e.isPlaying) {
            val elapsedMs = SystemClock.elapsedRealtime() - e.positionUpdateTimeMs
            (e.positionMs + (elapsedMs * e.playbackSpeed).toLong()).coerceIn(0L, e.durationMs)
        } else {
            e.positionMs.coerceIn(0L, e.durationMs)
        }
        val fraction = (positionMs.toFloat() / e.durationMs.toFloat()).coerceIn(0f, 1f)

        timePaint.alpha = (180 * alpha / 255)
        val centerY = scrubberRect.centerY()
        val textBaselineY = centerY + timePaint.textSize / 3f
        timePaint.textAlign = Paint.Align.LEFT
        val elapsedLabel = formatMs(positionMs)
        canvas.drawText(elapsedLabel, scrubberRect.left + contentPaddingPx, textBaselineY, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "-${formatMs(e.durationMs - positionMs)}",
            scrubberRect.right - contentPaddingPx,
            textBaselineY,
            timePaint
        )

        val trackLeft = scrubberRect.left + contentPaddingPx +
            timePaint.measureText(elapsedLabel) + timeLabelGapPx
        val trackRight = scrubberRect.right - contentPaddingPx -
            timePaint.measureText("-00:00") - timeLabelGapPx
        if (trackRight <= trackLeft) return

        val splitX = trackLeft + (trackRight - trackLeft) * fraction
        val accent = e.accentColor ?: Color.WHITE

        // Remaining portion: a plain flat line.
        trackBgPaint.alpha = (60 * alpha / 255)
        canvas.drawLine(splitX, centerY, trackRight, centerY, trackBgPaint)

        // Played portion: a sine wave, phase-scrolling while playing and
        // frozen when paused (so pausing visibly stills the whole card).
        wavePaint.color = accent
        wavePaint.alpha = alpha
        buildWavePath(trackLeft, splitX, centerY, elapsedSeconds, e.isPlaying)
        canvas.drawPath(wavePath, wavePaint)

        dotPaint.color = accent
        dotPaint.alpha = alpha
        canvas.drawCircle(splitX, centerY, scrubberDotRadiusPx, dotPaint)
    }

    private fun buildWavePath(
        startX: Float,
        endX: Float,
        centerY: Float,
        elapsedSeconds: Float,
        playing: Boolean
    ) {
        wavePath.rewind()
        if (endX <= startX) return
        val phase = if (playing) elapsedSeconds * waveSpeedPxPerSec else 0f
        var x = startX
        wavePath.moveTo(x, centerY)
        while (x < endX) {
            x = (x + waveSampleStepPx).coerceAtMost(endX)
            val angle = (x + phase) / waveLengthPx * 2f * Math.PI.toFloat()
            wavePath.lineTo(x, centerY + sin(angle) * waveAmplitudePx)
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /** Play/pause + prev/next. Geometry comes from the shared companion helpers, same as PillTouchView's hit-testing. */
    private fun drawTransportControls(canvas: Canvas, rect: RectF, e: PillEvent, alpha: Int) {
        val cy = rect.top + controlCenterY(density)

        buttonBgPaint.alpha = (alpha * 0.14f).toInt()
        for (i in 0 until CONTROL_COUNT) {
            canvas.drawCircle(controlCenterX(i, expandedWidthPx, density), cy, controlButtonRadiusPx, buttonBgPaint)
        }

        iconPaint.alpha = alpha
        drawSkipIcon(canvas, controlCenterX(0, expandedWidthPx, density), cy, pointingRight = false)
        val playCx = controlCenterX(1, expandedWidthPx, density)
        if (e.isPlaying) drawPauseIcon(canvas, playCx, cy) else drawPlayIcon(canvas, playCx, cy)
        drawSkipIcon(canvas, controlCenterX(2, expandedWidthPx, density), cy, pointingRight = true)
    }

    private fun drawPlayIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = dp(6f)
        iconPath.reset()
        iconPath.moveTo(cx - s * 0.6f, cy - s)
        iconPath.lineTo(cx - s * 0.6f, cy + s)
        iconPath.lineTo(cx + s, cy)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float) {
        val barW = dp(3f)
        val barH = dp(12f)
        val gap = dp(3.5f)
        canvas.drawRoundRect(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f, barW / 2f, barW / 2f, iconPaint)
        canvas.drawRoundRect(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f, barW / 2f, barW / 2f, iconPaint)
    }

    private fun drawSkipIcon(canvas: Canvas, cx: Float, cy: Float, pointingRight: Boolean) {
        val s = dp(5.5f)
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
