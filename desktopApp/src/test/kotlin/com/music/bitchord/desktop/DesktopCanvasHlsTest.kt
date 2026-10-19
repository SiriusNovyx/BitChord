package com.music.bitchord.desktop

import java.net.URI
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCanvasHlsTest {
    private val master = URI("https://example.test/art/master.m3u8?token=public")

    @Test fun selectsSmallestRenditionThatCoversTheRenderedSize() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=2160x2160
            large.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=768x768,CODECS="hvc1.2.4,mp4a.40.2"
            /cdn/adequate.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=486x486
            small.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=768x768
            lower-quality.m3u8
        """.trimIndent()
        assertEquals("https://example.test/cdn/adequate.m3u8", DesktopCanvasHls.select(master, manifest, 540))
    }

    @Test fun usesLargestAvailableRenditionWhenAllAreSmaller() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=240x240
            small.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=200,RESOLUTION=360x360
            larger.m3u8
        """.trimIndent()
        assertEquals("https://example.test/art/larger.m3u8", DesktopCanvasHls.select(master, manifest, 540))
    }

    @Test fun ignoresUnsupportedVariantsAndLeavesMediaPlaylistsAlone() {
        val malformed = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=300,RESOLUTION=768x768
            file:///private/clip.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=200
            audio.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100,RESOLUTION=768x768,URI="iframes.m3u8"
        """.trimIndent()
        assertNull(DesktopCanvasHls.select(master, malformed, 540))
        assertNull(DesktopCanvasHls.select(master, "#EXTM3U\n#EXTINF:5,\nsegment.ts", 540))
        assertNull(DesktopCanvasHls.select(master, "<html>not a playlist</html>", 540))
    }

    @Test fun resolvesRedirectRelativeUrlsAndCachesTheSelectedManifest() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        server.createContext("/redirect.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "/cdn/master.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/master.m3u8") { exchange ->
            requests.incrementAndGet()
            val content = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=768x768\nfit.m3u8\n".toByteArray()
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/missing.m3u8") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            repeat(2) {
                assertEquals("$base/cdn/fit.m3u8", DesktopCanvasHls.resolve("$base/redirect.m3u8", 540, emptyMap()))
            }
            assertEquals(1, requests.get())
            assertEquals("$base/missing.m3u8", DesktopCanvasHls.resolve("$base/missing.m3u8", 540, emptyMap()))
        } finally {
            server.stop(0)
        }
    }
}
