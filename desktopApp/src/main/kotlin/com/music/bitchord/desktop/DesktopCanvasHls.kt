package com.music.bitchord.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object DesktopCanvasHls {
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val CACHE_MILLIS = 5 * 60 * 1000L
    private val client by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    private data class Key(val url: String, val edge: Int, val headers: Map<String, String>)
    private data class Cached(val url: String, val at: Long)
    private val cache = object : LinkedHashMap<Key, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>) = size > 64
    }
    private val attributes = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun resolve(url: String, edge: Int, headers: Map<String, String>): String {
        val source = runCatching { URI(url) }.getOrNull() ?: return url
        if (source.scheme !in setOf("http", "https") || !source.path.orEmpty().endsWith(".m3u8", true)) return url
        val key = Key(url, edge, headers.toMap())
        val now = System.nanoTime() / 1_000_000L
        synchronized(cache) { cache[key]?.let { if (now - it.at < CACHE_MILLIS) return it.url } }
        return runCatching {
            val request = HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(15))
            headers.forEach { (name, value) -> request.header(name, value) }
            val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            val selected = response.body().use { body ->
                check(response.statusCode() in 200..299)
                val bytes = body.readNBytes(MAX_MANIFEST_BYTES + 1)
                check(bytes.size <= MAX_MANIFEST_BYTES)
                select(response.uri(), bytes.toString(Charsets.UTF_8), edge)
            }
            if (selected != null) synchronized(cache) { cache[key] = Cached(selected, now) }
            selected ?: url
        }.getOrDefault(url)
    }

    internal fun select(source: URI, manifest: String, edge: Int): String? {
        if (!manifest.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")) return null
        data class Variant(val url: String, val edge: Int, val bandwidth: Long)
        val variants = mutableListOf<Variant>()
        var pending: Map<String, String>? = null
        for (raw in manifest.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = attributes.findAll(line.substringAfter(':')).associate {
                    it.groupValues[1] to it.groupValues[2].trim('"')
                }
            } else if (line.isNotEmpty() && !line.startsWith('#')) {
                val fields = pending ?: continue
                pending = null
                val size = fields["RESOLUTION"]?.split('x')?.map { it.toIntOrNull() } ?: continue
                if (size.size != 2 || size.any { it == null || it <= 0 }) continue
                val target = runCatching { source.resolve(line) }.getOrNull() ?: continue
                if (target.scheme !in setOf("http", "https")) continue
                variants += Variant(target.toString(), maxOf(size[0]!!, size[1]!!), fields["BANDWIDTH"]?.toLongOrNull() ?: 0L)
            }
        }
        val adequate = variants.filter { it.edge >= edge }
        val chosenEdge = adequate.minOfOrNull { it.edge } ?: variants.maxOfOrNull { it.edge } ?: return null
        return variants.filter { it.edge == chosenEdge }.maxByOrNull { it.bandwidth }?.url
    }
}
