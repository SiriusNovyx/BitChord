package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsDisplayModeTest {
    @Test fun missingOrUnknownSavedPreferenceUsesOriginalLyrics() {
        for (value in listOf(null, "", "future-mode")) assertEquals(LyricsDisplayMode.Original, LyricsDisplayMode.fromStored(value))
    }

    @Test fun allModesRestoreFromTheirStableStorageKey() {
        for (mode in LyricsDisplayMode.entries) assertEquals(mode, LyricsDisplayMode.fromStored(mode.storageKey))
    }
}
