package com.music.bitchord

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.LyricsDisplayMode
import com.music.bitchord.ui.player.LyricsRomanizationResult
import com.music.bitchord.ui.player.LyricsTranslationResult
import com.music.bitchord.ui.player.LyricsTranslationState
import com.music.bitchord.ui.player.LyricsTranslationUiState
import com.music.bitchord.ui.player.rememberLyricsTranslationState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricsTranslationStateTest {
    private class Harness(val frames: BroadcastFrameClock, val composition: Composition) {
        suspend fun settle() {
            repeat(20) {
                Snapshot.sendApplyNotifications()
                frames.sendFrame(System.nanoTime())
                yield()
            }
        }
    }

    private suspend fun harness(block: suspend Harness.() -> Unit) {
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
            try { Harness(frames, composition).block() }
            finally { composition.dispose(); recomposer.cancel(); runner.cancelAndJoin() }
        }
    }

    @Test(timeout = 15_000) fun selectedModeAppliesToNewTracksAndRemountsButOriginalDoesNotRequest() = runBlocking {
        harness {
            val mode = mutableStateOf(LyricsDisplayMode.Translated)
            val track = mutableStateOf("A")
            val calls = mutableListOf<String>()
            var observed: LyricsTranslationState? = null
            val content: @Composable () -> Unit = {
                val state = rememberLyricsTranslationState(track.value, listOf(LyricLine(0, track.value)), "th", mode.value,
                    translate = { id, lines, target ->
                        calls += "$id:$target"
                        LyricsTranslationResult.Translated(lines.map { it.copy(text = "แปลแล้ว") })
                    }, romanize = { _, lines, _ -> LyricsRomanizationResult.Romanized(lines) })
                SideEffect { observed = state }
            }
            composition.setContent(content)
            settle()
            assertTrue(observed!!.translation is LyricsTranslationUiState.Ready)
            assertEquals(listOf("A:th"), calls)
            track.value = "B"
            settle()
            assertTrue(observed!!.translation is LyricsTranslationUiState.Ready)
            assertEquals(listOf("A:th", "B:th"), calls)
            composition.setContent { }
            settle()
            composition.setContent(content)
            settle()
            assertTrue(observed!!.translation is LyricsTranslationUiState.Ready)
            assertEquals(3, calls.size)
            mode.value = LyricsDisplayMode.Original
            track.value = "C"
            settle()
            assertEquals(3, calls.size)
            assertSame(LyricsTranslationUiState.Idle, observed!!.translation)
        }
    }

    @Test(timeout = 15_000) fun changingModeCancelsLateTranslationAndKeepsRomanization() = runBlocking {
        harness {
            val mode = mutableStateOf(LyricsDisplayMode.Translated)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var observed: LyricsTranslationState? = null
            composition.setContent {
                val state = rememberLyricsTranslationState("A", listOf(LyricLine(0, "original")), "th", mode.value,
                    translate = { _, lines, _ ->
                        withContext(NonCancellable) { started.complete(Unit); release.await() }
                        LyricsTranslationResult.Translated(lines.map { it.copy(text = "late") })
                    }, romanize = { _, lines, _ -> LyricsRomanizationResult.Romanized(lines.map { it.copy(text = "romanized") }) })
                SideEffect { observed = state }
            }
            settle()
            assertTrue(started.isCompleted)
            mode.value = LyricsDisplayMode.Romanized
            settle()
            release.complete(Unit)
            settle()
            assertSame(LyricsTranslationUiState.Idle, observed!!.translation)
            assertEquals("romanized", (observed!!.romanization as LyricsTranslationUiState.Ready).lines.single().text)
        }
    }

    @Test(timeout = 15_000) fun unavailableTranslationSettlesOnceAndRetainsThePreferredMode() = runBlocking {
        harness {
            val mode = mutableStateOf(LyricsDisplayMode.Translated)
            val changed = mutableIntStateOf(0)
            var calls = 0
            var observed: LyricsTranslationState? = null
            composition.setContent {
                changed.intValue
                val state = rememberLyricsTranslationState("A", listOf(LyricLine(0, "original")), "th", mode.value,
                    translate = { _, _, _ -> calls++; LyricsTranslationResult.Unavailable },
                    romanize = { _, _, _ -> LyricsRomanizationResult.Unavailable })
                SideEffect { observed = state }
            }
            settle()
            repeat(3) { changed.intValue++; settle() }
            assertEquals(1, calls)
            assertEquals(LyricsDisplayMode.Translated, mode.value)
            assertSame(LyricsTranslationUiState.Unavailable, observed!!.translation)
            mode.value = LyricsDisplayMode.Original
            settle()
            mode.value = LyricsDisplayMode.Translated
            settle()
            assertEquals(2, calls)
        }
    }

    @Test(timeout = 15_000) fun cancelledRequestCannotClearTheLoadingStateOfANewerRequest() = runBlocking {
        harness {
            val mode = mutableStateOf(LyricsDisplayMode.Translated)
            val oldRelease = CompletableDeferred<Unit>()
            val newRelease = CompletableDeferred<Unit>()
            var calls = 0
            var observed: LyricsTranslationState? = null
            composition.setContent {
                val state = rememberLyricsTranslationState("A", listOf(LyricLine(0, "original")), "th", mode.value,
                    translate = { _, lines, _ ->
                        val call = ++calls
                        if (call == 1) withContext(NonCancellable) { oldRelease.await() } else newRelease.await()
                        LyricsTranslationResult.Translated(lines.map { it.copy(text = if (call == 1) "old" else "new") })
                    }, romanize = { _, _, _ -> LyricsRomanizationResult.Unavailable })
                SideEffect { observed = state }
            }
            try {
                settle()
                mode.value = LyricsDisplayMode.Original
                settle()
                mode.value = LyricsDisplayMode.Translated
                settle()
                assertEquals(2, calls)
                assertSame(LyricsTranslationUiState.Loading, observed!!.translation)
                oldRelease.complete(Unit)
                settle()
                assertSame(LyricsTranslationUiState.Loading, observed!!.translation)
                newRelease.complete(Unit)
                settle()
                assertEquals("new", (observed!!.translation as LyricsTranslationUiState.Ready).lines.single().text)
            } finally { oldRelease.complete(Unit); newRelease.complete(Unit) }
        }
    }

    @Test(timeout = 15_000) fun originalModeStartsWithoutTranslationWorkOrAnAnimation() = runBlocking {
        harness {
            var observed: LyricsTranslationState? = null
            composition.setContent {
                val state = rememberLyricsTranslationState("A", listOf(LyricLine(0, "original")), "th", LyricsDisplayMode.Original,
                    translate = { _, _, _ -> error("Original mode must not translate") },
                    romanize = { _, _, _ -> error("Original mode must not romanize") })
                SideEffect { observed = state }
            }
            settle()
            assertEquals(0, observed!!.transition)
            assertSame(LyricsTranslationUiState.Idle, observed!!.translation)
        }
    }
}
