package dev.pill.dynamicpill.providers.spotify

import android.media.session.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyProviderLogicTest {

    @Test
    fun `playing and paused count as active`() {
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_PLAYING))
        assertTrue(isSpotifyPlaybackActive(PlaybackState.STATE_PAUSED))
    }

    @Test
    fun `stopped, none, and missing state are not active`() {
        assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_STOPPED))
        assertFalse(isSpotifyPlaybackActive(PlaybackState.STATE_NONE))
        assertFalse(isSpotifyPlaybackActive(null))
    }
}
