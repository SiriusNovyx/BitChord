package com.music.bitchord.desktop

import com.music.bitchord.data.spotify.SpotifyTrack
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class DesktopSpotifyRoutingTest {
    @Test fun `native Spotify is never used to seed a YouTube station`() = runBlocking {
        val song = SpotifyTrack("0123456789012345678901", "Track", "Artist", "Album", 123_000, null).asNativeSong()
        var searches = 0
        assertNull(DesktopAutoplay.youtubeSeedFor(song) { searches++; Result.success(emptyList()) })
        assertEquals(0, searches)
        assertTrue(DesktopAutoplay.tracksFor(emptyList(), song).isFailure)
        assertNull(DesktopAutoplay.youtubeSeedFor(song.copy(videoId = "jiosaavn:synthetic")) {
            searches++; Result.success(emptyList())
        })
        assertEquals(1, searches, "Other catalogue sources retain the existing matching path")
    }
    @Test fun `native rows keep Spotify identity instead of a pending YouTube match`() {
        val song = SpotifyTrack("0123456789012345678901", "Track", "Artist", "Album", 123_000, null).asNativeSong()
        assertEquals("spotify:track:0123456789012345678901", song.videoId)
        assertEquals("2:03", song.durationText)
    }

    @Test fun `Spotify audio never goes through a download resolver`() = runBlocking {
        val song = SpotifyTrack("0123456789012345678901", "Track", "Artist", "Album", 123_000, null).asNativeSong()
        val result = DesktopSpotifyStreams.resolve(song, forDownload = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("download", true))
    }
}
