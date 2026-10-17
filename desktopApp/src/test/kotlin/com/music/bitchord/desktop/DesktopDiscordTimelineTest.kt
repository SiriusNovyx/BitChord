package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopDiscordTimelineTest {
    @Test fun `seek refreshes presence while normal position reports do not`() {
        val first = DesktopPlaybackState(song = Song(videoId = "test", title = "Track", artist = "Artist",
            thumbnailUrl = null, fromAutoplay = false),
            isPlaying = true, durationMs = 240_000, positionMs = 102_000, seeks = 1)
        val original = discordPlaybackRevision(first, 1f)
        assertEquals(original, discordPlaybackRevision(first.copy(positionMs = 103_000), 1f))
        val sought = first.copy(positionMs = 151_000, seeks = 2, awaitingAudio = true)
        assertNotEquals(original, discordPlaybackRevision(sought, 1f))
        assertNotEquals(discordPlaybackRevision(sought, 1f),
            discordPlaybackRevision(sought.copy(awaitingAudio = false), 1f))
        assertNotEquals(original, discordPlaybackRevision(first.copy(positionMs = 50_000, seeks = 3), 1f))
    }
}
