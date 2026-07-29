package dev.pill.dynamicpill.core.model

import android.graphics.Bitmap

/**
 * A single candidate thing the pill could be showing. Providers (calls,
 * messages, Spotify — Phase 4) each expose at most one of these at a time;
 * the Arbiter picks the highest-priority one across all providers.
 *
 * [icon] is a pragmatic exception to `core`'s "no Android UI deps" spirit
 * (CLAUDE.md module table): that rule is about not depending on the Android
 * *UI framework* (Views, Contexts, WindowManager) so the state machine and
 * Arbiter stay pure-logic and unit-testable — `Bitmap` is a plain graphics
 * data holder neither of them inspects or branches on, just passes through
 * untouched on its way to the renderer. Providers must downscale before
 * setting this (CLAUDE.md rule 9 — never carry a full-size bitmap here).
 *
 * The transport action callbacks are the same idea applied to behavior, not
 * just data (see DEFERRED.md item 1, now resolved): rather than
 * PillAccessibilityService/PillTouchView importing SpotifyProvider directly
 * to call togglePlayPause()/skipNext()/skipPrevious() (a CLAUDE.md rule-7
 * violation — overlay reaching into providers.spotify), the event that
 * needs them carries its own closures. The renderer/touch layer invoke
 * whatever's on the current winner event without ever knowing which
 * concrete provider produced it. Null means "not applicable" (most
 * events, and non-media types, won't have these) rather than "no-op" —
 * callers should treat null as "hide the control", not "call it anyway".
 */
data class PillEvent(
    val providerId: String,
    val type: EventType,
    val priority: Int,
    val title: String,
    val subtitle: String? = null,
    /**
     * Secondary context shown in the expanded header, opposite the source app
     * name — the album for media, and whatever the equivalent turns out to be
     * for calls/messages. Null simply leaves that side of the header empty.
     */
    val contextLabel: String? = null,
    val icon: Bitmap? = null,
    val isPlaying: Boolean = true,
    val onPlayPause: (() -> Unit)? = null,
    val onSkipPrevious: (() -> Unit)? = null,
    val onSkipNext: (() -> Unit)? = null,
    /**
     * Hands off to whatever app produced this event (GestureAction.OPEN_APP).
     * Same closure rationale as the transport callbacks above — the overlay
     * opens the source app without ever knowing which app that is.
     */
    val onOpen: (() -> Unit)? = null,
    /** Dominant color extracted from [icon] (media art), for background tint. Null = no tint. */
    val accentColor: Int? = null,
    // Scrubber source data (rule 10) — the renderer extrapolates the live
    // position from these rather than polling PlaybackState on a timer:
    // positionMs + (elapsedRealtime() - positionUpdateTimeMs) * playbackSpeed.
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val positionUpdateTimeMs: Long = 0L,
)

enum class EventType {
    CALL, MESSAGE, MEDIA
}
