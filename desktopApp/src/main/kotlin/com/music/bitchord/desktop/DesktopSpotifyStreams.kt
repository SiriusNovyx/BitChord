package com.music.bitchord.desktop

import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.model.Song

internal const val SPOTIFY_NATIVE_PREFIX = "spotify:track:"
internal val Song.isNativeSpotify: Boolean get() = videoId.startsWith(SPOTIFY_NATIVE_PREFIX)
internal fun SpotifyTrack.asNativeSong(): Song = asPendingSong().copy(videoId = SPOTIFY_NATIVE_PREFIX + id)
internal object DesktopSpotifyStreams {
    fun resolve(song: Song, forDownload: Boolean = false): Result<DesktopStream> = runCatching {
        check(!forDownload) { "Spotify tracks cannot be downloaded by BitChord." }
        check(song.isNativeSpotify)
        check(DesktopSpotifyRuntime.available) { "This build does not include the native Spotify playback helper." }
        check(DesktopSpotifyPlayback.session.profile.value != null) { "Connect Spotify playback in Account & integrations." }
        verifySpotifyPlaybackAccount(DesktopSpotifyAccount.session.profile.value, DesktopSpotifyPlayback.session.profile.value!!)
        DesktopStream(song.videoId, DesktopStreamFormat(codec = "Spotify", sampleRateHz = 44_100, channels = 2),
            sourceId = "spotify-native")
    }
}
