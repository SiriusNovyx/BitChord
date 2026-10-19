package com.music.bitchord.desktop

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDiscordPipeDeadlineTest {
    private fun withPipe(test: (RandomAccessFile, WinNT.HANDLE) -> Unit) {
        if (!Platform.isWindows()) return
        val kernel = Kernel32.INSTANCE
        val name = "\\\\.\\pipe\\bitchord-discord-test-${UUID.randomUUID()}"
        val server = kernel.CreateNamedPipe(name, 3, 0, 1, 4096, 4096, 0, null)
        check(server != WinBase.INVALID_HANDLE_VALUE)
        val acceptor = Executors.newSingleThreadExecutor { Thread(it, "discord-test-accept").apply { isDaemon = true } }
        var client: RandomAccessFile? = null
        try {
            val accepted = acceptor.submit<Boolean> {
                kernel.ConnectNamedPipe(server, null) || kernel.GetLastError() == 535
            }
            client = RandomAccessFile(name, "rw")
            assertTrue(accepted.get(2, TimeUnit.SECONDS))
            test(client, server)
        } finally {
            kernel.DisconnectNamedPipe(server)
            kernel.CloseHandle(server)
            client?.close()
            acceptor.shutdownNow()
        }
    }

    @Test fun `silent Windows pipe is cancelled within the read deadline`() = withPipe { client, _ ->
        val worker = Executors.newSingleThreadExecutor { Thread(it, "discord-test-read").apply { isDaemon = true } }
        try {
            val result = worker.submit<Throwable?> {
                runCatching { withDiscordPipeDeadline(150) { client.readInt() } }.exceptionOrNull()
            }
            assertIs<SocketTimeoutException>(result.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun `completed deadline cannot cancel a later read on the same worker`() = withPipe { client, server ->
        val worker = Executors.newSingleThreadExecutor { Thread(it, "discord-test-reuse").apply { isDaemon = true } }
        try {
            val entered = java.util.concurrent.CountDownLatch(1)
            val result = worker.submit<Int> {
                assertEquals(7, withDiscordPipeDeadline(100) { 7 })
                entered.countDown()
                client.readInt()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertFalse(result.isDone)
            val count = IntByReference()
            assertTrue(Kernel32.INSTANCE.WriteFile(server, byteArrayOf(0, 0, 0, 42), 4, count, null))
            assertEquals(42, result.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun `real Windows pipe completes handshake and repeated acknowledged updates`() = withPipe { client, server ->
        val kernel = Kernel32.INSTANCE
        val worker = Executors.newSingleThreadExecutor { Thread(it, "discord-pipe-protocol").apply { isDaemon = true } }
        fun readBytes(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val part = ByteArray(size - offset)
                val count = IntByReference()
                check(kernel.ReadFile(server, part, part.size, count, null))
                check(count.value > 0)
                part.copyInto(result, offset, 0, count.value)
                offset += count.value
            }
            return result
        }
        fun receive(): Pair<Int, String> {
            val header = ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN)
            return header.int to String(readBytes(header.int), Charsets.UTF_8)
        }
        fun send(payload: String) {
            val bytes = discordFrame(1, payload)
            val count = IntByReference()
            check(kernel.WriteFile(server, bytes, bytes.size, count, null))
            check(count.value == bytes.size)
        }
        try {
            val serving = worker.submit<Boolean> {
                assertEquals(0, receive().first)
                send("""{"cmd":"DISPATCH","evt":"READY","data":{}}""")
                repeat(16) {
                    val (opcode, body) = receive()
                    assertEquals(1, opcode)
                    val request = Json.parseToJsonElement(body).jsonObject
                    send("""{"cmd":"SET_ACTIVITY","nonce":${request["nonce"]},"data":{}}""")
                }
                true
            }
            DesktopDiscordIpc(null, client).use { ipc ->
                ipc.handshake("test-app")
                repeat(16) { index -> ipc.setActivity(buildJsonObject { put("details", "Track $index") }) }
            }
            assertTrue(serving.get(3, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
}
