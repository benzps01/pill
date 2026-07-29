package dev.pill.dynamicpill.providers.spotify

import android.media.session.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyProviderLogicTest {

    @Test
    fun `playing is always active`() {
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_PLAYING, hasEverPlayed = false))
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_PLAYING, hasEverPlayed = true))
    }

    @Test
    fun `stopped, none, and missing state are never active`() {
        for (played in listOf(true, false)) {
            assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_STOPPED, played))
            assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_NONE, played))
            assertFalse(isSpotifyPlaybackActive(null, played))
        }
    }

    @Test
    fun `a pause after real playback stays active`() {
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_PAUSED, hasEverPlayed = true))
    }

    /**
     * Regression: clearing Spotify from recents destroys its session, then
     * Spotify relaunches itself and registers a fresh PAUSED session a second
     * or two later. Treating that as live made the pill reappear on its own
     * after the user had deliberately swiped the app away.
     */
    @Test
    fun `a session that has never played is not active when paused`() {
        assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_PAUSED, hasEverPlayed = false))
    }

    /**
     * Regression: Spotify passes through BUFFERING on every track change.
     * Treating it as inactive dropped the event for ~40ms, blinking the pill
     * out and back, and closing an open expanded card.
     */
    @Test
    fun `transient buffering and connecting ride through once playback has started`() {
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_BUFFERING, hasEverPlayed = true))
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_CONNECTING, hasEverPlayed = true))
    }

    @Test
    fun `buffering and connecting on a never-played session are not active`() {
        assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_BUFFERING, hasEverPlayed = false))
        assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_CONNECTING, hasEverPlayed = false))
    }
}
