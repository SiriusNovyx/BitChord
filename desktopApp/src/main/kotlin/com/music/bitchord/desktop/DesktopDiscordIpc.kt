package com.music.bitchord.desktop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InterruptedIOException
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Discord's local Rich Presence socket.
 *
 * The official way for a desktop application to set a presence, and the one
 * Android has no equivalent of: the Discord client, running on the same
 * machine, listens on a named pipe, and an application connects to it with
 * nothing but its own application id. No account token, no login, no
 * impersonation — the presence is attributed to the application and the client
 * already knows who is signed in.
 *
 * Framing is a 32-bit little-endian opcode, a 32-bit little-endian length, and
 * that many bytes of JSON.
 */
internal class DesktopDiscordIpc internal constructor(
    private val channel: SocketChannel?,
    private val pipe: RandomAccessFile?,
) : DesktopDiscordConnection {

    init { channel?.configureBlocking(false) }

    companion object {
        private const val OP_HANDSHAKE = 0
        private const val OP_FRAME = 1
        private const val OP_CLOSE = 2
        private const val OP_PING = 3
        private const val OP_PONG = 4
        private const val MAX_REPLY_FRAMES = 32
        private val IO_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2)

        /**
         * Connects to the first Discord that answers.
         *
         * Discord numbers its sockets 0-9 so several clients — stable, PTB,
         * canary — can run at once, and sandboxed builds put theirs inside
         * their own runtime directory rather than at the top of it.
         */
        fun connect(applicationId: String): DesktopDiscordIpc? {
            for (candidate in candidates()) {
                val ipc = runCatching { open(candidate) }.getOrNull() ?: continue
                val ready = runCatching {
                    ipc.handshake(applicationId)
                    true
                }.getOrDefault(false)
                if (ready) return ipc
                ipc.close()
            }
            return null
        }

        private fun open(path: String): DesktopDiscordIpc =
            if (DesktopPlatform.isWindows) {
                DesktopDiscordIpc(null, RandomAccessFile(path, "rw"))
            } else {
                val socket = SocketChannel.open(StandardProtocolFamily.UNIX)
                try {
                    socket.configureBlocking(false)
                    val deadline = System.nanoTime() + IO_TIMEOUT_NANOS
                    if (!socket.connect(UnixDomainSocketAddress.of(path))) {
                        while (!socket.finishConnect()) awaitChannel(socket, SelectionKey.OP_CONNECT, deadline)
                    }
                } catch (failure: Exception) {
                    socket.close()
                    throw failure
                }
                DesktopDiscordIpc(socket, null)
            }

        private fun candidates(): List<String> {
            if (DesktopPlatform.isWindows) {
                return (0..9).map { """\\.\pipe\discord-ipc-$it""" }
            }
            val roots = listOfNotNull(
                System.getenv("XDG_RUNTIME_DIR"),
                System.getenv("TMPDIR"),
                "/tmp",
            ).distinct()
            // Flatpak and Snap builds keep their socket a level down.
            val nested = listOf("", "app/com.discordapp.Discord", "snap.discord", ".flatpak/dev.vencord.Vesktop/xdg-run")
            return roots.flatMap { root ->
                nested.flatMap { sub ->
                    val dir = if (sub.isEmpty()) root else "$root/$sub"
                    (0..9).map { "$dir/discord-ipc-$it" }
                }
            }.filter { File(it).exists() || DesktopPlatform.isWindows }
        }

        private fun remainingMillis(deadline: Long): Long {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("Discord IPC deadline exceeded")
            return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
        }

        private fun awaitChannel(socket: SocketChannel, operation: Int, deadline: Long) {
            Selector.open().use { selector ->
                socket.register(selector, operation)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Discord IPC interrupted")
                    if (selector.select(remainingMillis(deadline)) > 0) return
                }
            }
        }
    }

    internal fun handshake(applicationId: String) {
        val deadline = System.nanoTime() + IO_TIMEOUT_NANOS
        send(OP_HANDSHAKE, """{"v":1,"client_id":"$applicationId"}""", deadline)
        val response = read(deadline)?.let { Json.parseToJsonElement(it).jsonObject }
        if (response?.get("cmd")?.jsonPrimitive?.content != "DISPATCH" ||
            response["evt"]?.jsonPrimitive?.content != "READY") {
            throw IOException("Discord IPC did not become ready")
        }
    }

    /** The SET_ACTIVITY payload, as its own function so a test can read it. */
    internal fun activityPayload(activity: JsonObject?, nonce: String = UUID.randomUUID().toString()): String = buildString {
        append("""{"cmd":"SET_ACTIVITY","args":{"pid":""")
        append(ProcessHandle.current().pid())
        append(""","activity":""")
        append(activity?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: "null")
        append("""},"nonce":"$nonce"}""")
    }

    /** Publishes an activity, or takes the presence down when given null. */
    override fun setActivity(activity: JsonObject?) {
        val nonce = UUID.randomUUID().toString()
        val deadline = System.nanoTime() + IO_TIMEOUT_NANOS
        send(OP_FRAME, activityPayload(activity, nonce), deadline)
        repeat(MAX_REPLY_FRAMES) {
            val response = read(deadline)?.let { Json.parseToJsonElement(it).jsonObject }
                ?: throw IOException("Discord IPC closed before acknowledging activity")
            if (response["nonce"]?.jsonPrimitive?.content == nonce) {
                if (response["evt"]?.jsonPrimitive?.content == "ERROR") {
                    throw IOException("Discord rejected the activity")
                }
                if (response["cmd"]?.jsonPrimitive?.content == "SET_ACTIVITY") return
            }
        }
        throw IOException("Discord did not acknowledge the activity")
    }

    private fun send(opcode: Int, payload: String, deadline: Long = System.nanoTime() + IO_TIMEOUT_NANOS) {
        val frame = ByteBuffer.wrap(discordFrame(opcode, payload))
        frame.position(frame.limit())
        frame.flip()
        if (channel != null) {
            while (frame.hasRemaining()) {
                if (channel.write(frame) == 0) awaitChannel(channel, SelectionKey.OP_WRITE, deadline)
            }
        } else {
            withDiscordPipeDeadline(remainingMillis(deadline)) { pipe!!.write(frame.array()) }
        }
    }

    private fun read(deadline: Long): String? {
        repeat(MAX_REPLY_FRAMES) {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            if (!fill(header, deadline)) return null
            header.flip()
            val opcode = header.int
            val length = header.int
            if (length < 0 || length > 1 shl 20) throw IOException("Invalid Discord IPC frame size")
            val body = ByteBuffer.allocate(length)
            if (!fill(body, deadline)) return null
            val payload = String(body.array(), Charsets.UTF_8)
            when (opcode) {
                OP_FRAME -> return payload
                OP_CLOSE -> return null
                OP_PING -> send(OP_PONG, payload, deadline)
                OP_PONG -> Unit
                else -> throw IOException("Invalid Discord IPC opcode")
            }
        }
        throw IOException("Discord IPC reply limit exceeded")
    }

    private fun fill(buffer: ByteBuffer, deadline: Long): Boolean {
        if (channel != null) {
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) return false
                if (count == 0) awaitChannel(channel, SelectionKey.OP_READ, deadline)
            }
            return true
        }
        val bytes = ByteArray(buffer.remaining())
        withDiscordPipeDeadline(remainingMillis(deadline)) { pipe!!.readFully(bytes) }
        buffer.put(bytes)
        return true
    }

    override fun close() {
        runCatching { send(OP_CLOSE, "{}") }
        runCatching { channel?.close() }
        runCatching { pipe?.close() }
    }
}

/**
 * One framed message: a little-endian opcode, a little-endian length, and the
 * JSON. Discord drops the connection on a frame it cannot parse, with nothing
 * said about why, so the byte order is worth pinning down in a test.
 */
internal fun discordFrame(opcode: Int, payload: String): ByteArray {
    val body = payload.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(8 + body.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(opcode)
        .putInt(body.size)
        .put(body)
        .array()
}
