package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.StreamResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class DesktopStreamReadException(val statusCode: Int? = null, cause: Throwable? = null) :
    IOException(statusCode?.let { "Audio stream request failed (HTTP $it)" } ?: "Audio stream request failed", cause)

/** Reads a remote file in bounded windows rather than as one open-ended request. */
internal class DesktopRangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
) {

    /** Total length in bytes, or -1 until the first response has stated one. */
    var length: Long = -1L
        private set

    private var position = 0L
    private var window = ByteArray(0)

    /** Where [window] starts in the file; -1 when nothing is held. */
    private var windowStart = -1L
    private val requestLock = Object()
    private var cancelled = false
    private var pendingRequest: CompletableFuture<HttpResponse<ByteArray>>? = null

    /** Called from the command thread; decoder/native cleanup stays with its owner. */
    fun cancelPendingRead() = synchronized(requestLock) {
        cancelled = true
        pendingRequest?.cancel(true)
        requestLock.notifyAll()
    }

    /** Only the decoder worker resets cancellation, before starting the requested seek. */
    fun resumeAfterCancellation() = synchronized(requestLock) { cancelled = false }

    private fun checkCancellation() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Audio read cancelled")
    }

    /** Where the next read will come from. */
    fun position(): Long = position

    fun seek(offset: Long) {
        position = offset.coerceAtLeast(0)
    }

    /**
     * Fills up to [count] bytes at the current position, returning how many — or -1 at the end of
     * the file.
     */
    fun read(into: ByteArray, count: Int): Int {
        synchronized(requestLock) { checkCancellation() }
        if (count <= 0) return 0
        if (length >= 0 && position >= length) return -1
        if (!holds(position) && !fetchWindowFor(position)) return -1
        val offset = (position - windowStart).toInt()
        val available = window.size - offset
        if (available <= 0) return -1
        val taken = minOf(count, available)
        System.arraycopy(window, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun holds(at: Long): Boolean =
        windowStart >= 0 && at >= windowStart && at < windowStart + window.size

    /** Fetches the window containing [at]. */
    private fun fetchWindowFor(at: Long): Boolean {
        val start = at - (at % windowBytes)
        val end = start + windowBytes - 1
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Range", "bytes=$start-$end")
            .header("Accept", "*/*")
            // Anything else would arrive compressed and the byte offsets the caller is counting in
            // would stop meaning anything.
            .header("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> runCatching { request.header(name, value) } }

        val response = fetchWithRetry(request.GET().build())
        if (response.statusCode() == 416) {
            val total = response.headers().firstValue("content-range").orElse("")
                .substringAfterLast('/').toLongOrNull()
            if (total != null && at >= total) { length = total; return false }
        }
        if (response.statusCode() !in 200..299) {
            // A URL cleared before playback has been refused mid-track: tell the resolver, as the
            // phone's ChunkedDataSource does, so it forgets it and benches the client that minted it.
            StreamResolver.onPlaybackRefused(url, response.statusCode())
            throw DesktopStreamReadException(response.statusCode())
        }

        val body = response.body()
        // A server that honoured the range says where the bytes came from and how many there are in
        // total.
        val stated = response.headers().firstValue("content-range").orElse(null)
        if (response.statusCode() == 206) {
            val span = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(stated.orEmpty().trim())
                ?: throw DesktopStreamReadException()
            val first = span.groupValues[1].toLongOrNull() ?: throw DesktopStreamReadException()
            val last = span.groupValues[2].toLongOrNull() ?: throw DesktopStreamReadException()
            if (first != start || last < first || last - first != body.size.toLong() - 1) throw DesktopStreamReadException()
            val total = span.groupValues[3].toLongOrNull()
            if (total != null && last >= total) throw DesktopStreamReadException()
            windowStart = first
            length = total ?: length
        } else {
            windowStart = 0
            length = body.size.toLong()
        }
        window = body
        if (!holds(at) && (length < 0 || at < length)) throw DesktopStreamReadException()
        return body.isNotEmpty()
    }

    private fun fetchWithRetry(request: HttpRequest): HttpResponse<ByteArray> {
        for (attempt in 0..2) {
            var failure: IOException? = null
            val response = try { fetch(request) } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) { failure = error; null }
            if (response != null && response.statusCode() !in RETRYABLE_STATUS) return response
            if (attempt == 2) throw DesktopStreamReadException(response?.statusCode(), failure)
            val retryAfter = response?.headers()?.firstValue("Retry-After")?.orElse(null)?.let {
                it.toLongOrNull()?.coerceIn(0, 6)?.let { seconds -> seconds * 1_000 }
                    ?: runCatching {
                        val instant = java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                        Duration.between(java.time.Instant.now(), instant).toMillis().coerceAtLeast(0)
                    }.getOrNull()
            } ?: 0L
            // A long rate limit is an actionable failure, not permission to retry before it expires.
            if (retryAfter > 5_000) throw DesktopStreamReadException(response?.statusCode(), failure)
            val backoff = maxOf(if (attempt == 0) 250L else 750L, retryAfter)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoff)
            synchronized(requestLock) {
                checkCancellation()
                while (true) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    try { TimeUnit.NANOSECONDS.timedWait(requestLock, left) }
                    catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("Audio retry cancelled").apply { initCause(error) }
                    }
                    checkCancellation()
                }
            }
        }
        error("Unreachable retry state")
    }

    private fun fetch(request: HttpRequest): HttpResponse<ByteArray> {
        val future = synchronized(requestLock) {
            checkCancellation()
            client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).also { pendingRequest = it }
        }
        try { return future.get(20, TimeUnit.SECONDS) }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Audio read cancelled").apply { initCause(error) }
        } catch (error: java.util.concurrent.CancellationException) {
            throw InterruptedIOException("Audio read cancelled").apply { initCause(error) }
        } catch (error: TimeoutException) { throw DesktopStreamReadException(cause = error) }
        catch (error: ExecutionException) { throw DesktopStreamReadException(cause = error.cause) }
        finally {
            future.cancel(true)
            synchronized(requestLock) { if (pendingRequest === future) pendingRequest = null }
        }
    }

    private companion object {
        /** Comfortably inside what Google will serve. */
        const val DEFAULT_WINDOW = 512 * 1024
        val RETRYABLE_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)

        val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }
}
