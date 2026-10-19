package com.music.bitchord.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopDiscordTransportTest {
    private fun read(channel: SocketChannel): Pair<Int, String> {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (header.hasRemaining()) check(channel.read(header) >= 0)
        header.flip()
        val opcode = header.int
        val body = ByteBuffer.allocate(header.int)
        while (body.hasRemaining()) check(channel.read(body) >= 0)
        return opcode to String(body.array(), Charsets.UTF_8)
    }

    private fun write(channel: SocketChannel, opcode: Int, payload: String) {
        val frame = ByteBuffer.wrap(discordFrame(opcode, payload))
        while (frame.hasRemaining()) channel.write(frame)
    }

    private fun withServer(serve: (SocketChannel) -> Unit, test: (DesktopDiscordIpc) -> Unit) {
        val server = ServerSocketChannel.open().bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val worker = Executors.newSingleThreadExecutor { Thread(it, "discord-protocol-test").apply { isDaemon = true } }
        var accepted: SocketChannel? = null
        try {
            val serving = worker.submit<Unit> {
                server.accept().use { accepted = it; serve(it) }
            }
            SocketChannel.open(server.localAddress).use { channel ->
                channel.configureBlocking(false)
                DesktopDiscordIpc(channel, null).use { test(it) }
            }
            serving.get(4, TimeUnit.SECONDS)
        } finally {
            accepted?.close()
            server.close()
            worker.shutdownNow()
        }
    }

    @Test fun `ready handshake and matching command reply are consumed with ping handling`() = withServer({ channel ->
        assertEquals(0, read(channel).first)
        write(channel, 1, """{"cmd":"DISPATCH","evt":"READY","data":{}}""")
        val request = Json.parseToJsonElement(read(channel).second).jsonObject
        assertEquals("SET_ACTIVITY", request["cmd"]!!.jsonPrimitive.content)
        write(channel, 3, "heartbeat")
        assertEquals(4 to "heartbeat", read(channel))
        write(channel, 1, """{"cmd":"SET_ACTIVITY","nonce":${request["nonce"]},"data":{}}""")
    }) { ipc ->
        ipc.handshake("test-app")
        ipc.setActivity(buildJsonObject { put("details", "Test track") })
    }

    @Test fun `a readable error frame is not a ready handshake`() = withServer({ channel ->
        read(channel)
        write(channel, 1, """{"cmd":"DISPATCH","evt":"ERROR","data":{}}""")
    }) { ipc -> assertFailsWith<IOException> { ipc.handshake("test-app") } }

    @Test fun `a rejected activity reply is a failed send`() = withServer({ channel ->
        read(channel)
        write(channel, 1, """{"cmd":"DISPATCH","evt":"READY","data":{}}""")
        val request = Json.parseToJsonElement(read(channel).second).jsonObject
        write(channel, 1, """{"cmd":"SET_ACTIVITY","evt":"ERROR","nonce":${request["nonce"]},"data":{"code":4000}}""")
    }) { ipc ->
        ipc.handshake("test-app")
        assertFailsWith<IOException> { ipc.setActivity(null) }
    }

    @Test fun `a silent channel cannot hold the session forever`() {
        val release = CountDownLatch(1)
        try {
            withServer({ channel -> read(channel); release.await(4, TimeUnit.SECONDS) }) { ipc ->
                assertFailsWith<SocketTimeoutException> { ipc.handshake("test-app") }
                release.countDown()
            }
        } finally { release.countDown() }
    }

    @Test fun `a different nonce is not treated as this activity acknowledgement`() = withServer({ channel ->
        read(channel)
        write(channel, 1, """{"cmd":"DISPATCH","evt":"READY","data":{}}""")
        val request = Json.parseToJsonElement(read(channel).second).jsonObject
        write(channel, 1, """{"cmd":"SET_ACTIVITY","nonce":"other-command","data":{}}""")
        write(channel, 3, "still waiting")
        assertEquals(4 to "still waiting", read(channel))
        write(channel, 1, """{"cmd":"SET_ACTIVITY","nonce":${request["nonce"]},"data":{}}""")
    }) { ipc ->
        ipc.handshake("test-app")
        ipc.setActivity(null)
    }

    @Test fun `a close frame before acknowledgement is a failed send`() = withServer({ channel ->
        read(channel)
        write(channel, 1, """{"cmd":"DISPATCH","evt":"READY","data":{}}""")
        read(channel)
        write(channel, 2, """{"code":1000}""")
    }) { ipc ->
        ipc.handshake("test-app")
        assertFailsWith<IOException> { ipc.setActivity(null) }
    }
}
