package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackAnalysisStatusTest {
    @Test
    fun `an absent next track is not waiting for analysis`() {
        assertEquals("ABSENT", SmartAnalysis().next.name)
    }
}
