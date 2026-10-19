package com.music.bitchord.desktop

internal enum class DesktopPerformanceMode { EFFICIENT, BALANCED, PERFORMANCE }

internal data class DesktopVisualPolicy(
    val mode: DesktopPerformanceMode,
    val visible: Boolean,
    val minimized: Boolean,
) {
    val foreground: Boolean get() = visible && !minimized
    val animateArtwork: Boolean get() = foreground && mode != DesktopPerformanceMode.EFFICIENT
    val reduceMotion: Boolean get() = !foreground || mode == DesktopPerformanceMode.EFFICIENT
    val reduceBlur: Boolean get() = !foreground || mode == DesktopPerformanceMode.EFFICIENT
}
