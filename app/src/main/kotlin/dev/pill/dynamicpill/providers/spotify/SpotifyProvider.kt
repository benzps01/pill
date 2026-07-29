package dev.pill.dynamicpill.providers.spotify

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.palette.graphics.Palette
import dev.pill.dynamicpill.core.event.EventProvider
import dev.pill.dynamicpill.core.model.EventType
import dev.pill.dynamicpill.core.model.PillEvent

/**
 * True only for playback states that mean "there's something to show" —
 * playing or paused. Anything else (stopped, none, buffering-with-no-prior-
 * state, or no state at all) means the session isn't actually active,
 * regardless of whether Android still lists it. Pulled out as its own named
 * function, taking a plain Int rather than a MediaController/PlaybackState,
 * so it's independently unit-testable and the "is this session really
 * live?" rule has exactly one place it's defined instead of being an inline
 * expression buried in [SpotifyProvider.update].
 */
internal fun isSpotifyPlaybackActive(playbackStateCode: Int?): Boolean =
    playbackStateCode == PlaybackState.STATE_PLAYING || playbackStateCode == PlaybackState.STATE_PAUSED

/**
 * Wraps the Spotify MediaSession, if one is active. Event-driven throughout
 * (CLAUDE.md rule 1) — [MediaSessionManager.OnActiveSessionsChangedListener]
 * fires when sessions come/go, [MediaController.Callback] fires on
 * playback/metadata changes; nothing here is polled on a timer.
 *
 * Requires Notification Access (the same grant already onboarded for
 * PillNotificationListenerService) — MediaSessionManager.getActiveSessions
 * needs a bound NotificationListenerService component to authorize session
 * access, unrelated to the Spotify notification itself.
 *
 * Scrubber position (rule 10) is exposed as position + speed + update-time
 * on [PillEvent]; the renderer extrapolates the live position from those,
 * this class never polls PlaybackState on a timer.
 */
