package dev.pill.dynamicpill.overlay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.pill.dynamicpill.core.device.DeviceProfile
import dev.pill.dynamicpill.core.device.Pixel8ProProfile

/**
 * Hosts the pill windows as an AccessibilityService rather than a plain
 * SYSTEM_ALERT_WINDOW-backed Service. This is load-bearing, not incidental:
 * TYPE_APPLICATION_OVERLAY windows sit below system windows like the status
 * bar and never receive touches there (confirmed on-device — zero touch
 * events reached a window positioned over the cutout). TYPE_ACCESSIBILITY_OVERLAY
 * windows added by a bound AccessibilityService are trusted and exempt from
 * that restriction, which is what lets the pill sit on the cutout and still
 * be fully tappable (same technique used by dynamicSpot / Dynamic Island Pro).
 */
class PillAccessibilityService : AccessibilityService() {

    private companion object {
        private const val NUDGE_DOWN_DP = 6f
    }

    private lateinit var windowManager: WindowManager
    private var renderView: PillView? = null
    private var touchView: PillTouchView? = null
    private val deviceProfile: DeviceProfile = Pixel8ProProfile

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                renderView?.freezeAnimations()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addPillViews()
        registerReceiver(
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 3 wires real events (notifications/media) through here.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiver(screenStateReceiver)
        renderView?.let { windowManager.removeView(it) }
        touchView?.let { windowManager.removeView(it) }
        renderView = null
        touchView = null
        return super.onUnbind(intent)
    }

    private fun addPillViews() {
        if (renderView != null) return

        val idleWidthPx = dp(PillView.IDLE_WIDTH_DP).toInt()
        val idleHeightPx = dp(PillView.IDLE_HEIGHT_DP).toInt()
        val expandedWidthPx = dp(PillView.EXPANDED_WIDTH_DP).toInt()
        val expandedHeightPx = dp(PillView.EXPANDED_HEIGHT_DP).toInt()
        val (windowTopPx, centerXOffsetPx) = resolveCutoutOffsets()

        val render = PillView(this)
        val renderParams = WindowManager.LayoutParams(
            expandedWidthPx,
            expandedHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = centerXOffsetPx
            y = windowTopPx
        }
        windowManager.addView(render, renderParams)
        renderView = render

        val touchParams = WindowManager.LayoutParams(
            idleWidthPx,
            idleHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = centerXOffsetPx
            // Both windows top-align — the renderer anchors content to its own
            // top and grows downward (see PillView.onDraw).
            y = windowTopPx
        }
        val touch = PillTouchView(
            this,
            windowManager,
            touchParams,
            idleWidthPx,
            idleHeightPx,
            expandedWidthPx,
            expandedHeightPx,
            windowTopPx
        ) { state, animate -> render.applyState(state, animate) }
        windowManager.addView(touch, touchParams)
        touchView = touch
    }

    /**
     * Reads the real cutout from the system if available; falls back to
     * [deviceProfile]'s fixed values otherwise (CLAUDE.md rule 7 — fallback
     * geometry lives behind DeviceProfile, not hardcoded here).
     * Returns (topOffsetPx, centerXOffsetPx-from-screen-center).
     */
    private fun resolveCutoutOffsets(): Pair<Int, Int> {
        val metrics = windowManager.currentWindowMetrics
        val cutoutRect = metrics.windowInsets.displayCutout?.boundingRects?.firstOrNull()
        val screenCenterX = metrics.bounds.width() / 2

        val result = if (cutoutRect != null) {
            (cutoutRect.top + dp(NUDGE_DOWN_DP).toInt()) to (cutoutRect.centerX() - screenCenterX)
        } else {
            val topOffsetPx = dp(deviceProfile.fallbackTopOffsetDp).toInt()
            val xOffsetPx =
                ((deviceProfile.fallbackCenterXFraction - 0.5f) * metrics.bounds.width()).toInt()
            topOffsetPx to xOffsetPx
        }

        Log.i(
            "PillAccessibilityService",
            "cutout=${cutoutRect} resolved topOffsetPx=${result.first} centerXOffsetPx=${result.second}"
        )
        return result
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
