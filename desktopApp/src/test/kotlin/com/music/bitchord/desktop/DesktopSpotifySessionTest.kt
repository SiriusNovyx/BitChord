package com.music.bitchord.desktop

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSpotifySessionTest {
    @Test fun `failed protection keeps sign in session only and clears old persisted grant`() {
        var saved = "old-sealed-grant"
        val store = SpotifyGrantStore({ saved }, { saved = it }, { null }, { null })
        assertFalse(store.save(SpotifyTokens("secret-access", "secret-refresh", 123, setOf("read"))))
        assertEquals("", saved)
        assertNull(store.load())
    }

    @Test fun `grant round trip stores only protected bytes and rejects malformed storage`() {
        var saved = ""
        val store = SpotifyGrantStore({ saved }, { saved = it },
            { bytes -> bytes.map { (it.toInt() xor 91).toByte() }.toByteArray() },
            { bytes -> bytes.map { (it.toInt() xor 91).toByte() }.toByteArray() })
        assertTrue(store.save(SpotifyTokens("secret-access", "secret-refresh", 123, setOf("read"))))
        assertFalse(String(Base64.getDecoder().decode(saved)).contains("secret"))
        assertEquals("secret-refresh", store.load()?.refreshToken)
        saved = "not base64"
        assertNull(store.load())
        store.clear()
        assertEquals("", saved)
    }

    @Test fun `concurrent callers share refresh and disconnect rejects its late result`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val session = SpotifySession(refresh = {
            calls++
            started.complete(Unit)
            finish.await()
            SpotifyTokens("new", "rotated", Long.MAX_VALUE, emptySet())
        })
        session.install(SpotifyTokens("old", "refresh", 0, emptySet()))
        val a = async { runCatching { session.accessToken() } }
        val b = async { runCatching { session.accessToken() } }
        started.await()
        session.disconnect()
        finish.complete(Unit)
        assertTrue(listOf(a, b).awaitAll().all { it.isFailure })
        assertEquals(1, calls)
        assertNull(session.profile.value)
        assertFailsWith<IllegalStateException> { session.accessToken() }
        Unit
    }

    @Test fun `parallel expired requests use one rotated token`() = runBlocking {
        var calls = 0
        val session = SpotifySession(refresh = {
            calls++
            SpotifyTokens("new", "rotated", Long.MAX_VALUE, emptySet())
        })
        session.install(SpotifyTokens("old", "refresh", 0, emptySet()))
        assertEquals(listOf("new", "new"), listOf(async { session.accessToken() }, async { session.accessToken() }).awaitAll())
        assertEquals(1, calls)
    }

    @Test fun `an API 401 refreshes once and revoked refresh clears the session`() = runBlocking {
        var refreshes = 0
        val account = SpotifyProfile("account", "Listener", null)
        val session = SpotifySession(refresh = {
            refreshes++
            SpotifyTokens("renewed", "rotated", Long.MAX_VALUE, emptySet())
        })
        session.install(SpotifyTokens("old", "refresh", Long.MAX_VALUE, emptySet()), account)
        val result = session.withToken { token -> if (token == "old") throw SpotifyApiException(401) else token }
        assertEquals("renewed", result)
        assertEquals(1, refreshes)
        val revoked = SpotifySession(refresh = { throw SpotifyApiException(400) })
        revoked.install(SpotifyTokens("old", "refresh", 0, emptySet()), account)
        assertFailsWith<SpotifyApiException> { revoked.accessToken() }
        assertNull(revoked.profile.value)
    }

    @Test fun `playback refuses a different account and stores its grant independently`() {
        val web = SpotifyProfile("one", "Listener", "premium")
        assertFailsWith<IllegalStateException> { verifySpotifyPlaybackAccount(web, web.copy(accountId = "two")) }
        assertFailsWith<IllegalStateException> { verifySpotifyPlaybackAccount(null, web) }
        verifySpotifyPlaybackAccount(web, web)
        var saved = ""
        val native = SpotifyGrantStore({ saved }, { saved = it }, { it.copyOf() }, { it }, clientId = "native-client")
        native.save(SpotifyTokens("native-access", "native-refresh", 123, setOf("streaming"), web.accountId))
        val webStore = SpotifyGrantStore({ saved }, { saved = it }, { it.copyOf() }, { it })
        assertNull(webStore.load())
        assertEquals("native-refresh", native.load()?.refreshToken)
        assertEquals(web.accountId, native.load()?.accountId)
    }

    @Test fun `starting a connection invalidates a late restore without dropping the saved session`() = runBlocking {
        val session = SpotifySession(refresh = { error("No refresh expected") })
        session.install(SpotifyTokens("active", "refresh", Long.MAX_VALUE, emptySet()))
        val restoring = session.revision()
        val connecting = session.beginConnection()
        assertEquals("active", session.accessToken())
        assertFailsWith<IllegalStateException> {
            session.install(SpotifyTokens("stale", "refresh", Long.MAX_VALUE, emptySet()), expectedRevision = restoring)
        }
        session.install(SpotifyTokens("connected", "refresh", Long.MAX_VALUE, emptySet()), expectedRevision = connecting)
        assertEquals("connected", session.accessToken())
    }
}
