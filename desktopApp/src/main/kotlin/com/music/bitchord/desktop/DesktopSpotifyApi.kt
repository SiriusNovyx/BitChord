package com.music.bitchord.desktop

import java.net.URI
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class SpotifyTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long,
    val scopes: Set<String>, val accountId: String? = null)
internal data class SpotifyProfile(val accountId: String, val displayName: String, val product: String?,
    val playbackUsername: String? = null)
internal class SpotifyApiException(val status: Int, val retryAfterSeconds: Long = 0) : Exception(
    if (status == 429) "Spotify is rate-limiting requests (429). Wait ${retryAfterSeconds.coerceAtLeast(1)} seconds before trying again."
    else "Spotify request failed ($status).")
internal class SpotifyApi(private val fixtureBase: URI? = null, private val clientId: String = DesktopSpotifyOAuth.CLIENT_ID) {
    suspend fun playlists(accessToken: String): List<SpotifyPlaylist> {
        val found = pages("me/playlists", accessToken).mapNotNull { item ->
            val id = item.text("id") ?: return@mapNotNull null
            SpotifyPlaylist(id, item.text("name") ?: "Untitled playlist",
                (item["owner"] as? JsonObject)?.text("display_name"), item.image())
        }
        return listOf(SpotifyPlaylist("liked", "Liked Songs", null,
            "https://misc.scdn.co/liked-songs/liked-songs-640.png")) + found
    }

    suspend fun tracks(playlistId: String, accessToken: String): List<SpotifyTrack> {
        require(playlistId.matches(Regex("[A-Za-z0-9]+")))
        val path = if (playlistId == "liked") "me/tracks" else "playlists/$playlistId/items"
        return pages(path, accessToken).mapNotNull { row ->
            if ((row["is_local"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val item = (row["item"] as? JsonObject) ?: (row["track"] as? JsonObject) ?: return@mapNotNull null
            if (item.text("type") != "track") return@mapNotNull null
            val id = item.text("id") ?: return@mapNotNull null
            val album = item["album"] as? JsonObject
            SpotifyTrack(id, item.text("name") ?: "Untitled track",
                (item["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }.joinToString(", "),
                album?.text("name"), (item["duration_ms"] as? JsonPrimitive)?.intOrNull ?: 0, album?.image())
        }
    }

    private suspend fun pages(path: String, accessToken: String): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        var offset = 0
        repeat(200) {
            val page = get("$path?limit=50&offset=$offset", accessToken)
            val items = page["items"] as? JsonArray ?: throw SpotifyApiException(502)
            if (items.size > 50) throw SpotifyApiException(502)
            found += items.mapNotNull { it as? JsonObject }
            if (page.text("next") == null) return found
            if (items.isEmpty()) throw SpotifyApiException(502)
            // Generate the next request locally; a server-supplied URL never receives a bearer.
            offset += items.size
        }
        error("Spotify library exceeds 10,000 items. Open a smaller playlist.")
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    @Volatile private var cooldownUntilMs = 0L

    init {
        require(fixtureBase == null || (fixtureBase.scheme == "http" && fixtureBase.host == "127.0.0.1"))
    }

    suspend fun exchangeCode(code: String, verifier: String, redirectUri: String): SpotifyTokens = token(mapOf(
        "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
        "redirect_uri" to redirectUri, "client_id" to clientId,
    ))

    suspend fun refresh(previous: SpotifyTokens): SpotifyTokens = token(mapOf(
        "grant_type" to "refresh_token", "refresh_token" to previous.refreshToken,
        "client_id" to clientId,
    ), previous)

    private suspend fun token(form: Map<String, String>, previous: SpotifyTokens? = null): SpotifyTokens {
        val body = request("https://accounts.spotify.com/api/token", form = form)
        val access = body.text("access_token")?.takeIf { it.isNotBlank() } ?: throw SpotifyApiException(502)
        val refresh = body.text("refresh_token")?.takeIf { it.isNotBlank() } ?: previous?.refreshToken
            ?: throw SpotifyApiException(502)
        val lifetime = body["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..86_400 }
            ?: throw SpotifyApiException(502)
        if (!body.text("token_type").equals("Bearer", ignoreCase = true)) throw SpotifyApiException(502)
        val scopes = body.text("scope")?.split(' ')?.filter(String::isNotBlank)?.toSet()
            ?: previous?.scopes ?: emptySet()
        return SpotifyTokens(access, refresh, System.currentTimeMillis() + lifetime * 1000, scopes, previous?.accountId)
    }

    suspend fun profile(accessToken: String): SpotifyProfile {
        val body = get("me", accessToken)
        val username = body.text("id")?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
        val identity = body.text("account_id")?.takeIf(String::isNotBlank) ?: username ?: throw SpotifyApiException(502)
        return SpotifyProfile(identity, body.text("display_name")?.takeIf(String::isNotBlank) ?: "Spotify listener",
            body.text("product"), username)
    }

    suspend fun get(path: String, accessToken: String): JsonObject {
        require(path.matches(Regex("[A-Za-z0-9_/?=&.-]+")) && !path.startsWith('/') && !path.contains(".."))
        return request("https://api.spotify.com/v1/$path", accessToken)
    }

    private suspend fun request(url: String, bearer: String? = null, form: Map<String, String>? = null): JsonObject {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) throw SpotifyApiException(429, ((cooldownUntilMs - now) / 1000 + 1))
        val source = URI(url)
        val destination = fixtureBase?.resolve(source.rawPath.removePrefix("/") +
            (source.rawQuery?.let { "?$it" } ?: ""))?.toString() ?: url
        val request = Request.Builder().url(destination).header("Accept", "application/json").apply {
            bearer?.let { header("Authorization", "Bearer $it") }
            form?.let { post(spotifyForm(it).toRequestBody("application/x-www-form-urlencoded".toMediaType())) }
        }.build()
        val call = client.newCall(request)
        val text = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(SpotifyApiException(503))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (it.code == 429) {
                                val seconds = it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 60
                                cooldownUntilMs = System.currentTimeMillis() + seconds * 1000
                                throw SpotifyApiException(429, seconds)
                            }
                            if (!it.isSuccessful) throw SpotifyApiException(it.code)
                            val source = it.body?.byteStream() ?: throw SpotifyApiException(502)
                            val bytes = source.readNBytes(1_048_577)
                            if (bytes.size > 1_048_576) throw SpotifyApiException(502)
                            continuation.resume(bytes.toString(Charsets.UTF_8))
                        } catch (failure: Exception) {
                            continuation.resumeWithException(failure as? SpotifyApiException ?: SpotifyApiException(502))
                        }
                    }
                }
            })
        }
        return try { Json.parseToJsonElement(text).jsonObject }
        catch (_: Exception) { throw SpotifyApiException(502) }
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.image(): String? = (this["images"] as? JsonArray)?.firstOrNull()
    ?.let { it as? JsonObject }?.text("url")?.takeIf { it.startsWith("https://") }
