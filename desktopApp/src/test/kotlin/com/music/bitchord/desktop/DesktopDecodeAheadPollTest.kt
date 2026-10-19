package com.music.bitchord.desktop

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopDecodeAheadPollTest {
    private fun ahead(source: DesktopSampleSource) = DesktopDecodeAhead(
        source, DesktopPcmFormat(44_100, 2, 4, isFloat = true), null, null, aheadSamples = 4,
    )

    private class Stalled : DesktopSampleSource {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancellations = AtomicInteger()
        @Volatile var next = 0f
        @Volatile var closingThread: Thread? = null
        override val sampleCount = 4
        override fun readSamples(): FloatArray {
            val old = next
            entered.countDown()
            release.await()
            return FloatArray(4) { old }
        }
        override fun cancelPendingRead() { cancellations.incrementAndGet(); release.countDown() }
        override fun seek(micros: Long): Boolean { next = micros.toFloat(); return true }
        override fun close() { closingThread = Thread.currentThread(); closed.countDown() }
    }

    @Test fun `a stalled source yields waiting without blocking the command consumer`() {
        val source = Stalled()
        val reader = ahead(source)
        try {
            assertTrue(source.entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertSame(DesktopDecodedRead.Waiting, reader.poll(10))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
            assertEquals(0, reader.sampleCount)
            // The source remains blocked, so this proves the consumer actually yielded.
            assertEquals(1L, source.release.count)
        } finally { reader.close() }
        assertTrue(source.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun `seek cancels the old read and cannot commit its old block`() {
        val source = Stalled()
        val reader = ahead(source)
        try {
            assertTrue(source.entered.await(2, TimeUnit.SECONDS))
            reader.seek(700_000)
            val next = assertIs<DesktopDecodedRead.Data>(reader.poll(1_000))
            assertEquals(700_000f, next.samples.first())
            assertTrue(source.cancellations.get() >= 1)
        } finally { reader.close() }
    }

    @Test fun `close cancels a blocked transport but frees its decoder on the worker`() {
        val source = Stalled()
        val reader = ahead(source)
        assertTrue(source.entered.await(2, TimeUnit.SECONDS))
        reader.close()
        assertTrue(source.closed.await(2, TimeUnit.SECONDS))
        assertTrue(source.closingThread !== Thread.currentThread())
        assertSame(DesktopDecodedRead.End, reader.poll(10))
    }

    @Test fun `read failure remains distinct from a genuine end of stream`() {
        val failure = IOException("Synthetic transport failure")
        val source = object : DesktopSampleSource {
            override val sampleCount = 0
            override fun readSamples(): FloatArray? = throw failure
            override fun seek(micros: Long) = true
            override fun close() = Unit
        }
        val reader = ahead(source)
        try { assertSame(failure, assertIs<DesktopDecodedRead.Failed>(reader.poll(1_000)).cause) }
        finally { reader.close() }
        val ended = ahead(object : DesktopSampleSource {
            override val sampleCount = 0
            override fun readSamples(): FloatArray? = null
            override fun seek(micros: Long) = true
            override fun close() = Unit
        })
        try { assertSame(DesktopDecodedRead.End, ended.poll(1_000)) } finally { ended.close() }
    }

    @Test fun `failed seek reports failure instead of hanging or playing old audio`() {
        for (throws in listOf(false, true)) {
            val reader = ahead(object : DesktopSampleSource {
                override val sampleCount = 0
                override fun readSamples(): FloatArray? = null
                override fun seek(micros: Long): Boolean {
                    if (throws) throw IOException("Synthetic seek failure")
                    return false
                }
                override fun close() = Unit
            })
            try {
                assertSame(DesktopDecodedRead.End, reader.poll(1_000))
                reader.seek(200_000)
                assertIs<DesktopDecodedRead.Failed>(reader.poll(1_000))
            } finally { reader.close() }
        }
    }
}
