package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.ui.player.LyricFrameSchedule
import org.junit.Assert.*
import org.junit.Test

class LyricFrameScheduleTest {
    private val lines = listOf(
        LyricLine(1_000, "first", sungUntilMs = 2_000),
        LyricLine(3_000, "second", sungUntilMs = 4_000),
    )

    @Test fun plainLinesSleepUntilScrollLeadAndVocalBoundaries() {
        val schedule = LyricFrameSchedule(lines, animateGaps = true, scrollLeadMinMs = 350, scrollLeadMaxMs = 500)
        assertFalse(schedule.needsFrames(1_500))
        assertEquals(650L, schedule.nextBoundaryAfter(0))
        assertEquals(1_000L, schedule.nextBoundaryAfter(650))
        assertEquals(2_000L, schedule.nextBoundaryAfter(1_000))
        assertEquals(2_500L, schedule.nextBoundaryAfter(2_000))
        assertEquals(3_000L, schedule.nextBoundaryAfter(2_500))
        assertNull(schedule.nextBoundaryAfter(4_000))
    }

    @Test fun gapAnimationRunsOnlyInTheGapAndTheStripDoesNotAnimateGaps() {
        val gapLines = listOf(LyricLine(0, ""), LyricLine(1_000, "vocal"))
        val panel = LyricFrameSchedule(gapLines, animateGaps = true)
        assertTrue(panel.needsFrames(500))
        assertFalse(panel.needsFrames(1_000))
        assertFalse(LyricFrameSchedule(gapLines, animateGaps = false).needsFrames(500))
    }

    @Test fun wordTimingAndTimedBackingKeepTheFrameClock() {
        val wordLine = LyricLine(1_000, "word", listOf(LyricWord(1_000, 1_500, "word")))
        assertTrue(LyricFrameSchedule(listOf(wordLine), false).needsFrames(1_200))
        val backing = lines[0].copy(background = wordLine)
        assertTrue(LyricFrameSchedule(listOf(backing), true).needsFrames(1_200))
    }

    @Test fun boundariesRecomputeAfterBackwardSeekAndIncludeBackingLead() {
        val backing = lines[0].copy(background = LyricLine(1_400, "answer", sungUntilMs = 1_800))
        val schedule = LyricFrameSchedule(listOf(backing), true, backgroundLeadMs = 250)
        assertEquals(1_150L, schedule.nextBoundaryAfter(1_000))
        assertEquals(1_000L, schedule.nextBoundaryAfter(500))
        assertEquals(1_400L, schedule.nextBoundaryAfter(1_150))
        assertEquals(1_800L, schedule.nextBoundaryAfter(1_400))
    }
}
