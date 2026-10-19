package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import com.my.kizzy.rpc.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal fun discordPlaybackRevision(state: DesktopPlaybackState, speed: Float): String? =
    state.song?.let { "${it.videoId}|${state.durationMs}|$speed|${state.isPlaying}|${state.seeks}|${state.awaitingAudio}" }

/**
 * Publishes what is playing to Discord as a Rich Presence activity.
 *
 * Two routes, and the desktop gets the good one Android cannot have.
 *
 * **The local socket, preferred.** A Discord client running on this machine
 * listens on a named pipe, and an application sets a presence through it with
 * nothing but its own application id — no token, no login, no impersonation.
 * That is Discord's official Rich Presence mechanism and what every desktop
 * application uses. Android has no such client to talk to, which is the whole
 * reason its implementation looks the way it does.
 *
 * **The account gateway, as a fallback.** Android's route, ported: sign in as
 * the account and speak the client's own protocol, because on a phone there is
 * nothing else. It stays here for the case the socket cannot cover — Discord
 * not running locally, or running somewhere else — and it is offered the way
 * Android offers it, as the alternative rather than the front door, because it
 * hands an app full account access and account automation is against Discord's
 * terms.
 */
internal object DesktopDiscordRpc {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rpc: KizzyRPC? = null
    private data class PresenceSnapshot(
        val state: DesktopPlaybackState,
        val speed: Float,
        val nowMs: Long,
        val startMs: Long,
        val endMs: Long,
        val title: String,
    )

    private val snapshotLock = Any()
    private var latest: PresenceSnapshot? = null
    private val _localClient = MutableStateFlow(false)
    val localClient: StateFlow<Boolean> = _localClient

    fun refreshLocalClient() = session.probe()

    private val _username = MutableStateFlow(DesktopPersistence().string(KEY_USERNAME, ""))

    /** Who the stored token belongs to, for the settings row to name. */
    val username: StateFlow<String> = _username

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    private val _token = MutableStateFlow(DesktopPersistence().string(KEY_TOKEN, ""))
    val token: StateFlow<String> = _token

    private val session = DesktopDiscordSession(
        scope = scope,
        connect = { DesktopDiscordIpc.connect(APPLICATION_ID) },
        onAvailabilityChanged = { available ->
            _localClient.value = available
        },
        onSessionStopped = ::closeGateway,
        onLocalActivityPublished = ::closeGateway,
    )

    init { session.setEnabled(_enabled.value) }

    /** Checks a token by asking Discord who it belongs to, then keeps it. */
    suspend fun signIn(candidate: String): Result<UserInfo> =
        KizzyRPC.getUserInfo(candidate, USER_AGENT, superProperties())
            .onSuccess { user ->
                DesktopPersistence().saveString(KEY_TOKEN, candidate)
                DesktopPersistence().saveString(KEY_USERNAME, user.name)
                _token.value = candidate
                _username.value = user.name
                setEnabled(true)
                session.restart()
            }

    fun signOut() {
        setEnabled(false)
        DesktopPersistence().saveString(KEY_TOKEN, "")
        DesktopPersistence().saveString(KEY_USERNAME, "")
        _token.value = ""
        _username.value = ""
    }

