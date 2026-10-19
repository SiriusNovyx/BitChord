package com.music.bitchord

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.player.LyricFrameSchedule
import com.music.bitchord.ui.player.LyricPlayhead
import com.music.bitchord.ui.player.rememberLyricClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricClockDriveTest {
    @Test(timeout = 15_000) fun lineLyricsDoNotRequestContinuousFramesButWordTimingDoes() = runBlocking {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val frames = BroadcastFrameClock()
        withContext(frames) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            val position = PlaybackPosition().apply { report(1_000) }
            val playhead = LyricPlayhead(position, mutableStateOf(0))
            val playing = mutableStateOf(true)
            val schedule = mutableStateOf(LyricFrameSchedule(listOf(
                LyricLine(0, "first"), LyricLine(10_000, "next")), false))
            var observed: MutableLongState? = null
            suspend fun settle() {
                Snapshot.sendApplyNotifications()
                repeat(15) { frames.sendFrame(System.nanoTime()); yield() }
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        val clock = rememberLyricClock("track", playhead, playing.value, schedule.value)
                        SideEffect { observed = clock }
                    }
                }
                settle()
                assertNotNull(observed)
                assertFalse("line-only lyrics kept a frame waiter active", frames.hasAwaiters)
                position.seeks++
                position.report(5_000)
                settle()
                assertTrue("a seek was not reflected", requireNotNull(observed).longValue >= 5_000)
                assertFalse(frames.hasAwaiters)
                schedule.value = LyricFrameSchedule(listOf(
                    LyricLine(0, "word", listOf(LyricWord(0, 10_000, "word")))), false)
                settle()
                assertTrue("word timing lost its frame clock", frames.hasAwaiters)
                playing.value = false
                settle()
                assertFalse("paused lyrics kept requesting frames", frames.hasAwaiters)
                position.seeks++
                position.report(500)
                settle()
                assertEquals(500L, requireNotNull(observed).longValue)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
            }
        }
    }
}
