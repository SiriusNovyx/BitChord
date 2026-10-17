package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

val LocalThaiFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
internal fun thaiTextStyle(text: String, style: TextStyle): TextStyle {
    val family = LocalThaiFontFamily.current
    return if (family != null && text.any { it in '\u0e00'..'\u0e7f' }) style.copy(fontFamily = family) else style
}
