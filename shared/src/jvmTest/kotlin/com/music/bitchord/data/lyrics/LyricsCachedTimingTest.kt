package com.music.bitchord.data.lyrics

import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LyricsCachedTimingTest {
    private fun key(track: String, target: String, texts: List<String>): String {
        val persisted = track + "\u0000" + target + texts.joinToString("", transform = { "\u0000$it" })
        return MessageDigest.getInstance("SHA-256").digest(persisted.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun translationCacheRebuildsEachCallersLeadAndBackingTimings() = runBlocking {
        val oldDirectory = LyricsTranslation.cacheDir
        val directory = Files.createTempDirectory("bitchord-lyrics-timing-")
        val cache = Files.createDirectory(directory.resolve("lyrics_translation_v3"))
        val track = UUID.randomUUID().toString()
        val file = cache.resolve(key(track, "th", listOf("hello world", "oh")) + ".json.gz")
        GZIPOutputStream(Files.newOutputStream(file)).use {
            it.write("""{"version":3,"sourceLanguage":"en","targetLanguage":"th","texts":["สวัสดี","โอ้"]}""".toByteArray())
        }
        fun lines(start: Long) = listOf(LyricLine(start, "hello world",
            words = listOf(LyricWord(start, start + 500, "hello"), LyricWord(start + 500, start + 1_000, "world")),
            background = LyricLine(start + 100, "oh")))
        try {
            LyricsTranslation.cacheDir = directory.toFile()
            for (start in listOf(1_000L, 8_000L)) {
                val translated = assertIs<LyricsTranslation.Result.Translated>(LyricsTranslation.translate(track, lines(start), "th"))
                assertTrue(translated.fromCache)
                val line = translated.lines.single()
                assertEquals("สวัสดี", line.text)
                assertEquals(start, line.timeMs)
                assertEquals(start, line.words.first().startMs)
                assertEquals(start + 100, line.background!!.timeMs)
                assertEquals("โอ้", line.background!!.text)
            }
        } finally {
            LyricsTranslation.cacheDir = oldDirectory
            Files.deleteIfExists(file)
            Files.deleteIfExists(cache)
            Files.deleteIfExists(directory)
        }
    }
}
