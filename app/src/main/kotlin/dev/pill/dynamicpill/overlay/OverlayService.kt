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
 * Hosts the pill as a WindowManager overlay. The window is sized to the pill's
 * max-expanded bounds and never resized (CLAUDE.md rule 4) — PillView animates its
 * drawn shape and touchable region internally instead.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var pillView: PillView? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                pillView?.freezeAnimations()
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
        addPillView()
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
        pillView?.let { windowManager.removeView(it) }
        pillView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addPillView() {
        if (pillView != null) return

        val view = PillView(this)

        val layoutParams = WindowManager.LayoutParams(
            dp(PillView.EXPANDED_WIDTH_DP).toInt(),
            dp(PillView.EXPANDED_HEIGHT_DP).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(12f).toInt()
        }

        windowManager.addView(view, layoutParams)
        pillView = view
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
