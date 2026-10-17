package com.music.bitchord.desktop

import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopThaiFontTest {
    @Test fun `Thai app or translation selects the provided family`() {
        assertTrue(useDesktopThaiFont("th-TH", ""))
        assertTrue(useDesktopThaiFont("en", "th"))
        assertFalse(useDesktopThaiFont("en", "ja"))
        assertTrue(DesktopStrings.available.any { it.tag == "th" })
    }

    @Test fun `bundled font is WDB Bangna and covers Thai marks`() {
        val resource = requireNotNull(javaClass.getResourceAsStream(
            "/composeResources/bitchord.desktopapp.generated.resources/font/wdb_bangna.ttf"))
        val font = resource.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        assertEquals("WDB Bangna", font.family)
        assertEquals(-1, font.canDisplayUpTo("ภาษาไทย น้ำ กี่ เนื้อเพลง ๑๒๓"))
    }
}
