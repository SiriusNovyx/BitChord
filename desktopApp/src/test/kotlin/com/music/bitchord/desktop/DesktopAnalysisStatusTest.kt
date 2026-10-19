package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.TrackAnalysisEligibility
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.AnalysisStore
import com.music.bitchord.playback.smart.TrackAnalysis
import java.io.File
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAnalysisStatusTest {
    private fun song(id: String) = Song(id, "Synthetic", "Artist", null)

    private class ManualWorker : AbstractExecutorService() {
        val pending = ArrayDeque<Runnable>()
        var inTask = false
        private var closed = false
        override fun execute(command: Runnable) { check(!closed); pending.addLast(command) }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> = pending.toMutableList().also { pending.clear(); closed = true }
        override fun isShutdown() = closed
        override fun isTerminated() = closed && pending.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() {
            inTask = true
            try { pending.removeFirst().run() } finally { inTask = false }
        }
    }

    @Test fun queuedWorkIsWaitingAndOnlyTheExecutingWorkerIsAnalysing() {
        assertTrue(DesktopAnalysisRuntime.available, "native analyser required for this integration test")
        val worker = ManualWorker()
        val track = song(UUID.randomUUID().toString())
        val local = DesktopStream("synthetic-local-file.wav")
        var observed: TrackAnalysisState? = null
        lateinit var analyzer: DesktopTrackAnalyzer
        analyzer = DesktopTrackAnalyzer(worker = worker, stopped = {
            if (worker.inTask) observed = analyzer.statusFor(track, local).state
            worker.inTask
        })
        try {
            analyzer.request(track, local, 60.0)
            assertEquals(1, worker.pending.size)
            assertEquals(TrackAnalysisState.WAITING, analyzer.statusFor(track, local).state)
            worker.runNext()
            assertEquals(TrackAnalysisState.ANALYSING, observed)
            assertEquals(TrackAnalysisState.WAITING, analyzer.statusFor(track, local).state)
        } finally { analyzer.release() }
    }

    @Test fun cachedEvidenceCannotOverrideVideoOrSourcePolicyAndUnsupportedRequestsDoNoWork() {
        val worker = ManualWorker()
        val analyzer = DesktopTrackAnalyzer(worker = worker)
        val id = DesktopAddonSource.trackKey("status-test", UUID.randomUUID().toString())
        val home = DesktopAnalysisRuntime.analysisHome()
        val file = File(File(home, "smart_analysis"), "${id.hashCode().toUInt()}_${id.length}.json")
        assertFalse(file.exists())
        try {
            AnalysisStore { home }.save(id, TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = id, bpm = 120.0))
            assertTrue(analyzer.analysisFor(id).isUsable)
            val addon = song(id)
            val status = analyzer.statusFor(addon)
            assertEquals(TrackAnalysisEligibility.SOURCE_POLICY, status.eligibility)
            assertFalse(status.isMeasured)
            assertEquals(TrackAnalysisEligibility.VIDEO, analyzer.statusFor(addon.copy(isVideoOrigin = true)).eligibility)
            assertEquals(TrackAnalysisEligibility.SOURCE_POLICY,
                analyzer.statusFor(song(SPOTIFY_NATIVE_PREFIX + "synthetic")).eligibility)
            analyzer.request(addon, null, 60.0)
            analyzer.request(addon.copy(isVideoOrigin = true), null, 60.0)
            assertTrue(worker.pending.isEmpty(), "unsupported rows must not start matching, decoding or inference")
        } finally { analyzer.release(); file.delete() }
    }

    @Test fun absentAndKnownMissingSourcesAreDifferent() {
        val analyzer = DesktopTrackAnalyzer()
        try {
            assertEquals(TrackAnalysisEligibility.ABSENT, analyzer.statusFor(null).eligibility)
            assertEquals(TrackAnalysisEligibility.ELIGIBLE, analyzer.statusFor(song("canonical-id").copy(title = "")).eligibility)
            assertEquals(TrackAnalysisEligibility.NO_SOURCE, analyzer.statusFor(song("jiosaavn:source").copy(title = "")).eligibility)
        } finally { analyzer.release() }
    }

    @Test fun noMatchRemainsVisibleDuringCooldownAndANewerAttemptCanFindASource() {
        assertTrue(DesktopAnalysisRuntime.available)
        val worker = ManualWorker()
        var now = 1_000L
        var calls = 0
        val track = song("jiosaavn:" + UUID.randomUUID())
        val analyzer = DesktopTrackAnalyzer(worker = worker, currentTimeMs = { now }, sourceOverride = {
            calls++
            if (calls == 1) null else DesktopStream("nonexistent-analysis-fixture.wav")
        })
        try {
            analyzer.request(track, null, 60.0)
            worker.runNext()
            assertEquals(TrackAnalysisEligibility.NO_SOURCE, analyzer.statusFor(track).eligibility)
            analyzer.request(track, null, 60.0)
            assertTrue(worker.pending.isEmpty())
            assertEquals(1, calls)
            now += DesktopTrackAnalyzer.RETRY_AFTER_MS
            analyzer.request(track, null, 60.0)
            assertEquals(TrackAnalysisEligibility.UNKNOWN, analyzer.statusFor(track).eligibility)
            assertEquals(1, worker.pending.size)
            worker.runNext()
            assertEquals(2, calls)
            assertEquals(TrackAnalysisEligibility.ELIGIBLE, analyzer.statusFor(track).eligibility)
            assertEquals(TrackAnalysisState.FAILED, analyzer.statusFor(track).state)
        } finally { analyzer.release() }
    }
}
