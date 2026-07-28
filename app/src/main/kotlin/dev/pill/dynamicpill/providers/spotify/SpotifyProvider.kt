package dev.pill.dynamicpill.providers.spotify

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import dev.pill.dynamicpill.core.event.EventProvider
import dev.pill.dynamicpill.core.model.EventType
import dev.pill.dynamicpill.core.model.PillEvent

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
 * Scrubber extrapolation (rule 10) isn't wired yet — no transport UI exists
 * to show a scrub position into.
 */
class SpotifyProvider(
    private val context: Context,
    private val notificationListenerComponent: ComponentName
) : EventProvider {

    private companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val PRIORITY = 5
        private const val ART_SIZE_PX = 72
    }

    override val id = "spotify"
    override var currentEvent: PillEvent? = null
        private set

    /** Fetched once, never changes — the app icon shown in Compact/PS. */
    var appIcon: Bitmap? = null
        private set

    private var listener: (() -> Unit)? = null
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var activeController: MediaController? = null

    // Downscaled-art cache, keyed by track identity — rule 9: never hold
    // the full-size bitmap Spotify hands us past the synchronous downscale
    // below, and never re-downscale on every callback for the same track.
    private var cachedArtKey: String? = null
    private var cachedArt: Bitmap? = null

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

    private fun attachTo(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback)
        update()
    }

    private fun update() {
        val controller = activeController
        val playbackState = controller?.playbackState?.state
        val metadata = controller?.metadata
        val isActive = playbackState == PlaybackState.STATE_PLAYING || playbackState == PlaybackState.STATE_PAUSED
        currentEvent = if (controller != null && isActive && metadata != null) {
            PillEvent(
                providerId = id,
                type = EventType.MEDIA,
                priority = PRIORITY,
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                subtitle = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                icon = artFor(metadata),
                isPlaying = playbackState == PlaybackState.STATE_PLAYING,
                onPlayPause = ::togglePlayPause,
                onSkipPrevious = ::skipPrevious,
                onSkipNext = ::skipNext
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
            Bitmap.createScaledBitmap(it, ART_SIZE_PX, ART_SIZE_PX, true)
        }
        cachedArtKey = key
        cachedArt = downscaled
        return downscaled
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
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        return Bitmap.createScaledBitmap(bitmap, ART_SIZE_PX, ART_SIZE_PX, true)
    }
}
