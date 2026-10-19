package com.music.bitchord.desktop

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.concurrent.withLock

/** What [DesktopDecodeAhead] reads from: a decoder, or a stand-in for one in tests. */
internal interface DesktopSampleSource {
    /** The next block as interleaved floats, or null at the end. The array may be reused. */
    fun readSamples(): FloatArray?

    /** How much of the array [readSamples] returned is actually this block. */
    val sampleCount: Int

    fun seek(micros: Long): Boolean

    /** Signal transport cancellation without blocking or freeing the decoder's native state. */
    fun cancelPendingRead() = Unit

    /** Reset the cancellation signal before releasing the command lock for a new seek. */
    fun prepareSeek() = Unit

    fun close()
}

internal sealed interface DesktopDecodedRead {
    data class Data(val samples: FloatArray) : DesktopDecodedRead
    data object Waiting : DesktopDecodedRead
    data object End : DesktopDecodedRead
    data class Failed(val cause: Exception) : DesktopDecodedRead
}

/**
 * Decodes a track a few seconds ahead of the speakers, on a thread of its own.
 *
 * The audio thread used to call the decoder directly, and the decoder reads straight from the
 * network: a YouTube stream stops for a whole 512 KiB range request every half-minute or so, and a
 * lossless stream waits on its socket whenever the connection hiccups. WASAPI holds only a fifth
 * of a second, so any read slower than that was heard as a gap of a few milliseconds. Here those
 * waits land on this thread while the audio thread plays what was decoded earlier.
 *
 * Only this thread touches [source] once constructed — seeks and the close are handed to it — so
 * the native decoder never sees two threads.
 */
