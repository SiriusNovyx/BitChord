package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

internal class SpotifyGrantStore(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val protect: (ByteArray) -> ByteArray?,
    private val unprotect: (ByteArray) -> ByteArray?,
    private val clientId: String = DesktopSpotifyOAuth.CLIENT_ID,
) {
    fun save(tokens: SpotifyTokens): Boolean {
        val plain = buildJsonObject {
            put("client_id", clientId)
            put("access", tokens.accessToken)
            put("refresh", tokens.refreshToken)
            put("expires", tokens.expiresAtMs)
            put("scopes", tokens.scopes.joinToString(" "))
            tokens.accountId?.let { put("account_id", it) }
        }.toString().toByteArray()
        val sealed = try { protect(plain) } finally { plain.fill(0) }
        if (sealed == null) {
            clear()
            return false
        }
        return runCatching { write(Base64.getEncoder().encodeToString(sealed)); true }.getOrDefault(false)
    }

    fun load(): SpotifyTokens? = runCatching {
        val encoded = read().takeIf { it.length in 1..65_536 } ?: return null
        val plain = unprotect(Base64.getDecoder().decode(encoded)) ?: return null
        try {
            val body = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
            fun field(key: String) = body[key]?.jsonPrimitive?.contentOrNull
            if (field("client_id") != clientId) return null
            SpotifyTokens(field("access")?.takeIf(String::isNotBlank) ?: return null,
                field("refresh")?.takeIf(String::isNotBlank) ?: return null,
                body["expires"]?.jsonPrimitive?.longOrNull ?: return null,
                field("scopes").orEmpty().split(' ').filter(String::isNotBlank).toSet(), field("account_id"))
        } finally { plain.fill(0) }
    }.getOrNull()

    fun clear() { write("") }

    companion object {
        fun desktop(key: String = "spotify.oauth.dpapi.v1", clientId: String = DesktopSpotifyOAuth.CLIENT_ID): SpotifyGrantStore {
            val persistence = DesktopPersistence()
            return SpotifyGrantStore({ persistence.string(key) }, { persistence.saveString(key, it) },
                DesktopWindowsCrypto::protect, DesktopWindowsCrypto::unprotect, clientId)
        }
    }
}

internal fun verifySpotifyPlaybackAccount(web: SpotifyProfile?, playback: SpotifyProfile) {
    check(web != null && web.accountId == playback.accountId) {
        "Spotify playback must use the same account as the library connection."
    }
}

internal class SpotifySession(
    private val refresh: suspend (SpotifyTokens) -> SpotifyTokens,
    private val store: SpotifyGrantStore? = null,
) {
    private val mutableProfile = MutableStateFlow<SpotifyProfile?>(null)
    val profile = mutableProfile.asStateFlow()
    private val mutablePersistent = MutableStateFlow(false)
    val persistent = mutablePersistent.asStateFlow()
    private val mutablePlaybackAuthorized = MutableStateFlow(false)
    val playbackAuthorized = mutablePlaybackAuthorized.asStateFlow()
    private val lock = Any()
    private val refreshMutex = Mutex()
    private var current: SpotifyTokens? = null
    private var revision = 0L

    fun revision(): Long = synchronized(lock) { revision }

    fun beginConnection(): Long = synchronized(lock) { ++revision }

    fun install(tokens: SpotifyTokens, account: SpotifyProfile? = null, expectedRevision: Long = revision()) {
        synchronized(lock) {
            check(revision == expectedRevision) { "Spotify connection changed. Please try again." }
            revision++
            current = tokens
            mutablePlaybackAuthorized.value = "streaming" in tokens.scopes
            mutableProfile.value = account
            mutablePersistent.value = store?.save(tokens) == true
        }
    }

    fun disconnect() {
        synchronized(lock) {
            revision++
            current = null
            mutablePlaybackAuthorized.value = false
            mutableProfile.value = null
            mutablePersistent.value = false
            store?.clear()
        }
    }

    suspend fun accessToken(): String = refreshMutex.withLock {
        val (before, expected) = synchronized(lock) {
            checkNotNull(current) { "Connect Spotify in Account & integrations." } to revision
        }
        if (System.currentTimeMillis() < before.expiresAtMs - 60_000) return@withLock before.accessToken
        val renewed = try { refresh(before) } catch (failure: SpotifyApiException) {
            if (failure.status == 400 || failure.status == 401) {
                synchronized(lock) { if (revision == expected) disconnect() }
            }
            throw failure
        }
        synchronized(lock) {
            check(revision == expected) { "Spotify connection changed. Please try again." }
            current = renewed
            mutablePlaybackAuthorized.value = "streaming" in renewed.scopes
            mutablePersistent.value = store?.save(renewed) == true
            renewed.accessToken
        }
    }

    suspend fun <T> withToken(block: suspend (String) -> T): T {
        val expected = revision()
        val token = accessToken()
        val result = try { block(token) } catch (failure: SpotifyApiException) {
            if (failure.status != 401) throw failure
            synchronized(lock) {
                check(revision == expected) { "Spotify connection changed. Please try again." }
                current?.takeIf { it.accessToken == token }?.let {
                    current = SpotifyTokens(it.accessToken, it.refreshToken, 0, it.scopes, it.accountId)
                }
            }
            block(accessToken())
        }
        synchronized(lock) { check(revision == expected) { "Spotify connection changed. Please try again." } }
        return result
    }
}

internal object DesktopSpotifyAccount {
    val api = SpotifyApi()
    private val store = SpotifyGrantStore.desktop()
    val session = SpotifySession(api::refresh, store)

    suspend fun restore() = withContext(Dispatchers.IO) {
        val expected = session.revision()
        val saved = store.load() ?: return@withContext
        val tokens = if (saved.expiresAtMs - System.currentTimeMillis() <= 60_000) api.refresh(saved) else saved
        val account = api.profile(tokens.accessToken)
        currentCoroutineContext().ensureActive()
        session.install(tokens, account, expected)
    }

    suspend fun connect(openBrowser: (java.net.URI) -> Unit) = withContext(Dispatchers.IO) {
        val expected = session.beginConnection()
        val scopes = DesktopSpotifyOAuth.SCOPES
        SpotifyAuthorization(scopes = scopes).use { authorization ->
            openBrowser(authorization.authorizationUri)
            val code = authorization.awaitCode()
            val tokens = api.exchangeCode(code, authorization.verifier, authorization.redirectUri.toString())
            check(tokens.scopes.containsAll(scopes.split(' '))) {
                "Spotify did not grant the library permissions. Please reconnect."
            }
            val account = api.profile(tokens.accessToken)
            currentCoroutineContext().ensureActive()
            session.install(tokens, account, expected)
        }
    }
}
