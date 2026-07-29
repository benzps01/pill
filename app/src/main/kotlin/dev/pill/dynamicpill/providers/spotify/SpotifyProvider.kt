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
 * Whether a session means "there's something worth showing".
 *
 * [hasEverPlayed] is what separates the two cases that a raw state code
 * can't tell apart, both observed on-device:
 *
 *  - **Resurrected sessions.** Clearing Spotify from recents destroys its
 *    session, and then Spotify relaunches itself in the background and
 *    registers a *fresh* session sitting in PAUSED a second or two later.
 *    Treating that as live made the pill reappear on its own after the user
 *    had explicitly swiped the app away. A session that has never played
 *    isn't a pause the user made — it's an app restarting itself.
 *  - **Transient blips.** Spotify passes through BUFFERING/CONNECTING on
 *    every track change. Treating those as inactive dropped the event for
 *    ~40ms, blinking the pill out and back — and slamming shut an open
 *    expanded card. Once a session has played, ride those out instead.
 *
 * Pulled out as its own named function taking plain values rather than a
 * MediaController/PlaybackState, so it's independently unit-testable and the
 * "is this session really live?" rule has exactly one place it's defined
 * instead of being an inline expression buried in [SpotifyProvider.update].
 */
internal fun isSpotifyPlaybackActive(playbackStateCode: Int?, hasEverPlayed: Boolean): Boolean =
    when (playbackStateCode) {
        PlaybackState.STATE_PLAYING -> true
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING -> hasEverPlayed
        else -> false
    }

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

    // Fetched once in start(), never change — travel out on every PillEvent
    // as sourceIcon/sourceLabel rather than being read off this class, so the
    // renderer stays provider-agnostic.
    private var appIcon: Bitmap? = null
    private var appLabel: String? = null

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

    /**
     * Reset per session (see [attachTo]) — a *new* session that has never
     * reached PLAYING is treated as a resurrection rather than a pause. See
     * [isSpotifyPlaybackActive].
     */
    private var hasEverPlayed = false

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

    override fun start() {
        appIcon = loadAppIcon()
        appLabel = loadAppLabel()
        mediaSessionManager.addOnActiveSessionsChangedListener(
            sessionsChangedListener,
            notificationListenerComponent
        )
        val initial = mediaSessionManager.getActiveSessions(notificationListenerComponent)
        attachTo(initial.firstOrNull { it.packageName == SPOTIFY_PACKAGE })
    }

    override fun stop() {
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
        // A different session is a different playback; whatever the old one
        // had done tells us nothing about this one.
        hasEverPlayed = false
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
        if (stateCode == PlaybackState.STATE_PLAYING) hasEverPlayed = true
        when (stateCode) {
            PlaybackState.STATE_PLAYING -> {
                cancelExpiry()
                pausedExpired = false
            }
            // Only a session that actually played can go stale from pausing —
            // one that never played isn't showing in the first place.
            PlaybackState.STATE_PAUSED -> if (hasEverPlayed) scheduleExpiry()
            else -> cancelExpiry()
        }
        val isActive = isSpotifyPlaybackActive(stateCode, hasEverPlayed) &&
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
                sourceIcon = appIcon,
                sourceLabel = appLabel,
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
