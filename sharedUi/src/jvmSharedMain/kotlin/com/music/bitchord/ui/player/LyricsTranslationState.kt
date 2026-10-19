package com.music.bitchord.ui.player

import androidx.compose.runtime.*
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.LyricsDisplayMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class LyricsTranslationState(
    val translation: LyricsTranslationUiState,
    val romanization: LyricsTranslationUiState,
    val transition: Int,
)

/** The saved mode owns the operation; a completed request never changes the user's choice. */
@Composable
internal fun rememberLyricsTranslationState(
    trackId: String,
    lyrics: List<LyricLine>?,
    targetLanguage: String,
    mode: LyricsDisplayMode,
    translate: suspend (String, List<LyricLine>, String) -> LyricsTranslationResult,
    romanize: suspend (String, List<LyricLine>, String) -> LyricsRomanizationResult,
): LyricsTranslationState {
    var translation by remember(trackId, lyrics, targetLanguage) { mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle) }
    var romanization by remember(trackId, lyrics, targetLanguage) { mutableStateOf<LyricsTranslationUiState>(LyricsTranslationUiState.Idle) }
    val requestOwners = remember(trackId, lyrics, targetLanguage) { mutableMapOf<LyricsDisplayMode, Any>() }
    var transition by remember(trackId) { mutableIntStateOf(0) }
    var lastMode by remember(trackId) { mutableStateOf(mode) }
    LaunchedEffect(trackId, lyrics, targetLanguage, mode) {
        val changedMode = lastMode != mode
        lastMode = mode
        if (mode == LyricsDisplayMode.Original) {
            if (changedMode) transition++
            return@LaunchedEffect
        }
        val lines = lyrics.orEmpty()
        if (lines.isEmpty()) return@LaunchedEffect
        val state = if (mode == LyricsDisplayMode.Translated) translation else romanization
        if (state is LyricsTranslationUiState.Ready) {
            if (changedMode) transition++
            return@LaunchedEffect
        }
        if (state is LyricsTranslationUiState.SameLanguage ||
            state is LyricsTranslationUiState.Unavailable && !changedMode) return@LaunchedEffect
        val owner = Any()
        requestOwners[mode] = owner
        fun update(value: LyricsTranslationUiState) {
            if (requestOwners[mode] !== owner) return
            if (mode == LyricsDisplayMode.Translated) translation = value else romanization = value
        }
        update(LyricsTranslationUiState.Loading)
        try {
            val result = if (mode == LyricsDisplayMode.Translated) {
                when (val answer = translate(trackId, lines, targetLanguage)) {
                    is LyricsTranslationResult.Translated -> LyricsTranslationUiState.Ready(answer.lines)
                    is LyricsTranslationResult.SameLanguage -> LyricsTranslationUiState.SameLanguage
                    LyricsTranslationResult.Unavailable -> LyricsTranslationUiState.Unavailable
                }
            } else {
                when (val answer = romanize(trackId, lines, targetLanguage)) {
                    is LyricsRomanizationResult.Romanized -> LyricsTranslationUiState.Ready(answer.lines)
                    LyricsRomanizationResult.AlreadyRomanized -> LyricsTranslationUiState.SameLanguage
                    LyricsRomanizationResult.Unavailable -> LyricsTranslationUiState.Unavailable
                }
            }
            currentCoroutineContext().ensureActive()
            update(result)
            if (result is LyricsTranslationUiState.Ready) transition++
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            update(LyricsTranslationUiState.Unavailable)
        } finally {
            val current = if (mode == LyricsDisplayMode.Translated) translation else romanization
            if (current is LyricsTranslationUiState.Loading) update(LyricsTranslationUiState.Idle)
            if (requestOwners[mode] === owner) requestOwners.remove(mode)
        }
    }
    return LyricsTranslationState(translation, romanization, transition)
}
