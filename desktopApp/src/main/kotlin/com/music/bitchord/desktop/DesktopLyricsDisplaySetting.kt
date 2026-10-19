package com.music.bitchord.desktop

import com.music.bitchord.data.settings.LyricsDisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DesktopLyricsDisplaySetting(private val persistence: DesktopPersistence = DesktopPersistence()) {
    private val selected = MutableStateFlow(LyricsDisplayMode.fromStored(persistence.string(KEY, "")))
    val mode: StateFlow<LyricsDisplayMode> = selected.asStateFlow()

    fun set(mode: LyricsDisplayMode) {
        selected.value = mode
        persistence.saveString(KEY, mode.storageKey)
    }

    private companion object { const val KEY = "lyrics_display_mode" }
}
