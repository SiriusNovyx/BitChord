package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTranslationSettingTest {
    @Test fun `player follows saved translation choice and later changes`() {
        val original = DesktopTranslationSetting.language.value
        try {
            DesktopTranslationSetting.set("th")
            assertEquals("th", DesktopPlayerSettings.translationLanguage.value)
            DesktopTranslationSetting.set("ja")
            assertEquals("ja", DesktopPlayerSettings.translationLanguage.value)
            DesktopTranslationSetting.set("")
            assertEquals("", DesktopPlayerSettings.translationLanguage.value)
        } finally {
            DesktopTranslationSetting.set(original)
        }
    }
}
