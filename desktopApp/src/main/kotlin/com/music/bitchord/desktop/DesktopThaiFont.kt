package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.wdb_bangna
import com.music.bitchord.data.LocaleTags
import org.jetbrains.compose.resources.Font

internal fun useDesktopThaiFont(appLanguage: String, translationLanguage: String): Boolean =
    LocaleTags.normalizeAppTag(appLanguage) == "th" || LocaleTags.normalizeAppTag(translationLanguage) == "th"

@Composable
internal fun desktopThaiFontFamily(): FontFamily = FontFamily(Font(Res.font.wdb_bangna))
