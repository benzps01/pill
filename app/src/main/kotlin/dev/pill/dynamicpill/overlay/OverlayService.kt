package dev.pill.dynamicpill.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import dev.pill.dynamicpill.R

/**
 * Hosts the pill as two WindowManager overlays: a render-only window fixed forever at
 * max-expanded bounds (PillView), and a small invisible hit-target window that tracks
 * the current state (PillTouchView). See PillTouchView's class doc for why touch
 * pass-through needs a second window rather than resizing the render window itself.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var renderView: PillView? = null
    private var touchView: PillTouchView? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                renderView?.freezeAnimations()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "pill_overlay"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        addPillViews()
        registerReceiver(
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenStateReceiver)
        renderView?.let { windowManager.removeView(it) }
        touchView?.let { windowManager.removeView(it) }
        renderView = null
        touchView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addPillViews() {
        if (renderView != null) return

        val idleWidthPx = dp(PillView.IDLE_WIDTH_DP).toInt()
        val idleHeightPx = dp(PillView.IDLE_HEIGHT_DP).toInt()
        val expandedWidthPx = dp(PillView.EXPANDED_WIDTH_DP).toInt()
        val expandedHeightPx = dp(PillView.EXPANDED_HEIGHT_DP).toInt()
        val topOffsetPx = dp(12f).toInt()

        val render = PillView(this)
        val renderParams = WindowManager.LayoutParams(
            expandedWidthPx,
            expandedHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffsetPx
        }
        windowManager.addView(render, renderParams)
        renderView = render

        val touchParams = WindowManager.LayoutParams(
            idleWidthPx,
            idleHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Centered on the same midline the renderer draws around (see PillTouchView).
            y = topOffsetPx + (expandedHeightPx - idleHeightPx) / 2
        }
        val touch = PillTouchView(
            this,
            windowManager,
            touchParams,
            idleWidthPx,
            idleHeightPx,
            expandedWidthPx,
            expandedHeightPx,
            topOffsetPx
        ) { state, animate -> render.applyState(state, animate) }
        windowManager.addView(touch, touchParams)
        touchView = touch
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
