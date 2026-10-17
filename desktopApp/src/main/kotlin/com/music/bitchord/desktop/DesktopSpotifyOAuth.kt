package com.music.bitchord.desktop

import java.net.URI
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

internal object DesktopSpotifyOAuth {
    const val CLIENT_ID = "aaa50631fe1f4db78234e8674ac9a16d"
    const val REDIRECT_URI = "http://127.0.0.1:43821/callback"
    const val SCOPES = "user-read-private playlist-read-private playlist-read-collaborative user-library-read"
}

internal fun spotifyPkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

internal fun spotifyForm(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
}

internal class SpotifyAuthorizationDenied : Exception("Spotify sign-in was declined.")

internal class SpotifyAuthorization(
    port: Int = 43821,
    private val timeoutMs: Long = 180_000,
    scopes: String = DesktopSpotifyOAuth.SCOPES,
    clientId: String = DesktopSpotifyOAuth.CLIENT_ID,
    private val callbackPath: String = "/callback",
    forceConsent: Boolean = false,
) : AutoCloseable {
    private val result = CompletableDeferred<String>()
    private val state = randomValue()
    val verifier = randomValue()
    private val deadline = System.nanoTime() + timeoutMs * 1_000_000
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Spotify callback").apply { isDaemon = true } }
    val redirectUri = URI("http://127.0.0.1:${server.address.port}$callbackPath")
    val authorizationUri = URI("https://accounts.spotify.com/authorize?" + spotifyForm(buildMap {
        putAll(mapOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to redirectUri.toString(),
        "scope" to scopes,
        "state" to state,
        "code_challenge_method" to "S256",
        "code_challenge" to spotifyPkceChallenge(verifier),
        ))
        if (forceConsent) put("show_dialog", "true")
    }))

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use { handle(it) }
        }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val status = when {
            exchange.requestMethod != "GET" -> 405
            uri.rawPath != callbackPath -> 404
            exchange.requestHeaders.getFirst("Host") != "127.0.0.1:${redirectUri.port}" -> 400
            !exchange.remoteAddress.address.isLoopbackAddress -> 400
            System.nanoTime() >= deadline -> 410
            result.isCompleted -> 409
            else -> {
                val params = parseQuery(uri.rawQuery)
                val receivedState = params?.get("state")
                when {
                    receivedState == null || !MessageDigest.isEqual(state.toByteArray(), receivedState.toByteArray()) -> 400
                    params["code"] != null && params["error"] != null -> 400
                    params["error"] == "access_denied" -> {
                        result.completeExceptionally(SpotifyAuthorizationDenied())
                        200
                    }
                    params["error"] != null -> {
                        result.completeExceptionally(IllegalStateException("Spotify could not authorize this app."))
                        200
                    }
                    params["code"].isNullOrBlank() -> 400
                    else -> {
                        result.complete(params.getValue("code"))
                        200
                    }
                }
            }
        }
        val body = (if (status == 200) "Return to BitChord to finish connecting Spotify."
            else "This Spotify callback was not accepted. Return to BitChord.").toByteArray()
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
    }

    suspend fun awaitCode(): String = try {
        withTimeout(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) { result.await() }
    } catch (failure: Exception) {
        close()
        throw failure
    }

    override fun close() {
        result.cancel()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun parseQuery(query: String?): Map<String, String>? = runCatching {
        require(query != null && query.length <= 8192)
        val params = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2)
            val key = URLDecoder.decode(parts[0], Charsets.UTF_8)
            val value = URLDecoder.decode(parts[1], Charsets.UTF_8)
            require(key !in params)
            params[key] = value
        }
        params
    }.getOrNull()

    private fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
