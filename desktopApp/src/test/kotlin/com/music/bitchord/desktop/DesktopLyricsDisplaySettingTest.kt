package com.music.bitchord.desktop

import com.music.bitchord.data.settings.LyricsDisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopLyricsDisplaySettingTest {
    @Test fun `saved display mode is restored by a new settings owner`() {
        val setting = DesktopLyricsDisplaySetting()
        val original = setting.mode.value
        try {
            for (mode in listOf(LyricsDisplayMode.Translated, LyricsDisplayMode.Romanized, LyricsDisplayMode.Original)) {
                setting.set(mode)
                assertEquals(mode, DesktopLyricsDisplaySetting().mode.value)
            }
        } finally { setting.set(original) }
    }
}
