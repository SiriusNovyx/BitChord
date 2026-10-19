package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopRangeRecoveryTest {
    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/media", handler)
            start()
        }

    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}/media"

    @Test fun `short outage retries exactly the same bytes without skipping or duplicating`() {
        val attempts = AtomicInteger()
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val server = server { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            if (attempts.incrementAndGet() == 1) exchange.sendResponseHeaders(503, -1)
            else {
                exchange.responseHeaders.add("Content-Range", "bytes 0-3/4")
                exchange.sendResponseHeaders(206, 4)
                exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        try {
            val stream = DesktopRangeStream(url(server), windowBytes = 4)
            val result = ByteArray(4)
            assertEquals(4, stream.read(result, 4))
            assertContentEquals(bytes, result)
            assertEquals(-1, stream.read(result, 4))
            assertEquals(listOf("bytes=0-3", "bytes=0-3"), ranges)
        } finally { server.stop(0) }
    }

    @Test fun `sustained outage fails after bounded retries rather than reporting end of song`() {
        val attempts = AtomicInteger()
        val server = server { exchange ->
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        try {
            val stream = DesktopRangeStream(url(server), windowBytes = 4)
            assertFailsWith<IOException> { stream.read(ByteArray(4), 4) }
            assertEquals(3, attempts.get())
            assertEquals(0L, stream.position())
        } finally { server.stop(0) }
    }

    @Test fun `expired url is a failure rather than normal completion`() {
        val attempts = AtomicInteger()
        val server = server { exchange ->
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        try {
            val stream = DesktopRangeStream(url(server), windowBytes = 4)
            assertFailsWith<IOException> { stream.read(ByteArray(4), 4) }
            assertEquals(1, attempts.get())
        } finally { server.stop(0) }
    }

    @Test fun `cancellation unblocks the request and a new seek can read again`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/media") { exchange ->
            if (attempts.incrementAndGet() == 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            runCatching {
                exchange.responseHeaders.add("Content-Range", "bytes 0-3/4")
                exchange.sendResponseHeaders(206, 4)
                exchange.responseBody.write(byteArrayOf(1, 2, 3, 4))
            }
            exchange.close()
        }
        server.start()
        val stream = DesktopRangeStream(url(server), windowBytes = 4)
        val reading = executor.submit<Boolean> {
            try { stream.read(ByteArray(4), 4); false } catch (_: IOException) { true }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stream.cancelPendingRead()
            assertTrue(reading.get(1, TimeUnit.SECONDS))
            stream.resumeAfterCancellation()
            stream.seek(2)
            val result = ByteArray(2)
            assertEquals(2, stream.read(result, 2))
            assertContentEquals(byteArrayOf(3, 4), result)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun `a long rate limit does not retry ahead of the server deadline`() {
        val attempts = AtomicInteger()
        val server = server { exchange ->
            attempts.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "60")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        try {
            assertEquals(429, assertFailsWith<DesktopStreamReadException> {
                DesktopRangeStream(url(server)).read(ByteArray(4), 4)
            }.statusCode)
            assertEquals(1, attempts.get())
        } finally { server.stop(0) }
    }

    @Test fun `an unknown total length does not turn the first window into end of file`() {
        val attempts = AtomicInteger()
        val server = server { exchange ->
            val start = if (attempts.getAndIncrement() == 0) 0 else 4
            exchange.responseHeaders.add("Content-Range", "bytes $start-${start + 3}/*")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.write(ByteArray(4) { (start + it).toByte() })
            exchange.close()
        }
        try {
            val stream = DesktopRangeStream(url(server), windowBytes = 4)
            val first = ByteArray(4)
            val second = ByteArray(4)
            assertEquals(4, stream.read(first, 4))
            assertEquals(-1L, stream.length)
            assertEquals(4, stream.read(second, 4))
            assertContentEquals(byteArrayOf(4, 5, 6, 7), second)
        } finally { server.stop(0) }
    }

    @Test fun `a malformed or premature range response is a failure`() {
        for (status in listOf(206, 416)) {
            val server = server { exchange ->
                exchange.responseHeaders.add("Content-Range", if (status == 206) "bytes 0-3/8" else "bytes */8")
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            try {
                assertFailsWith<IOException> { DesktopRangeStream(url(server), windowBytes = 4).read(ByteArray(4), 4) }
            } finally { server.stop(0) }
        }
    }
}
