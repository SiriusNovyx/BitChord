package com.music.bitchord.desktop

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAudioWriteTest {
    private val format = DesktopPcmFormat(48_000, 2, 4, isFloat = true)

    @Test fun `a partial write retains every remaining frame for the next pump iteration`() {
        val pcm = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val pending = DesktopAudioWrite(pcm, pcm.size, format)
        val heard = mutableListOf<Float>()
        assertEquals(1, pending.write { samples, _ ->
            heard += samples.take(2)
            8
        })
        assertFalse(pending.complete)
        assertEquals(0, pending.write { _, _ -> 0 })
        assertFalse(pending.complete)
        assertEquals(2, pending.write { samples, count ->
            heard += samples.take(count)
            count * 4
        })
        assertTrue(pending.complete)
        assertContentEquals(pcm, heard.toFloatArray())
    }

    @Test fun `repeated zero writes fail instead of inventing output frames or retrying forever`() {
        var now = 0L
        val pending = DesktopAudioWrite(FloatArray(4), 4, format, nowNanos = { now })
        assertEquals(0, pending.write { _, _ -> 0 })
        now = 6_000_000_000L
        assertFailsWith<IOException> { pending.write { _, _ -> 0 } }
        assertFalse(pending.complete)
    }

    @Test fun `the output contract rejects an incomplete PCM frame`() {
        val pending = DesktopAudioWrite(FloatArray(4), 4, format)
        assertFailsWith<IOException> { pending.write { _, _ -> 4 } }
    }

    @Test fun `a user pause does not count toward the output failure timeout`() {
        var now = 0L
        val pending = DesktopAudioWrite(FloatArray(4), 4, format, nowNanos = { now })
        assertEquals(1, pending.write { _, _ -> 8 })
        now = 10_000_000_000L
        pending.resetDeadline()
        assertEquals(0, pending.write { _, _ -> 0 })
        now += 100_000_000L
        assertEquals(1, pending.write { _, _ -> 8 })
        assertTrue(pending.complete)
    }
}