class SpotifyProvider(
    private val context: Context,
    private val notificationListenerComponent: ComponentName
) : EventProvider {

    private companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val PRIORITY = 5
        private const val DEFAULT_ACCENT_COLOR = 0xFF303030.toInt()
        // A paused session can be either "you tapped pause, still engaged"
        // or "the app was swiped from recents and its session just lingers
        // paused indefinitely" — MediaSession gives no way to tell these
        // apart directly (task-removal doesn't necessarily kill the
        // session; that's by design, so media controls survive backgrounding).
        // A short idle expiry is the practical compromise: paused stays live
        // briefly, then auto-clears if nothing real happens.
        private const val PAUSED_EXPIRY_MS = 2 * 60 * 1000L
    }

    override val id = "spotify"
    override var currentEvent: PillEvent? = null
        private set

    /** Fetched once, never changes — the app icon shown in Compact/PS and the ES badge. */
    var appIcon: Bitmap? = null
        private set

    /** Fetched once — the app's display name, shown in the ES header row. */
    var appLabel: String? = null
        private set

    private val density = context.resources.displayMetrics.density
    private val artSizePx = (64 * density).toInt()
    private val iconSizePx = (32 * density).toInt()

    private var listener: (() -> Unit)? = null
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var activeController: MediaController? = null

    // One-shot deferred expiry for a paused session — not a polling loop
    // (rule 1): it fires once, is cancelled/reset the moment there's real
    // activity (a fresh PLAY), and its firing is itself the single event
    // that clears a gone-stale session, not a recurring state check.
    private val expiryHandler = Handler(Looper.getMainLooper())
    private var expiryRunnable: Runnable? = null
    private var pausedExpired = false

    // Downscaled-art + accent-color cache, keyed by track identity — rule 9:
    // never hold the full-size bitmap Spotify hands us past the synchronous
    // downscale below, and never redo this work on every callback for the
    // same track.
    private var cachedArtKey: String? = null
    private var cachedArt: Bitmap? = null
    private var cachedAccentColor: Int = DEFAULT_ACCENT_COLOR

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() {
            activeController = null
            update()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachTo(controllers?.firstOrNull { it.packageName == SPOTIFY_PACKAGE })
        }

    fun start() {
        appIcon = loadAppIcon()
        appLabel = loadAppLabel()
        mediaSessionManager.addOnActiveSessionsChangedListener(
            sessionsChangedListener,
            notificationListenerComponent
        )
        val initial = mediaSessionManager.getActiveSessions(notificationListenerComponent)
        attachTo(initial.firstOrNull { it.packageName == SPOTIFY_PACKAGE })
    }

    fun stop() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        cancelExpiry()
    }

    override fun setListener(listener: () -> Unit) {
        this.listener = listener
    }

    fun togglePlayPause() {
        val controller = activeController ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun skipNext() {
        activeController?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    /**
     * Brings Spotify to the foreground (GestureAction.OPEN_APP).
     *
     * Prefers the session's own [MediaController.getSessionActivity] — that's
     * the PendingIntent Spotify itself nominated for "take me to what's
     * playing", so it lands on the right screen and, being Spotify's own
     * intent, isn't subject to our background-activity-launch restrictions.
     * Falls back to a plain launcher intent when a session doesn't set one.
     */
    fun openApp() {
        activeController?.sessionActivity?.let { pending ->
            try {
                pending.send()
                return
            } catch (e: PendingIntent.CanceledException) {
                // Stale intent from a dead session — fall through to the launcher.
            }
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return
        context.startActivity(launchIntent)
    }

    private fun attachTo(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback)
        cancelExpiry()
        pausedExpired = false
        update()
    }

    private fun scheduleExpiry() {
        if (expiryRunnable != null) return
        val runnable = Runnable {
            pausedExpired = true
            expiryRunnable = null
            update()
        }
        expiryRunnable = runnable
        expiryHandler.postDelayed(runnable, PAUSED_EXPIRY_MS)
    }

    private fun cancelExpiry() {
        expiryRunnable?.let { expiryHandler.removeCallbacks(it) }
        expiryRunnable = null
    }

    private fun update() {
        val controller = activeController
        val playbackState = controller?.playbackState
        val metadata = controller?.metadata
        val stateCode = playbackState?.state
        when (stateCode) {
            PlaybackState.STATE_PLAYING -> {
                cancelExpiry()
                pausedExpired = false
            }
            PlaybackState.STATE_PAUSED -> scheduleExpiry()
            else -> cancelExpiry()
        }
        val isActive = isSpotifyPlaybackActive(stateCode) &&
            !(stateCode == PlaybackState.STATE_PAUSED && pausedExpired)
        currentEvent = if (controller != null && isActive && metadata != null && playbackState != null) {
            val art = artFor(metadata)
            PillEvent(
                providerId = id,
                type = EventType.MEDIA,
                priority = PRIORITY,
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                subtitle = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                contextLabel = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                icon = art,
                isPlaying = playbackState.state == PlaybackState.STATE_PLAYING,
                onPlayPause = ::togglePlayPause,
                onSkipPrevious = ::skipPrevious,
                onSkipNext = ::skipNext,
                onOpen = ::openApp,
                accentColor = cachedAccentColor,
                positionMs = playbackState.position,
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                playbackSpeed = playbackState.playbackSpeed,
                positionUpdateTimeMs = playbackState.lastPositionUpdateTime
            )
        } else {
            null
        }
        listener?.invoke()
    }

    private fun artFor(metadata: MediaMetadata): Bitmap? {
        val key = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?: "${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}|${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}"
        if (key == cachedArtKey) return cachedArt

        val fullSize = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val downscaled = fullSize?.let {
            Bitmap.createScaledBitmap(it, artSizePx, artSizePx, true)
        }
        cachedArtKey = key
        cachedArt = downscaled
        cachedAccentColor = downscaled?.let {
            Palette.from(it).generate().getDominantColor(DEFAULT_ACCENT_COLOR)
        } ?: DEFAULT_ACCENT_COLOR
        return downscaled
    }

    private fun loadAppLabel(): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(SPOTIFY_PACKAGE, 0)).toString()
    } catch (e: Exception) {
        // Same package-visibility failure mode as loadAppIcon — the header
        // just goes label-less rather than the provider failing.
        null
    }

    private fun loadAppIcon(): Bitmap? {
        val drawable = try {
            context.packageManager.getApplicationIcon(SPOTIFY_PACKAGE)
        } catch (e: Exception) {
            return null
        }
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
            ?: Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            ).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        return Bitmap.createScaledBitmap(bitmap, iconSizePx, iconSizePx, true)
    }
}
