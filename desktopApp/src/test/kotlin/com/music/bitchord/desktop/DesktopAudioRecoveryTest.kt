package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAudioRecoveryTest {
    private val pcm = DesktopPcmFormat(48_000, 2, 4, isFloat = true)
    private fun song(id: String) = Song(id, id, "Synthetic", null, durationText = "1:00")

    private class Source : DesktopSampleSource {
        val events = LinkedBlockingQueue<() -> FloatArray?>()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val seekUs = AtomicLong(-1)
        val cancellations = AtomicInteger()
        var trackId: String? = null
        @Volatile var closeAction: () -> Unit = {}
        override var sampleCount = 0
        override fun readSamples(): FloatArray? {
            entered.countDown()
            return events.take().invoke().also { sampleCount = it?.size ?: 0 }
        }
        fun emit(frames: Int) { events.put { FloatArray(frames * 2) { .1f } } }
        override fun seek(micros: Long): Boolean { seekUs.set(micros); return true }
        override fun cancelPendingRead() { cancellations.incrementAndGet(); events.offer { FloatArray(0) } }
        override fun close() { closeAction(); closed.countDown() }
    }

    private class Output : DesktopAudioOutput {
        override var format = DesktopPcmFormat(48_000, 2, 4, isFloat = true)
        override var gain = 1f
        override var isOpen = false
        override val deviceName = "Synthetic output"
        override val bufferBytes = 0
        val submitted = AtomicLong()
        val played = AtomicLong()
        val closed = CountDownLatch(1)
        @Volatile var autoPlay = false
        @Volatile var acceptFrames: ((Int) -> Int)? = null
        val paused = CountDownLatch(1)
        override fun isUsingSelection(id: String) = isOpen
        override fun open(requested: DesktopPcmFormat) = Result.success(requested).also { format = requested; isOpen = true }
        override fun write(samples: FloatArray, count: Int): Int {
            val accepted = acceptFrames?.invoke(count / format.channels) ?: (count / format.channels)
            submitted.addAndGet(accepted.toLong())
            if (autoPlay) played.set(submitted.get())
            return accepted * format.frameBytes
        }
        override fun framesPlayed() = played.get()
        override fun pause() { paused.countDown() }
        override fun resume() = Unit
        override fun flush() { submitted.set(played.get()) }
        override fun close() { isOpen = false; closed.countDown() }
    }

    private fun engine(output: Output, sources: MutableList<Source>, ended: AtomicInteger = AtomicInteger(), crossfaded: AtomicInteger = AtomicInteger(), incomingReady: CompletableDeferred<Unit>? = null): DesktopPlaybackEngine =
        DesktopPlaybackEngine({ ended.incrementAndGet() }, { crossfaded.incrementAndGet() }, output,
            { song ->
                val pending = if (song.videoId == "B" && incomingReady != null) {
                    val delegate = CompletableDeferred<DesktopStream?>()
                    object : Deferred<DesktopStream?> by delegate {
                        override suspend fun await(): DesktopStream? {
                            incomingReady.complete(Unit)
                            return delegate.await()
                        }
                    }
                } else null
                Result.success(DesktopLiveResolution(DesktopStream("https://synthetic.invalid"), pending))
            },
            { song, _, startAt ->
                val source = Source().also { it.trackId = song.videoId; it.seekUs.set(startAt * 1_000); sources += it }
                DesktopDecodeAhead(source, pcm, 60_000_000, null, aheadSamples = 96_000)
            }, false)

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(predicate(), message)
    }

    @Test fun `shutdown waits for decoder owned close after the audio pump stops`() {
        val output = Output()
        val sources = CopyOnWriteArrayList<Source>()
        val engine = engine(output, sources)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var stopper: Thread? = null
        try {
            engine.load(song("A"))
            await("decoder opened") { sources.size == 1 && !engine.state.value.isLoading }
            val source = sources.single()
            source.closeAction = { closing.countDown(); releaseClose.await(5, TimeUnit.SECONDS) }
            stopper = Thread { try { engine.shutdown(1_500) } finally { returned.countDown() } }.apply { start() }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "pump exit must not leave native decoder cleanup running")
            releaseClose.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(0L, source.closed.count)
            assertEquals(0L, output.closed.count)
        } finally { releaseClose.countDown(); engine.shutdown(); stopper?.join(3_000) }
    }

    @Test fun `shutdown waits for cancelled opening to hand back its decoder`() = runBlocking {
        val output = Output()
        val source = Source()
        val opening = CompletableDeferred<Unit>()
        val releaseOpening = CompletableDeferred<Unit>()
        val returned = CountDownLatch(1)
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("https://synthetic.invalid"))) },
            { _, _, _ ->
                withContext(NonCancellable) { opening.complete(Unit); releaseOpening.await() }
                DesktopDecodeAhead(source, pcm, 60_000_000, null)
            }, false)
        var stopper: Thread? = null
        try {
            engine.load(song("A"))
            withTimeout(2_000) { opening.await() }
            stopper = Thread { try { engine.shutdown(1_500) } finally { returned.countDown() } }.apply { start() }
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "cancelled open still owns native state")
            releaseOpening.complete(Unit)
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertEquals(0L, source.closed.count)
        } finally { releaseOpening.complete(Unit); engine.shutdown(); stopper?.join(3_000) }
    }

    @Test fun `paused queue status keeps an unsupported next track distinct from an empty queue`() {
        val output = Output()
        val sources = CopyOnWriteArrayList<Source>()
        val engine = engine(output, sources)
        try {
            engine.load(song("A"), false)
            await("paused track opened") { !engine.state.value.isLoading && engine.state.value.song?.videoId == "A" }
            engine.prepareNext(song("B").copy(isVideoOrigin = true))
            await("video remains present in paused queue") {
                engine.state.value.smartAnalysis.next.eligibility == com.music.bitchord.data.settings.TrackAnalysisEligibility.VIDEO
            }
            engine.prepareNext(null)
            await("empty queue is absent") {
                engine.state.value.smartAnalysis.next.eligibility == com.music.bitchord.data.settings.TrackAnalysisEligibility.ABSENT
            }
            assertFalse(engine.state.value.isPlaying)
            assertEquals(1, sources.size)
            engine.prepareNext(song("A"))
            await("repeat of the same song is still a present queue item") {
                engine.state.value.smartAnalysis.next.eligibility != com.music.bitchord.data.settings.TrackAnalysisEligibility.ABSENT
            }
            engine.load(song("C"), false)
            await("new selection opened") { !engine.state.value.isLoading && engine.state.value.song?.videoId == "C" }
            assertEquals(com.music.bitchord.data.settings.TrackAnalysisEligibility.ABSENT, engine.state.value.smartAnalysis.next.eligibility)
        } finally { engine.shutdown() }
    }

    @Test fun `two buffering interruptions hold the heard position and reanchor on resume`() {
        val output = Output()
        val sources = CopyOnWriteArrayList<Source>()
        val engine = engine(output, sources)
        try {
            engine.load(song("A"))
            await("track opened") { sources.size == 1 && !engine.state.value.isLoading }
            val source = sources.single()
            source.emit(48_000)
            await("first audio submitted") { output.submitted.get() == 48_000L && !engine.state.value.awaitingAudio }
            // Decoding is stalled, but the first second is still queued at the device.
            assertFalse(engine.state.value.awaitingAudio)
            output.played.set(24_000)
            await("queued audio still advances") { engine.state.value.positionMs == 500L }
            for (second in 1..2) {
                output.played.set(second * 48_000L)
                await("drained audio holds clock $second") { engine.state.value.awaitingAudio && engine.state.value.positionMs == second * 1_000L }
                val held = engine.state.value
                Thread.sleep(70)
                assertEquals(held.positionMs, engine.state.value.positionMs)
                assertEquals(held.positionSampledAtNanos, engine.state.value.positionSampledAtNanos)
                source.emit(48_000)
                await("audio resumes $second") { !engine.state.value.awaitingAudio && output.submitted.get() == (second + 1) * 48_000L }
                assertTrue(engine.state.value.positionSampledAtNanos > held.positionSampledAtNanos)
                assertEquals(second * 1_000L, engine.state.value.positionMs)
            }
        } finally { engine.shutdown() }
    }

    @Test fun `a stalled decoder accepts a seek and a different song without restarting`() {
        val output = Output()
        val sources = CopyOnWriteArrayList<Source>()
        val engine = engine(output, sources)
        try {
            engine.load(song("A"))
            await("first source") { sources.size == 1 && !engine.state.value.isLoading }
            val first = sources.single()
            assertTrue(first.entered.await(1, TimeUnit.SECONDS))
            val previousRevision = engine.state.value.seeks
            engine.seekTo(3_000)
            await("seek handled during stall") { first.seekUs.get() == 3_000_000L && engine.state.value.seeks == previousRevision + 1 }
            engine.load(song("B"))
            await("new song handled during stall") { sources.size == 2 && engine.state.value.song?.videoId == "B" && !engine.state.value.isLoading }
            assertTrue(first.closed.await(1, TimeUnit.SECONDS))
            output.autoPlay = true
            sources.last().emit(4_800)
            await("new song plays") { output.submitted.get() > 0 && !engine.state.value.awaitingAudio }
        } finally { engine.shutdown() }
    }

    @Test fun `transport failure preserves selection and Play retries without reporting song ended`() {
        val output = Output()
        val sources = CopyOnWriteArrayList<Source>()
        val ended = AtomicInteger()
        val engine = engine(output, sources, ended)
        try {
            engine.load(song("A"))
            await("first source") { sources.size == 1 && !engine.state.value.isLoading }
            sources.single().events.put { throw IOException("Synthetic outage") }
            await("automatic retry source") { sources.size == 2 && !engine.state.value.isLoading }
            sources.last().events.put { throw IOException("Outage continues") }
            await("actionable error") { engine.state.value.error != null }
            assertEquals("A", engine.state.value.song?.videoId)
            assertEquals(0, ended.get())
            engine.play()
            await("retry source") { sources.size == 3 && !engine.state.value.isLoading }
            output.autoPlay = true
            sources.last().emit(4_800)
            await("retry is audible") { !engine.state.value.awaitingAudio && output.submitted.get() > 0 }
            assertEquals(null, engine.state.value.error)
        } finally { engine.shutdown() }
    }

    @Test fun `waiting for final queued audio does not prevent selecting a new song`() {
        val output = Output()
        val sources = CopyOnWriteArrayList<Source>()
        val engine = engine(output, sources)
        try {
            engine.load(song("A"))
            await("source") { sources.size == 1 && !engine.state.value.isLoading }
            sources.single().emit(4_800)
            sources.single().events.put { null }
            await("audio waiting at output") { output.submitted.get() == 4_800L }
            engine.load(song("B"))
            await("new track does not wait for old drain") { engine.state.value.song?.videoId == "B" && !engine.state.value.isLoading }
        } finally { engine.shutdown() }
    }

    @Test fun `shutdown closes the output even when its idle sleep is interrupted`() {
        val output = Output()
        val engine = engine(output, CopyOnWriteArrayList())
        assertTrue(output.paused.await(1, TimeUnit.SECONDS))
        engine.shutdown()
        assertTrue(output.closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun `late cancelled open is closed even when the same song is requested again`() {
        val output = Output()
        val oldOpened = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val old = Source()
        val opens = AtomicInteger()
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("synthetic"))) },
            { _, _, _ ->
                val openingOld = opens.incrementAndGet() == 1
                if (openingOld) withContext(NonCancellable) { oldOpened.complete(Unit); releaseOld.await() }
                DesktopDecodeAhead(if (openingOld) old else Source(), pcm, 60_000_000, null)
            }, false)
        try {
            engine.load(song("A"))
            runBlocking { oldOpened.await() }
            engine.load(song("A"))
            await("second A opened") { opens.get() == 2 && !engine.state.value.isLoading }
            releaseOld.complete(Unit)
            assertTrue(old.closed.await(1, TimeUnit.SECONDS), "Cancelled opening result must release its decoder")
            assertEquals("A", engine.state.value.song?.videoId)
        } finally { releaseOld.complete(Unit); engine.shutdown() }
    }

    @Test fun `one automatic recovery refreshes the source and resumes at the heard position`() {
        val output = Output().apply { autoPlay = true }
        val sources = CopyOnWriteArrayList<Source>()
        val ended = AtomicInteger()
        val engine = engine(output, sources, ended)
        try {
            engine.load(song("A"))
            await("source") { sources.size == 1 && !engine.state.value.isLoading }
            sources.single().emit(48_000)
            await("one second heard") { engine.state.value.positionMs == 1_000L }
            sources.single().events.put { throw IOException("Synthetic outage") }
            await("source refreshed automatically") { sources.size == 2 && !engine.state.value.isLoading }
            assertEquals(1_000_000L, sources.last().seekUs.get())
            assertEquals("A", engine.state.value.song?.videoId)
            sources.last().emit(48_000)
            await("new source plays") { !engine.state.value.awaitingAudio && engine.state.value.positionMs == 2_000L }
            // A second failure stops with an actionable error, without an endless reconnect loop.
            sources.last().events.put { throw IOException("Still offline") }
            await("automatic budget exhausted") { engine.state.value.error != null }
            Thread.sleep(700)
            assertEquals(2, sources.size)
            assertEquals(0, ended.get())
        } finally { engine.shutdown() }
    }

    private fun startFade(engine: DesktopPlaybackEngine, sources: MutableList<Source>, output: Output, incomingReady: CompletableDeferred<Unit>, incomingFrames: Int = 48_000) {
        engine.setCrossfadeSeconds(1)
        engine.load(song("A"))
        await("A opened") { sources.size == 1 && !engine.state.value.isLoading }
        engine.prepareNext(song("B"))
        // upgradeIncoming awaits only after Upcoming was enqueued. The following seek acts as
        // a command barrier, so it cannot complete before the incoming deck is installed.
        runBlocking { withTimeout(3_000) { incomingReady.await() } }
        engine.seekTo(59_000)
        await("A seek applied") { sources.first().seekUs.get() == 59_000_000L }
        sources.last().emit(incomingFrames)
        sources.first().emit(480)
        await("primer starts fade") { output.submitted.get() >= 480 }
        // Wait for the primer's state publication to finish before delivering the final block.
        await("primer clock sampled") { engine.state.value.positionMs >= 59_010 }
    }

    @Test fun `the final fade block retains its owner until every frame is accepted`() {
        val output = Output().apply { autoPlay = true }
        val sources = CopyOnWriteArrayList<Source>()
        val crossfaded = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val engine = engine(output, sources, crossfaded = crossfaded, incomingReady = ready)
        val first = AtomicBoolean(true)
        val release = AtomicBoolean(false)
        val finalWrite = CountDownLatch(1)
        output.acceptFrames = { frames ->
            if (frames < 10_000) frames
            else {
                finalWrite.countDown()
                if (first.getAndSet(false)) 480 else if (release.get()) frames else 0
            }
        }
        try {
            startFade(engine, sources, output, ready)
            sources.first().emit(48_000)
            assertTrue(finalWrite.await(2, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(1L, sources.first().closed.count, "Outgoing decoder still owns an unfinished output block")
            assertEquals(0, crossfaded.get())
            engine.prepareNext(song("C"))
            Thread.sleep(100)
            assertEquals(2, sources.size, "Queue change waits for the PCM already mixed with B")
            release.set(true)
            await("one completed handoff") { crossfaded.get() == 1 && engine.state.value.song?.videoId == "B" }
            assertTrue(sources.first().closed.await(1, TimeUnit.SECONDS))
            assertEquals(48_480L, output.submitted.get())
        } finally { release.set(true); engine.shutdown() }
    }

    @Test fun `disabling crossfade aborts a pending final block without a stale handoff`() {
        val output = Output().apply { autoPlay = true }
        val sources = CopyOnWriteArrayList<Source>()
        val ready = CompletableDeferred<Unit>()
        val crossfaded = AtomicInteger()
        val engine = engine(output, sources, crossfaded = crossfaded, incomingReady = ready)
        val first = AtomicBoolean(true)
        val finalWrite = CountDownLatch(1)
        output.acceptFrames = { frames ->
            if (frames < 10_000) frames else {
                finalWrite.countDown()
                if (first.getAndSet(false)) 480 else 0
            }
        }
        try {
            startFade(engine, sources, output, ready)
            sources.first().emit(48_000)
            assertTrue(finalWrite.await(2, TimeUnit.SECONDS))
            await("partial block accepted") { output.submitted.get() == 960L }
            engine.setCrossfadeSeconds(0)
            assertTrue(sources[1].closed.await(1, TimeUnit.SECONDS))
            await("unwritten mixed PCM discarded and outgoing decoder rebased") { sources.first().seekUs.get() == 59_020_000L }
            sources.first().emit(480)
            await("ordinary playback continues") { output.submitted.get() == 1_440L }
            assertEquals(0, crossfaded.get())
            assertEquals("A", engine.state.value.song?.videoId)
            assertEquals(null, engine.state.value.error)
        } finally { engine.shutdown() }
    }

    @Test fun `incoming timeline excludes mixed PCM that the output has not accepted`() {
        val output = Output().apply { autoPlay = true }
        val sources = CopyOnWriteArrayList<Source>()
        val ready = CompletableDeferred<Unit>()
        val engine = engine(output, sources, incomingReady = ready)
        val first = AtomicBoolean(true)
        output.acceptFrames = { frames ->
            if (frames < 10_000) frames else if (first.getAndSet(false)) 480 else 0
        }
        try {
            startFade(engine, sources, output, ready)
            sources.first().emit(28_800)
            await("incoming shown with drained output") { engine.state.value.song?.videoId == "B" && engine.state.value.awaitingAudio }
            assertEquals(10L, engine.state.value.positionMs)
            val held = engine.state.value.positionMs
            Thread.sleep(100)
            assertEquals(held, engine.state.value.positionMs)
        } finally { engine.shutdown() }
    }

    @Test fun `failure writing the final fade block produces an error instead of an endless retry`() {
        val output = Output().apply { autoPlay = true }
        val sources = CopyOnWriteArrayList<Source>()
        val ended = AtomicInteger()
        val attempts = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val engine = engine(output, sources, ended, incomingReady = ready)
        output.acceptFrames = { frames ->
            if (frames >= 10_000) { attempts.incrementAndGet(); throw IOException("Synthetic output failure") }
            frames
        }
        try {
            startFade(engine, sources, output, ready)
            sources.first().emit(48_000)
            await("actionable output error") { engine.state.value.error != null }
            assertEquals(0, ended.get())
            assertEquals(1, attempts.get())
            sources.forEachIndexed { index, source ->
                assertTrue(source.closed.await(1, TimeUnit.SECONDS), "Source $index of ${sources.size} was not closed; seek=${source.seekUs.get()}, queued=${source.events.size}, cancellations=${source.cancellations.get()}")
            }
        } finally { engine.shutdown() }
    }

    @Test fun `pause during automatic reopening remains paused when the source is ready`() {
        val output = Output().apply { autoPlay = true }
        val first = Source()
        val second = Source()
        val openingRetry = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("https://synthetic.invalid"))) },
            { _, _, _ ->
                val source = if (opens.incrementAndGet() == 1) first else {
                    openingRetry.complete(Unit); release.await(); second
                }
                DesktopDecodeAhead(source, pcm, 60_000_000, null)
            }, false)
        try {
            engine.load(song("A"))
            await("A opened") { opens.get() == 1 && !engine.state.value.isLoading }
            first.events.put { throw IOException("Synthetic outage") }
            runBlocking { withTimeout(3_000) { openingRetry.await() } }
            engine.pause()
            release.complete(Unit)
            await("retry ready") { !engine.state.value.isLoading }
            second.emit(4_800)
            Thread.sleep(100)
            assertFalse(engine.state.value.isPlaying)
            assertEquals(0L, output.submitted.get())
            engine.play()
            await("explicit resume") { output.submitted.get() > 0 && engine.state.value.isPlaying }
        } finally { release.complete(Unit); engine.shutdown() }
    }

    @Test fun `incoming open completing after a terminal output failure is closed`() {
        val output = Output()
        val first = Source()
        val incoming = Source()
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = DesktopPlaybackEngine({}, {}, output,
            { Result.success(DesktopLiveResolution(DesktopStream("synthetic"))) },
            { song, _, _ ->
                val source = if (song.videoId == "A") first else {
                    withContext(NonCancellable) { opening.complete(Unit); release.await() }; incoming
                }
                DesktopDecodeAhead(source, pcm, 60_000_000, null)
            }, false)
        try {
            engine.setCrossfadeSeconds(1)
            engine.load(song("A"))
            await("A opened") { !engine.state.value.isLoading }
            engine.prepareNext(song("B"))
            runBlocking { withTimeout(3_000) { opening.await() } }
            output.acceptFrames = { throw IOException("Synthetic output failure") }
            first.emit(480)
            await("terminal error") { engine.state.value.error != null }
            release.complete(Unit)
            assertTrue(incoming.closed.await(1, TimeUnit.SECONDS))
            assertFalse(engine.state.value.isPlaying)
        } finally { release.complete(Unit); engine.shutdown() }
    }

    @Test fun `a deferred incoming upgrade cannot adopt a new request for the same song`() {
        val output = Output()
        val ready = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<DesktopStream?>()
        val owner = CompletableDeferred<Job>()
        val wrongOpen = CountDownLatch(1)
        val bResolutions = AtomicInteger()
        val observed = object : Deferred<DesktopStream?> by pending {
            override suspend fun await(): DesktopStream? {
                owner.complete(currentCoroutineContext()[Job]!!)
                ready.complete(Unit)
                return pending.await()
            }
        }
        val engine = DesktopPlaybackEngine({}, {}, output,
            { song -> Result.success(DesktopLiveResolution(DesktopStream("synthetic"),
                if (song.videoId == "B" && bResolutions.incrementAndGet() == 1) observed else null)) },
            { _, stream, _ ->
                if (stream.url == "old-upgrade") wrongOpen.countDown()
                DesktopDecodeAhead(Source(), pcm, 60_000_000, null)
            }, false)
        try {
            engine.setCrossfadeSeconds(1)
            engine.load(song("A"))
            await("A opened") { !engine.state.value.isLoading }
            engine.prepareNext(song("B"))
            runBlocking { withTimeout(3_000) { ready.await() } }
            engine.load(song("B"))
            await("new B request ready") { engine.state.value.song?.videoId == "B" && !engine.state.value.isLoading }
            runBlocking { withTimeout(1_000) { owner.await().join() } }
            pending.complete(DesktopStream("old-upgrade", format = DesktopStreamFormat(codec = "flac")))
            runBlocking { withTimeout(3_000) { owner.await().join() } }
            assertFalse(wrongOpen.await(500, TimeUnit.MILLISECONDS))
            assertEquals("B", engine.state.value.song?.videoId)
        } finally { pending.complete(null); engine.shutdown() }
    }

    @Test fun `recovering the incoming song preserves the deferred next queue item`() {
        val output = Output().apply { autoPlay = true }
        val sources = CopyOnWriteArrayList<Source>()
        val ready = CompletableDeferred<Unit>()
        val engine = engine(output, sources, incomingReady = ready)
        try {
            startFade(engine, sources, output, ready, incomingFrames = 28_800)
            sources.first().emit(28_800)
            await("B shown after midpoint") { engine.state.value.song?.videoId == "B" && !engine.state.value.awaitingAudio }
            engine.prepareNext(song("C"))
            sources[1].events.put { throw IOException("Incoming transport failed") }
            sources.first().emit(4_800)
            await("B recovered and C prepared") { sources.size >= 4 && !engine.state.value.isLoading }
            assertEquals("B", engine.state.value.song?.videoId)
            assertEquals(listOf("A", "B", "B", "C"), sources.map { it.trackId })
        } finally { engine.shutdown() }
    }
}
