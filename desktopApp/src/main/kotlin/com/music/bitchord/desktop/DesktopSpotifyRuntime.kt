package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object DesktopSpotifyRuntime {
    private val active = ConcurrentHashMap.newKeySet<Process>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Spotify authentication deadline").apply { isDaemon = true }
    }
    fun disconnect() { active.toList().forEach { it.destroyForcibly() } }
    private val arch = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64", "x64" -> "x64"
        else -> "unsupported"
    }
    private val resource = "/native/spotify/windows-$arch/bitchord-spotify.exe"
    val available: Boolean get() = DesktopPlatform.isWindows && javaClass.getResource(resource) != null

    private fun helper(): Path {
        check(available) { "Native Spotify playback is unavailable in this build." }
        val bytes = javaClass.getResourceAsStream(resource)!!.use { it.readAllBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = DesktopStorage.directory("native-cache/spotify") {
            Path.of(System.getProperty("user.home"), ".cache", "bitchord", "spotify")
        }.resolve(digest)
        Files.createDirectories(directory)
        val file = directory.resolve("bitchord-spotify.exe")
        if (!Files.exists(file) || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)),
                MessageDigest.getInstance("SHA-256").digest(bytes))) {
            val partial = Files.createTempFile(directory, "helper-", ".tmp")
            try {
                Files.write(partial, bytes)
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(partial) }
        }
        RandomAccessFile(file.toFile(), "r").use {
            it.seek(0x3c)
            val offset = Integer.reverseBytes(it.readInt())
            require(offset >= 0x40 && offset.toLong() + 6 <= it.length())
            it.seek(offset.toLong())
            require(Integer.reverseBytes(it.readInt()) == 0x4550)
            val machine = java.lang.Short.reverseBytes(it.readShort()).toInt() and 0xffff
            require(machine == if (arch == "arm64") 0xaa64 else 0x8664) { "Spotify helper architecture mismatch" }
        }
        return file
    }

    private fun payload(token: String, account: SpotifyProfile, trackId: String? = null, position: Long = 0): String {
        val username = checkNotNull(account.playbackUsername) { "Reconnect your Spotify library to verify playback." }
        require(username.isNotBlank() && username.length <= 256 && username.none(Char::isISOControl))
        return buildJsonObject {
            put("version", 1)
            put("client_id", DesktopSpotifyPlayback.CLIENT_ID)
            put("token", token)
            put("username", username)
            if (trackId == null) put("authenticate_only", true) else {
                put("track_id", trackId)
                put("position_ms", position.coerceIn(0, Int.MAX_VALUE.toLong()))
            }
        }.toString() + "\n"
    }

    private fun launch(payload: String): Process {
        val executable = helper()
        val temporary = DesktopStorage.directory("tmp/spotify") {
            Path.of(System.getProperty("java.io.tmpdir"), "bitchord-spotify")
        }
        Files.createDirectories(temporary)
        val process = ProcessBuilder(executable.toString()).directory(temporary.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                environment()["TEMP"] = temporary.toString()
                environment()["TMP"] = temporary.toString()
            }.start()
        active.add(process)
        process.onExit().thenRun { active.remove(process) }
        try {
            process.outputStream.write(payload.toByteArray())
            process.outputStream.flush()
            return process
        } catch (_: Exception) {
            process.destroyForcibly()
            throw SpotifyPcmException()
        }
    }

    suspend fun authenticate(tokens: SpotifyTokens, account: SpotifyProfile) {
        val process = launch(payload(tokens.accessToken, account))
        val deadline = deadlines.schedule({ process.destroyForcibly() }, 30, TimeUnit.SECONDS)
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { process.destroyForcibly() }
            Thread({
                try {
                    check(SpotifyPcmProtocol(process.inputStream).next() == null)
                    continuation.resume(Unit)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure as? SpotifyPcmException ?: SpotifyPcmException())
                } finally {
                    deadline.cancel(false)
                    runCatching { process.outputStream.close() }
                    process.destroyForcibly()
                }
            }, "Spotify account verification").apply { isDaemon = true }.start()
        }
    }

    fun source(song: Song, startAtMs: Long): SpotifyPcmSource {
        val trackId = song.videoId.removePrefix(SPOTIFY_NATIVE_PREFIX)
        require(song.isNativeSpotify && trackId.matches(Regex("[A-Za-z0-9]{22}")))
        return SpotifyPcmSource(startAtMs) { position ->
            val token = runBlocking { DesktopSpotifyPlayback.accessToken() }
            val account = checkNotNull(DesktopSpotifyAccount.session.profile.value)
            launch(payload(token, account, trackId, position))
        }
    }
}
