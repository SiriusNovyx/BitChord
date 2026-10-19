package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackAnalysisStatusTest {
    @Test
    fun `an absent next track is not waiting for analysis`() {
        assertEquals(TrackAnalysisEligibility.ABSENT, SmartAnalysis().next.eligibility)
        assertEquals(TrackAnalysisEligibility.ABSENT, SmartAnalysis().current.eligibility)
    }

    @Test fun cachedCompletionDoesNotMakeAnUnsupportedTrackEligible() {
        for (reason in TrackAnalysisEligibility.entries.filter { it != TrackAnalysisEligibility.ELIGIBLE }) {
            val status = TrackAnalysisStatus(reason, TrackAnalysisState.ANALYSED)
            assertEquals(false, status.isMeasured)
        }
    }

    @Test fun eligibilityAndProcessingProgressAreIndependent() {
        for (state in TrackAnalysisState.entries) {
            val status = TrackAnalysisStatus(TrackAnalysisEligibility.ELIGIBLE, state)
            assertEquals(state in setOf(TrackAnalysisState.ANALYSED, TrackAnalysisState.REFINING), status.isMeasured)
        }
        assertEquals(TrackAnalysisState.FAILED,
            TrackAnalysisStatus(TrackAnalysisEligibility.UNKNOWN, TrackAnalysisState.FAILED).state)
    }
}
