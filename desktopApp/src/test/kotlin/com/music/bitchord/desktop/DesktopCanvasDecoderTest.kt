package com.music.bitchord.desktop

import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCanvasDecoderTest {
    @Test(timeout = 10_000)
    fun nativeVideoDecodesAndLoopsWithCorrectPixelChannels() {
        val clip = Files.createTempFile("bitchord-canvas-test-", ".mp4")
        val decoder = DesktopCanvasDecoder()
        try {
            requireNotNull(javaClass.getResourceAsStream("/canvas/red.mp4")).use { source ->
                Files.newOutputStream(clip).use(source::copyTo)
            }
            decoder.open(clip.toString()).getOrThrow()
            assertEquals(64, decoder.width)
            assertEquals(64, decoder.height)
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            // The fixture has six frames; crossing EOF also checks the native seek/flush path.
            repeat(20) {
                assertTrue(decoder.nextFrame(pixels), "frame $it was unavailable")
                assertTrue((pixels[2].toInt() and 255) > 240, "red channel is missing")
                assertTrue((pixels[0].toInt() and 255) < 15, "blue channel is swapped")
                assertEquals(255, pixels[3].toInt() and 255)
            }
        } finally {
            decoder.close()
            Files.deleteIfExists(clip)
        }
    }
}