internal class DesktopDecodeAhead(
    private val source: DesktopSampleSource,
    val outputFormat: DesktopPcmFormat,
    val durationUs: Long?,
    val measuredFormat: DesktopStreamFormat?,
    /** How far ahead to decode, in samples across all channels. */
    private val aheadSamples: Int = outputFormat.sampleRate * outputFormat.channels * AHEAD_SECONDS,
    name: String = "BitChord-Decode",
) {

    private val lock = ReentrantLock()
    /** Signalled when there is something for the audio thread to take. */
    private val ready = lock.newCondition()
    /** Signalled when the worker has room, a seek, or a close to act on. */
    private val wake = lock.newCondition()

    private val queue = ArrayDeque<FloatArray>()
    private var queuedSamples = 0
    private var ended = false
    private var failure: Exception? = null
    private var closed = false
    private var pendingSeekUs: Long? = null
    /** Bumped by every seek, so a block decoded before it is never played after it. */
    private var generation = 0
    /** Whether a block has been handed out since the last seek; a wait before the first is a load. */
    private var started = false
    private var waitingSinceNanos: Long? = null

    /** How much of the array [readSamples] returned is actually this block. */
    var sampleCount: Int = 0
        private set

    private val worker = Thread(::decodeLoop, name).apply {
        isDaemon = true
        // Above the UI, below the audio thread itself.
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    /** Bounded wait: the audio thread must return to its command loop while decoding is stalled. */
    fun poll(timeoutMs: Long = 20): DesktopDecodedRead = lock.withLock {
        require(timeoutMs >= 0)
        if (queue.isEmpty() && !ended && !closed && failure == null) {
            if (waitingSinceNanos == null) waitingSinceNanos = System.nanoTime()
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (queue.isEmpty() && !ended && !closed && failure == null && remaining > 0) {
                remaining = ready.awaitNanos(remaining)
            }
        }
        val block = queue.removeFirstOrNull()
        if (block == null) {
            sampleCount = 0
            return when {
                closed -> DesktopDecodedRead.End
                failure != null -> DesktopDecodedRead.Failed(failure!!)
                ended -> DesktopDecodedRead.End
                else -> DesktopDecodedRead.Waiting
            }
        }
        waitingSinceNanos?.let { since ->
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)
            if (started && waitedMs >= STARVED_LOG_MS) {
                DesktopTrackLog.log("decode-ahead resumed after ${waitedMs}ms without queued samples")
            }
        }
        waitingSinceNanos = null
        queuedSamples -= block.size
        started = true
        wake.signal()
        sampleCount = block.size
        DesktopDecodedRead.Data(block)
    }

    /** Blocking convenience for non-command consumers; playback uses [poll]. */
    fun readSamples(): FloatArray? {
        while (true) {
            when (val next = poll(1_000)) {
                is DesktopDecodedRead.Data -> return next.samples
                DesktopDecodedRead.Waiting -> Unit
                DesktopDecodedRead.End -> return null
                is DesktopDecodedRead.Failed -> throw next.cause
            }
        }
    }

    /** Moves to [micros]; whatever was decoded ahead of the old position is thrown away. */
    fun seek(micros: Long): Boolean {
        lock.withLock {
            generation++
            queue.clear()
            queuedSamples = 0
            ended = false
            failure = null
            started = false
            waitingSinceNanos = null
            pendingSeekUs = micros
            // The worker cannot start the new seek/read until this signal has been issued.
            runCatching { source.cancelPendingRead() }
            wake.signal()
            ready.signalAll()
        }
        return true
    }

    /** Stops decoding; the decoder itself is closed by the worker, once any read in flight returns. */
    fun close() {
        lock.withLock {
            closed = true
            queue.clear()
            queuedSamples = 0
            runCatching { source.cancelPendingRead() }
            wake.signal()
            ready.signalAll()
        }
    }

    val isTerminated: Boolean get() = !worker.isAlive

    fun awaitTermination(timeoutNanos: Long): Boolean {
        if (timeoutNanos > 0 && Thread.currentThread() !== worker) {
            TimeUnit.NANOSECONDS.timedJoin(worker, timeoutNanos)
        }
        return isTerminated
    }

    private fun decodeLoop() {
        try {
            while (true) {
                var seekTo: Long? = null
                val decodingFor = lock.withLock {
                    while (!closed && pendingSeekUs == null && (ended || failure != null || queuedSamples >= aheadSamples)) {
                        wake.await()
                    }
                    if (closed) return
                    seekTo = pendingSeekUs
                    pendingSeekUs = null
                    if (seekTo != null) source.prepareSeek()
                    generation
                }
                val micros = seekTo
                if (micros != null) {
                    val seekFailure = try {
                        if (source.seek(micros)) null else IOException("Audio source could not seek")
                    } catch (error: Exception) { error }
                    lock.withLock {
                        if (!closed && decodingFor == generation && seekFailure != null) {
                            failure = seekFailure
                            ready.signalAll()
                        }
                    }
                    continue
                }

                var readFailure: Exception? = null
                val decoded = try {
                    source.readSamples()?.copyOf(source.sampleCount)
                } catch (error: Exception) {
                    readFailure = error
                    null
                }
                lock.withLock {
                    // A seek came in while this block was being read; it belongs to the old position.
                    if (decodingFor != generation) return@withLock
                    // Close came in while this block was being read. Committing it would leave the
                    // queue non-empty after close, and the caller's very next readSamples() — which
                    // the contract says is null — would return this block instead. Seen on Windows
                    // CI, where scheduling lets the caller's close win this race consistently;
                    // Linux's timing hides it. The block dies with the closed reader.
                    if (closed) return@withLock
                    if (readFailure != null) {
                        failure = readFailure
                        DesktopTrackLog.log("decode-ahead: read failed (${readFailure.javaClass.simpleName})")
                    } else if (decoded == null) {
                        ended = true
                    } else if (decoded.isNotEmpty()) {
                        queue.addLast(decoded)
                        queuedSamples += decoded.size
                    }
                    ready.signalAll()
                }
            }
        } catch (_: InterruptedException) {
            // Cancellation still closes native state on its owning worker.
        } finally {
            runCatching { source.close() }
        }
    }

    private companion object {
        /** Long enough to ride out a slow range request or a reconnect. */
        const val AHEAD_SECONDS = 8

        /** Waits shorter than this are ordinary scheduling, not a network stall worth logging. */
        const val STARVED_LOG_MS = 20L
    }
}
