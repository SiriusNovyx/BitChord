package com.music.bitchord.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSpotifyPcmTest {
    private fun bytes(vararg frames: Pair<Int, FloatArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("BCSP".toByteArray())
        fun number(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        number(1); number(44_100); number(2)
        frames.forEach { (kind, values) ->
            out.write(kind); number(values.size)
            values.forEach { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(it).array()) }
        }
        return out.toByteArray()
    }

    @Test fun `PCM preserves interleaving and explicit end`() {
        val protocol = SpotifyPcmProtocol(ByteArrayInputStream(bytes(0 to floatArrayOf(),
            1 to floatArrayOf(0.25f, -0.5f), 2 to floatArrayOf())))
        assertContentEquals(floatArrayOf(0.25f, -0.5f), protocol.next())
        assertNull(protocol.next())
    }

    @Test fun `unexpected EOF is failure instead of a completed song`() {
        val protocol = SpotifyPcmProtocol(ByteArrayInputStream(bytes(0 to floatArrayOf())))
        assertFailsWith<SpotifyPcmException> { protocol.next() }
    }

    @Test fun `authentication success requires ready and explicit completion without audio`() {
        assertNull(SpotifyPcmProtocol(ByteArrayInputStream(bytes(0 to floatArrayOf(), 2 to floatArrayOf()))).next())
        assertFailsWith<SpotifyPcmException> {
            SpotifyPcmProtocol(ByteArrayInputStream(bytes(2 to floatArrayOf()))).next()
        }
    }

    @Test fun `native error reports retain a bounded diagnostic code`() {
        val data = bytes(0 to floatArrayOf(), 3 to floatArrayOf()).also {
            ByteBuffer.wrap(it, it.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(116)
        }
        val failure = assertFailsWith<SpotifyPcmException> { SpotifyPcmProtocol(ByteArrayInputStream(data)).next() }
        assertEquals(116, failure.code)
    }

    @Test fun `untrusted native protocol rejects wrong architecture format and invalid frames`() {
        val badHeader = bytes().also { it[8] = 0 }
        assertFailsWith<SpotifyPcmException> { SpotifyPcmProtocol(ByteArrayInputStream(badHeader)).next() }
        assertFailsWith<SpotifyPcmException> {
            SpotifyPcmProtocol(ByteArrayInputStream(bytes(1 to FloatArray(8194)))).next()
        }
        assertFailsWith<SpotifyPcmException> {
            SpotifyPcmProtocol(ByteArrayInputStream(bytes(1 to floatArrayOf(Float.NaN, 0f)))).next()
        }
    }
}
