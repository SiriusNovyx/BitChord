package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

internal object DesktopStorage {
    val root: Path? by lazy {
        System.getProperty("bitchord.dataDir")?.takeIf(String::isNotBlank)?.let { Path.of(it).toAbsolutePath().normalize() }
    }

    fun install() {
        val directory = root ?: return
        Files.createDirectories(directory)
        Files.createDirectories(directory.resolve("tmp"))
        val probe = Files.createTempFile(directory, "bitchord-writable-", ".tmp")
        Files.delete(probe)
        // Must precede initialization of any DesktopPersistence or Preferences singleton.
        System.setProperty("java.util.prefs.PreferencesFactory", DesktopPortablePreferencesFactory::class.java.name)
        val nativeCache = directory.resolve("native-cache")
        Files.createDirectories(nativeCache)
        System.setProperty("org.bytedeco.javacpp.cachedir", nativeCache.toString())
        System.setProperty("skiko.data.path", directory.resolve("graphics-cache").toString())
    }

    fun directory(name: String, standard: () -> Path): Path = root?.resolve(name) ?: standard()
}

class DesktopPortablePreferencesFactory : PreferencesFactory {
    private val root = requireNotNull(DesktopStorage.root) { "bitchord.dataDir is required for portable preferences" }
    private val user = DesktopPortablePreferences(null, "", root.resolve("preferences/user"))
    private val system = DesktopPortablePreferences(null, "", root.resolve("preferences/system"))
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system
}
