package com.music.bitchord.desktop

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class SpotifyPcmSource(private val startAtMs: Long, private val launch: (Long) -> Process) : DesktopSampleSource {
    private class Run(val process: Process) {
        val blocks = ArrayBlockingQueue<FloatArray>(8)
        val closing = AtomicBoolean(false)
        @Volatile var ended = false
        @Volatile var failure: SpotifyPcmException? = null
        lateinit var reader: Thread
    }
    private var run: Run? = null
    private var first: FloatArray? = null
    private var closed = false
    override var sampleCount = 0
        private set

    fun open() {
        check(run == null && !closed)
        val started = start(startAtMs)
        run = started
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
            while (first == null) {
                first = started.blocks.poll(100, TimeUnit.MILLISECONDS)
                if (started.failure != null || (started.ended && first == null) || System.nanoTime() >= deadline) {
                    throw (started.failure ?: SpotifyPcmException())
                }
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun start(positionMs: Long): Run {
        val started = Run(launch(positionMs))
        started.reader = Thread({
            try {
                val protocol = SpotifyPcmProtocol(started.process.inputStream)
                while (!started.closing.get()) {
                    val block = protocol.next() ?: break
                    started.blocks.put(block)
                }
            } catch (_: InterruptedException) {
                // A seek or close retires this reader and its bounded queue together.
            } catch (failure: Exception) {
                if (!started.closing.get()) started.failure = failure as? SpotifyPcmException ?: SpotifyPcmException()
            } finally { started.ended = true }
        }, "BitChord Spotify PCM").apply { isDaemon = true; start() }
        return started
    }

    override fun readSamples(): FloatArray? {
        if (closed) return null.also { sampleCount = 0 }
        val current = checkNotNull(run)
        val block = first?.also { first = null } ?: current.blocks.poll(100, TimeUnit.MILLISECONDS)
        current.failure?.let { throw it }
        val result = block ?: if (current.ended) null else FloatArray(0)
        sampleCount = result?.size ?: 0
        return result
    }

    override fun seek(micros: Long): Boolean {
        if (closed) return false
        run?.let(::retire)
        first = null
        run = start((micros / 1000).coerceAtLeast(0))
        return true
    }

    private fun retire(current: Run) {
        current.closing.set(true)
        current.reader.interrupt()
        runCatching { current.process.outputStream.close() }
        current.process.destroy()
        try {
            if (!current.process.waitFor(1, TimeUnit.SECONDS)) current.process.destroyForcibly()
        } catch (_: InterruptedException) {
            current.process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
        runCatching { current.process.inputStream.close() }
        current.blocks.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        run?.let(::retire)
        run = null
        first = null
    }
}
