package dev.pill.dynamicpill.providers.spotify

import android.content.ComponentName
import android.content.Context
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
 * Album art (rule 9) and scrubber extrapolation (rule 10) are not wired yet
 * — no Expanded-state UI exists to render them into. This only turns
 * Spotify playback into a title/artist PillEvent.
 */
class SpotifyProvider(
    context: Context,
    private val notificationListenerComponent: ComponentName
) : EventProvider {

    private companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val PRIORITY = 5
    }

    override val id = "spotify"
    override var currentEvent: PillEvent? = null
        private set

    private var listener: (() -> Unit)? = null
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var activeController: MediaController? = null

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

    private fun attachTo(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        controller?.registerCallback(controllerCallback)
        update()
    }

    private fun update() {
        val controller = activeController
        val state = controller?.playbackState
        val metadata = controller?.metadata
        currentEvent = if (controller != null && state?.state == PlaybackState.STATE_PLAYING && metadata != null) {
            PillEvent(
                providerId = id,
                type = EventType.MEDIA,
                priority = PRIORITY,
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                subtitle = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            )
        } else {
            null
        }
        listener?.invoke()
    }
}
