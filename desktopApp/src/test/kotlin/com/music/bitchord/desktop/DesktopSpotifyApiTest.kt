package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSpotifyApiTest {
    private fun fixture(block: (com.sun.net.httpserver.HttpExchange) -> Unit,
        clientId: String = DesktopSpotifyOAuth.CLIENT_ID, test: (SpotifyApi) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", block)
        server.start()
        try { test(SpotifyApi(URI("http://127.0.0.1:${server.address.port}/"), clientId)) }
        finally { server.stop(0) }
    }
    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        exchange.use {
            val bytes = body.toByteArray()
            it.sendResponseHeaders(status, bytes.size.toLong())
            it.responseBody.write(bytes)
        }
    }

    @Test fun `code exchange sends verifier without a client secret and refresh retains rotated grant`() {
        var requests = 0
        fixture({ exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            assertFalse(body.contains("client_secret"))
            assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
            if (requests++ == 0) {
                assertTrue(body.contains("code_verifier=verifier"))
                respond(exchange, 200, """{"access_token":"access1","refresh_token":"refresh1","token_type":"Bearer","expires_in":3600,"scope":"user-library-read"}""")
            } else {
                assertTrue(body.contains("refresh_token=refresh1"))
                respond(exchange, 200, """{"access_token":"access2","token_type":"Bearer","expires_in":3600}""")
            }
        }) { api -> runBlocking {
            val first = api.exchangeCode("code", "verifier", DesktopSpotifyOAuth.REDIRECT_URI)
            val refreshed = api.refresh(first)
            assertEquals("access2", refreshed.accessToken)
            assertEquals("refresh1", refreshed.refreshToken)
            assertEquals(first.scopes, refreshed.scopes)
            assertFalse(refreshed.toString().contains("access2"))
        } }
    }

    @Test fun `profile prefers account identity when present and keeps unknown subscription unknown`() {
        fixture({ respond(it, 200, """{"account_id":"stable-account","id":"mutable-name","display_name":"Listener"}""") }) { api -> runBlocking {
            val profile = api.profile("access")
            assertEquals("stable-account", profile.accountId)
            assertEquals("mutable-name", profile.playbackUsername)
            assertEquals(null, profile.product)
        } }
    }

    @Test fun `profile accepts the current user response with id and no account_id`() {
        fixture({ exchange ->
            assertEquals("/v1/me", exchange.requestURI.path)
            assertEquals("Bearer access", exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, """{"id":"spotify-user-123","display_name":"Listener","product":"premium","type":"user","images":[],"external_urls":{"spotify":"https://open.spotify.com/user/spotify-user-123"}}""")
        }) { api -> runBlocking {
            val profile = api.profile("access")
            assertEquals("spotify-user-123", profile.accountId)
            assertEquals("spotify-user-123", profile.playbackUsername)
            assertEquals("Listener", profile.displayName)
            assertEquals("premium", profile.product)
        } }
    }

    @Test fun `profile falls back to id when account_id is null or blank`() {
        for (account in listOf("null", "\"\"", "\"  \"")) {
            fixture({ respond(it, 200, """{"account_id":$account,"id":"spotify-user-123","display_name":null}""") }) { api -> runBlocking {
                val profile = api.profile("access")
                assertEquals("spotify-user-123", profile.accountId)
                assertEquals("spotify-user-123", profile.playbackUsername)
                assertEquals("Spotify listener", profile.displayName)
            } }
        }
    }

    @Test fun `profile rejects responses without a usable account identity`() {
        for (body in listOf("{}", """{"display_name":"Listener"}""", """{"account_id":" ","id":""}""")) {
            fixture({ respond(it, 200, body) }) { api -> runBlocking {
                val failure = assertFailsWith<SpotifyApiException> { api.profile("access") }
                assertEquals(502, failure.status)
            } }
        }
    }

    @Test fun `refresh preserves protected account binding`() {
        fixture({ respond(it, 200, """{"access_token":"new","token_type":"Bearer","expires_in":3600}""") }) { api -> runBlocking {
            val updated = api.refresh(SpotifyTokens("old", "refresh", 0, setOf("streaming"), "stable-account"))
            assertEquals("stable-account", updated.accountId)
        } }
    }

    @Test fun `native exchanges use the playback identity instead of the personal app`() {
        var received = ""
        fixture({
            received = it.requestBody.bufferedReader().readText()
            respond(it, 200, """{"access_token":"native","refresh_token":"refresh","token_type":"Bearer","expires_in":3600,"scope":"streaming"}""")
        }, clientId = "65b708073fc0480ea92a077233ca87bd") { api -> runBlocking {
            api.exchangeCode("code", "verifier", "http://127.0.0.1:8898/login")
            assertTrue(received.contains("client_id=65b708073fc0480ea92a077233ca87bd"))
            assertFalse(received.contains(DesktopSpotifyOAuth.CLIENT_ID))
        } }
    }

    @Test fun `API errors never include the response body or bearer`() {
        fixture({ respond(it, 403, "private-details-bearer-secret") }) { api -> runBlocking {
            val failure = assertFailsWith<SpotifyApiException> { api.profile("bearer-secret") }
            assertEquals(403, failure.status)
            assertFalse(failure.message.orEmpty().contains("secret"))
        } }
    }

    @Test fun `rate limit enters cooldown without another network request`() {
        var requests = 0
        fixture({
            requests++
            it.responseHeaders.set("Retry-After", "120")
            respond(it, 429, "{}")
        }) { api -> runBlocking {
            assertFailsWith<SpotifyApiException> { api.profile("access") }
            val limited = assertFailsWith<SpotifyApiException> { api.profile("access") }
            assertEquals(429, limited.status)
            assertTrue(limited.retryAfterSeconds > 0)
            assertTrue(limited.message.orEmpty().contains("Wait"))
            assertTrue(limited.message.orEmpty().contains(limited.retryAfterSeconds.toString()))
            assertEquals(1, requests)
        } }
    }

    @Test fun `redirects never forward credentials`() {
        var requests = 0
        fixture({
            requests++
            it.responseHeaders.set("Location", "/stolen")
            respond(it, 302, "{}")
        }) { api -> runBlocking {
            assertFailsWith<SpotifyApiException> { api.profile("access") }
            assertEquals(1, requests)
        } }
    }

    @Test fun `oversized API response is rejected`() {
        fixture({ respond(it, 200, "x".repeat(1_048_577)) }) { api -> runBlocking {
            assertFailsWith<SpotifyApiException> { api.profile("access") }
        } }
    }

    @Test fun `library pagination counts raw rows and uses current playlist item field`() {
        val paths = mutableListOf<String>()
        fixture({ exchange ->
            paths += exchange.requestURI.toString()
            assertEquals("Bearer access", exchange.requestHeaders.getFirst("Authorization"))
            val body = when {
                exchange.requestURI.path.endsWith("/me/playlists") -> """{"items":[{"id":"playlist1","name":"Mix","owner":{"display_name":"Owner"},"images":[]}],"next":null}"""
                exchange.requestURI.query.contains("offset=0") -> """{"items":[{"is_local":false,"item":{"id":"track1","type":"track","name":"First","duration_ms":120000,"artists":[{"name":"Artist"}],"album":{"name":"Album","images":[]}}},{"item":null}],"next":"https://untrusted.invalid/never-follow"}"""
                else -> """{"items":[{"item":{"id":"episode1","type":"episode","name":"Podcast"}},{"is_local":true,"item":{"id":"local","type":"track"}},{"item":{"id":"track2","type":"track","name":"Second","artists":[],"album":{"images":[]}}}],"next":null}"""
            }
            respond(exchange, 200, body)
        }) { api -> runBlocking {
            val playlists = api.playlists("access")
            assertEquals(listOf("liked", "playlist1"), playlists.map { it.id })
            val tracks = api.tracks("playlist1", "access")
            assertEquals(listOf("track1", "track2"), tracks.map { it.id })
            assertEquals("Artist", tracks.first().artist)
            assertTrue(paths.last().contains("/items?limit=50&offset=2"))
            assertEquals(3, paths.size)
        } }
    }
}
