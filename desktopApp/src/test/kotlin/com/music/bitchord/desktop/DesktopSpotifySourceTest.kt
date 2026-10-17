package com.music.bitchord.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSpotifySourceTest {
    private class Child(value: Float) : Process() {
        var destroyed = false
        private val bytes = ByteBuffer.allocate(16 + 5 + 5 + 8 + 5).order(ByteOrder.LITTLE_ENDIAN)
            .put("BCSP".toByteArray()).putInt(1).putInt(44100).putInt(2)
            .put(0).putInt(0).put(1).putInt(2).putFloat(value).putFloat(-value)
            .put(2).putInt(0).array()
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed = true }
    }

    @Test fun `seek retires the old PCM process before reading the new position`() {
        val children = mutableListOf<Child>()
        val positions = mutableListOf<Long>()
        val source = SpotifyPcmSource(0, { position ->
            positions += position
            Child(if (position == 0L) 0.25f else 0.5f).also(children::add)
        })
        source.open()
        assertContentEquals(floatArrayOf(0.25f, -0.25f), source.readSamples())
        assertTrue(source.seek(62_000_000))
        assertTrue(children.first().destroyed)
        var block: FloatArray? = floatArrayOf()
        repeat(10) { if (block?.isEmpty() == true) block = source.readSamples() }
        assertContentEquals(floatArrayOf(0.5f, -0.5f), block)
        assertEquals(listOf(0L, 62_000L), positions)
        source.close()
        assertTrue(children.all { it.destroyed })
    }
}
