package com.music.bitchord

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.ui.LocalVisualActivity
import com.music.bitchord.ui.rememberIsForeground
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStateTest {
    @Test(timeout = 15_000) fun hiddenHostStopsFrameWorkEvenWhenLifecycleRemainsResumed() = runBlocking {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            val visible = mutableStateOf(true)
            var observed: Boolean? = null
            var frames = 0
            var time = 0L
            suspend fun settle(expected: Boolean) = withTimeout(5_000) {
                Snapshot.sendApplyNotifications()
                while (observed != expected) {
                    clock.sendFrame(++time)
                    yield()
                }
                repeat(3) { clock.sendFrame(++time); yield() }
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalLifecycleOwner provides owner,
                        LocalVisualActivity provides visible.value,
                    ) {
                        val active = rememberIsForeground()
                        SideEffect { observed = active }
                        LaunchedEffect(active) {
                            if (active) while (true) withFrameNanos { frames++ }
                        }
                    }
                }
                settle(true)
                val foregroundFrames = frames
                visible.value = false
                settle(false)
                val hiddenFrames = frames
                repeat(10) { clock.sendFrame(++time); yield() }
                assertEquals(hiddenFrames, frames)
                assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
                visible.value = true
                settle(true)
                assertTrue(frames > hiddenFrames && hiddenFrames >= foregroundFrames)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                settle(false)
                val stoppedFrames = frames
                repeat(10) { clock.sendFrame(++time); yield() }
                assertEquals(stoppedFrames, frames)
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                settle(true)
                assertTrue(frames > stoppedFrames)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
            }
        }
    }
}
