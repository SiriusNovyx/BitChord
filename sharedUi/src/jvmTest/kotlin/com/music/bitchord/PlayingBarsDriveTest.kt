package com.music.bitchord

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.ui.LocalVisualActivity
import com.music.bitchord.ui.components.rememberPlayingBarHeights
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlayingBarsDriveTest {
    @Test(timeout = 15_000) fun coveredOrStoppedBarsReleaseTheirFrameWaiters() = runBlocking {
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
            val visible = mutableStateOf(true)
            var heights: List<Float>? = null
            suspend fun settle() {
                Snapshot.sendApplyNotifications()
                repeat(15) { frames.sendFrame(System.nanoTime()); yield() }
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalVisualActivity provides visible.value) {
                        val bars = rememberPlayingBarHeights()
                        SideEffect { heights = bars }
                    }
                }
                settle()
                assertEquals(3, requireNotNull(heights).size)
                assertTrue("visible bars lost animation", frames.hasAwaiters)
                visible.value = false
                settle()
                assertFalse("covered bars kept requesting frames", frames.hasAwaiters)
                visible.value = true
                settle()
                assertTrue("uncovered bars did not resume", frames.hasAwaiters)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                settle()
                assertFalse("stopped lifecycle kept animating", frames.hasAwaiters)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
            }
        }
    }
}