    fun setEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
        session.setEnabled(value)
    }

    fun onPlaybackStateChanged(state: DesktopPlaybackState, speed: Float) = synchronized(snapshotLock) {
        val song = state.song
        if (song == null) {
            clear()
            return@synchronized
        }
        val rate = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val now = System.currentTimeMillis()
        val duration = state.durationMs.coerceAtLeast(0L)
        val snapshot = PresenceSnapshot(
            state, rate, now,
            now - (state.positionMs / rate).toLong(),
            now + ((duration - state.positionMs).coerceAtLeast(0L) / rate).toLong(),
            if (rate == 1f) song.title else "${song.title} [${String.format(Locale.ROOT, "%.2fx", rate)}]",
        )
        latest = snapshot
        publish(snapshot)
    }

    /** Display changes retain the original clock anchor; they do not rewind the song. */
    fun refreshActivity() = synchronized(snapshotLock) {
        latest?.let(::publish)
    }

    private fun publish(snapshot: PresenceSnapshot) {
        val song = snapshot.state.song ?: return
        if (!snapshot.state.isPlaying || snapshot.state.awaitingAudio) {
            session.update(null) { closeGateway() }
            return
        }
        val activity = ipcActivity(song, snapshot.title, snapshot.startMs, snapshot.endMs)
        session.update(activity) { sendGateway(snapshot) }
    }

    fun clear() = synchronized(snapshotLock) {
        latest = null
        session.update(null) { closeGateway() }
    }

    private fun closeGateway() {
        runCatching { rpc?.closeRPC() }
        rpc = null
    }

    private suspend fun sendGateway(snapshot: PresenceSnapshot) {
        if (_token.value.isBlank()) return
        val song = snapshot.state.song ?: return
        val title = snapshot.title
        val now = snapshot.nowMs
        val elapsed = now - snapshot.startMs
        val remaining = snapshot.endMs - now
        val client = rpc ?: KizzyRPC(
            token = _token.value,
            os = if (DesktopPlatform.isWindows) "Windows" else "Linux",
            browser = "Discord Client",
            device = "desktop",
            userAgent = USER_AGENT,
            superPropertiesBase64 = superProperties(),
        ).also { rpc = it }

        client.setActivity(
            name = DesktopDiscordSettings.activityName.value
                .ifBlank { DesktopDiscordSettings.APP_NAME },
            details = title,
            state = song.artist,
            detailsUrl = watchUrl(song),
            // Never null: an activity with no large image falls back to
            // whatever icon the application id points at, which is not
            // ours to set.
            largeImage = RpcImage.ExternalImage(
                song.artworkAt(ART_PX)?.takeIf { it.startsWith("http") } ?: FALLBACK_ART_URL,
            ),
            smallImage = null,
            largeText = song.albumName,
            buttons = buttonLabels(song).takeIf { it.isNotEmpty() },
            type = when (DesktopDiscordSettings.activityType.value) {
                "playing" -> KizzyRPC.Type.PLAYING
                "watching" -> KizzyRPC.Type.WATCHING
                "competing" -> KizzyRPC.Type.COMPETING
                else -> KizzyRPC.Type.LISTENING
            },
            statusDisplayType = if (DesktopDiscordSettings.useDetails.value) {
                KizzyRPC.StatusDisplayType.DETAILS
            } else {
                KizzyRPC.StatusDisplayType.STATE
            },
            since = now,
            startTime = now - elapsed,
            endTime = now + remaining,
            applicationId = APPLICATION_ID,
            status = DesktopDiscordSettings.status.value,
        )
    }

    /**
     * The activity as the local socket wants it.
     *
     * The same fields the gateway payload carries, in the shape SET_ACTIVITY
     * takes. Artwork goes over as a plain URL: mirroring it onto Discord's CDN
     * is what needs an account token, and the client resolves an http asset by
     * itself.
     */
    private fun ipcActivity(song: Song, title: String, startMs: Long, endMs: Long): JsonObject = buildJsonObject {
        put("type", DesktopDiscordSettings.activityNumber(DesktopDiscordSettings.activityType.value))
        DesktopDiscordSettings.activityName.value.takeIf(String::isNotBlank)?.let { put("name", it) }
        put("details", title)
        put("state", song.artist)
        // Which line Discord bolds, and repeats beside the name in a member
        // list — the whole point of "Lead with the song".
        put("status_display_type", if (DesktopDiscordSettings.useDetails.value) 1 else 2)
        putJsonObject("timestamps") {
            put("start", startMs)
            put("end", endMs)
        }
        putJsonObject("assets") {
            put("large_image", song.artworkAt(ART_PX)?.takeIf { it.startsWith("http") } ?: FALLBACK_ART_URL)
            song.albumName?.let { put("large_text", it) }
        }
        val labels = buttonLabels(song)
        if (labels.isNotEmpty()) {
            putJsonArray("buttons") {
                labels.forEach { (label, url) ->
                    add(buildJsonObject { put("label", label); put("url", url) })
                }
            }
        }
    }

    /** The buttons as configured: text with its variables filled in, and hidden ones dropped. */
    private fun buttonLabels(song: Song): List<Pair<String, String>> = buildList {
        val settings = DesktopDiscordSettings
        if (settings.button1Visible.value) {
            val text = settings.button1Text.value.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_1 }
            add(settings.resolveVariables(text, song.title, song.artist, song.albumName) to watchUrl(song))
        }
        if (settings.button2Visible.value) {
            val text = settings.button2Text.value.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_2 }
            add(settings.resolveVariables(text, song.title, song.artist, song.albumName) to PROJECT_URL)
        }
    }

    /**
     * The `X-Super-Properties` header Discord's own client sends.
     *
     * The gateway accepts a presence without it, but the endpoint that mirrors
     * external artwork onto Discord's CDN starts refusing a request with no
     * client fingerprint — so the cover art quietly stops appearing while
     * everything else keeps working. Built once, because rotating the ids per
     * request is what a real client never does.
     */
    private val superProperties: String by lazy {
        val fields = mapOf(
            "os" to if (DesktopPlatform.isWindows) "Windows" else "Linux",
            "browser" to "Discord Client",
            "device" to "",
            "system_locale" to Locale.getDefault().toString(),
            "client_version" to CLIENT_VERSION,
            "release_channel" to "stable",
            "client_build_number" to CLIENT_BUILD,
            "device_vendor_id" to UUID.randomUUID().toString(),
            "client_uuid" to UUID.randomUUID().toString(),
            "client_launch_id" to UUID.randomUUID().toString(),
        )
        val json = JsonObject(
            fields.mapValues { (key, value) ->
                if (key == "client_build_number") JsonPrimitive(value.toInt()) else JsonPrimitive(value)
            },
        )
        Base64.getEncoder().encodeToString(Json.encodeToString(JsonObject.serializer(), json).toByteArray())
    }

    private fun superProperties(): String = superProperties

    private fun watchUrl(song: Song) = "https://music.youtube.com/watch?v=${song.videoId}"

    private const val KEY_TOKEN = "discord_token"
    private const val KEY_USERNAME = "discord_username"
    private const val KEY_ENABLED = "discord_rpc_enabled"

    private const val APPLICATION_ID = "1411019391843172514"
    private const val PROJECT_URL = "https://github.com/kushagrasinghx/BitChord"
    private const val ART_PX = 480
    private const val FALLBACK_ART_URL =
        "https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/app/src/main/ic_launcher-playstore.png"
    private const val CLIENT_VERSION = "0.0.83"
    private const val CLIENT_BUILD = "352675"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) discord/0.0.83 Chrome/120.0.0.0 Electron/28.2.10 Safari/537.36"
}
