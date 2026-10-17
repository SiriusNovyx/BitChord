package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVisualPolicyTest {
    @Test fun `background never decodes artwork even in performance mode`() {
        DesktopPerformanceMode.entries.forEach { mode ->
            assertFalse(DesktopVisualPolicy(mode, visible = false, minimized = false).animateArtwork)
            assertFalse(DesktopVisualPolicy(mode, visible = true, minimized = true).animateArtwork)
        }
    }

    @Test fun `balanced restores artwork when visible`() {
        assertTrue(DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, true, false).animateArtwork)
        assertFalse(DesktopVisualPolicy(DesktopPerformanceMode.BALANCED, true, false).reduceBlur)
    }

    @Test fun `efficient uses static artwork and reduced effects`() {
        val efficient = DesktopVisualPolicy(DesktopPerformanceMode.EFFICIENT, true, false)
        assertFalse(efficient.animateArtwork)
        assertTrue(efficient.reduceMotion)
        assertTrue(efficient.reduceBlur)
    }
}
