package com.music.bitchord.data.settings

enum class LyricsDisplayMode(val storageKey: String) {
    Original("original"),
    Romanized("romanized"),
    Translated("translated");

    companion object {
        fun fromStored(value: String?): LyricsDisplayMode = entries.firstOrNull { it.storageKey == value } ?: Original
    }
}
