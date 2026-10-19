package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine

internal class LyricFrameSchedule(
    private val lines: List<LyricLine>,
    private val animateGaps: Boolean,
    scrollLeadMinMs: Long = 0,
    scrollLeadMaxMs: Long = 0,
    backgroundLeadMs: Long = 0,
) {
    private val wordTimed = lines.any { it.isWordSynced || it.background?.isWordSynced == true }
    private val synced = lines.any { it.timeMs > 0 }
    private val boundaries = buildSet {
        lines.forEachIndexed { index, line ->
            add(line.timeMs)
            add(line.endMs)
            val previous = lines.getOrNull(index - 1)
            val lead = if (previous == null) scrollLeadMinMs else
                (line.timeMs - previous.endMs).coerceIn(scrollLeadMinMs, scrollLeadMaxMs)
            add(line.timeMs - lead)
            line.background?.let { backing ->
                add(backing.timeMs - backgroundLeadMs)
                add(backing.timeMs)
                add(backing.endMs)
            }
        }
    }.sorted()

    fun needsFrames(positionMs: Long): Boolean = wordTimed ||
        (animateGaps && synced && lines.lastOrNull { it.timeMs <= positionMs }?.isGap == true)

    fun nextBoundaryAfter(positionMs: Long): Long? {
        val found = boundaries.binarySearch(positionMs)
        return boundaries.getOrNull(if (found >= 0) found + 1 else -found - 1)
    }
}
