package com.music.bitchord.desktop

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Separate public playback grant used by librespot; never substitutes for the fork's Web API grant. */
internal object DesktopSpotifyPlayback {
    // The standard desktop playback identity in librespot 0.8.0 and the requested reference flow.
    // https://github.com/crmne/spotifast/blob/v0.13.2/src/auth.rs
    const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private val api = SpotifyApi(clientId = CLIENT_ID)
    private val store = SpotifyGrantStore.desktop("spotify.playback.dpapi.v1", CLIENT_ID)
    val session = SpotifySession(api::refresh, store)

    suspend fun restore() = withContext(Dispatchers.IO) {
        val web = DesktopSpotifyAccount.session.profile.value ?: return@withContext
        val expected = session.revision()
        val saved = store.load() ?: return@withContext
        check(saved.accountId == web.accountId) { "Reconnect Spotify playback for this account." }
        val tokens = if (saved.expiresAtMs - System.currentTimeMillis() <= 60_000) api.refresh(saved) else saved
        DesktopSpotifyRuntime.authenticate(tokens, web)
        verifySpotifyPlaybackAccount(DesktopSpotifyAccount.session.profile.value, web)
        currentCoroutineContext().ensureActive()
        session.install(tokens, web, expected)
    }

    suspend fun connect(openBrowser: (URI) -> Unit) = withContext(Dispatchers.IO) {
        val web = checkNotNull(DesktopSpotifyAccount.session.profile.value) { "Connect your Spotify library first." }
        val expected = session.beginConnection()
        SpotifyAuthorization(port = 8898, scopes = "streaming", clientId = CLIENT_ID,
            callbackPath = "/login", forceConsent = true).use { authorization ->
            openBrowser(authorization.authorizationUri)
            val code = authorization.awaitCode()
            val tokens = api.exchangeCode(code, authorization.verifier, authorization.redirectUri.toString())
            check("streaming" in tokens.scopes) { "Spotify did not grant audio streaming permission." }
            // Streaming grants are not Web API grants. Authenticate at the native session boundary.
            DesktopSpotifyRuntime.authenticate(tokens, web)
            verifySpotifyPlaybackAccount(DesktopSpotifyAccount.session.profile.value, web)
            currentCoroutineContext().ensureActive()
            session.install(SpotifyTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs,
                tokens.scopes, web.accountId), web, expected)
        }
    }

    suspend fun accessToken(): String {
        verifySpotifyPlaybackAccount(DesktopSpotifyAccount.session.profile.value,
            checkNotNull(session.profile.value) { "Connect Spotify playback first." })
        return session.accessToken()
    }

    fun disconnect() {
        session.disconnect()
        DesktopSpotifyRuntime.disconnect()
    }
}
