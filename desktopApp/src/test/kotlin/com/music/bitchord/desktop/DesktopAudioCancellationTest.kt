package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopAudioCancellationTest {
    @Test fun `native FFmpeg open can be cancelled before the network timeout`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { exchange ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val worker = Executors.newSingleThreadExecutor()
        val decoder = DesktopAudioDecoder()
        val opening = worker.submit<Boolean> {
            try {
                decoder.open("http://127.0.0.1:${server.address.port}/media",
                    requested = DesktopPcmFormat(44_100, 2, 4, isFloat = true)).isFailure
            } finally { decoder.close() }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            decoder.cancelPendingRead()
            assertTrue(opening.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            server.stop(0)
            worker.shutdownNow()
            worker.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test fun `native windowed decoder reports a transport failure and can seek after cancellation`() {
        val rate = 48_000
        val dataSize = rate * 4 * 20
        val media = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4)
            putShort(4); putShort(16); put("data".toByteArray()); putInt(dataSize)
            while (remaining() >= 2) putShort(1_000)
        }.array()
        val offline = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media") { exchange ->
            if (offline.get()) exchange.sendResponseHeaders(503, -1)
            else {
                val span = Regex("bytes=(\\d+)-(\\d+)").find(exchange.requestHeaders.getFirst("Range"))!!
                val from = span.groupValues[1].toInt()
                val to = minOf(span.groupValues[2].toInt(), media.lastIndex)
                if (from >= media.size) {
                    exchange.responseHeaders.add("Content-Range", "bytes */${media.size}")
                    exchange.sendResponseHeaders(416, -1)
                } else {
                    exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${media.size}")
                    exchange.sendResponseHeaders(206, (to - from + 1).toLong())
                    exchange.responseBody.write(media, from, to - from + 1)
                }
            }
            exchange.close()
        }
        server.start()
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open("http://127.0.0.1:${server.address.port}/media", windowed = true,
                requested = DesktopPcmFormat(rate, 2, 4, isFloat = true)).getOrThrow()
            offline.set(true)
            assertFailsWith<IOException> { while (decoder.readSamples() != null) {} }
            offline.set(false)
            decoder.cancelPendingRead()
            decoder.prepareSeek()
            assertTrue(decoder.seek(2_000_000))
            assertNotNull(decoder.readSamples())
            assertTrue(kotlin.math.abs(decoder.positionUs - 2_000_000) < 200_000)
        } finally { decoder.close(); server.stop(0) }
    }
}
