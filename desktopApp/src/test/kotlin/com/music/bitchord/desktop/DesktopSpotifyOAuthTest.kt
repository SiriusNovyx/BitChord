package com.music.bitchord.desktop

import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSpotifyOAuthTest {
    private val http = HttpClient.newHttpClient()
    private fun parameters(uri: URI) = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
    }
    private fun get(uri: String) = http.send(HttpRequest.newBuilder(URI(uri)).GET().build(),
        HttpResponse.BodyHandlers.ofString())

    @Test fun `PKCE uses the RFC S256 vector and fresh independent values`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            spotifyPkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        SpotifyAuthorization(port = 0).use { first ->
            SpotifyAuthorization(port = 0).use { second ->
                val a = parameters(first.authorizationUri)
                val b = parameters(second.authorizationUri)
                assertEquals("S256", a["code_challenge_method"])
                assertEquals(DesktopSpotifyOAuth.CLIENT_ID, a["client_id"])
                assertNotEquals(a["state"], b["state"])
                assertNotEquals(a["code_challenge"], b["code_challenge"])
                assertTrue(a.getValue("state").length >= 43)
                assertFalse(first.authorizationUri.toString().contains(first.verifier))
            }
        }
    }

    @Test fun `native playback approval has its own identity callback and minimal scope`() {
        SpotifyAuthorization(port = 0, scopes = "streaming", clientId = "65b708073fc0480ea92a077233ca87bd",
            callbackPath = "/login", forceConsent = true).use { pending ->
            val params = parameters(pending.authorizationUri)
            assertEquals("65b708073fc0480ea92a077233ca87bd", params["client_id"])
            assertEquals("streaming", params["scope"])
            assertEquals("true", params["show_dialog"])
            assertEquals("/login", pending.redirectUri.path)
        }
    }

    @Test fun `listener is bound before browser launch and accepts only one valid callback`() = runBlocking {
        SpotifyAuthorization(port = 0).use { pending ->
            val state = parameters(pending.authorizationUri).getValue("state")
            assertEquals(400, get("${pending.redirectUri}?code=not-accepted&state=wrong").statusCode())
            assertEquals(400, get("${pending.redirectUri}?code=one&code=two&state=$state").statusCode())
            assertEquals(404, get("http://127.0.0.1:${pending.redirectUri.port}/other?state=$state&code=no").statusCode())
            val accepted = get("${pending.redirectUri}?code=accepted-code&state=$state")
            assertEquals(200, accepted.statusCode())
            assertFalse(accepted.body().contains("accepted-code"))
            assertEquals("accepted-code", pending.awaitCode())
            assertEquals(409, get("${pending.redirectUri}?code=second&state=$state").statusCode())
        }
    }

    @Test fun `denied consent is bounded and never exposes server response details`() = runBlocking {
        SpotifyAuthorization(port = 0).use { pending ->
            val state = parameters(pending.authorizationUri).getValue("state")
            assertEquals(200, get("${pending.redirectUri}?error=access_denied&error_description=private&state=$state").statusCode())
            val failure = assertFailsWith<SpotifyAuthorizationDenied> { pending.awaitCode() }
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }

    @Test fun `cancellation releases callback port`() = runBlocking {
        val pending = SpotifyAuthorization(port = 0)
        val port = pending.redirectUri.port
        val waiting = async { pending.awaitCode() }
        waiting.cancel()
        pending.close()
        SpotifyAuthorization(port = port).use { assertEquals(port, it.redirectUri.port) }
    }

    @Test fun `expiry releases callback port`() = runBlocking {
        val pending = SpotifyAuthorization(port = 0, timeoutMs = 20)
        val port = pending.redirectUri.port
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { pending.awaitCode() }
        SpotifyAuthorization(port = port).use { assertEquals(port, it.redirectUri.port) }
    }
}
