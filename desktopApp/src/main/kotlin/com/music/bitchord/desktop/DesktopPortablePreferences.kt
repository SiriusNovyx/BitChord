package com.music.bitchord.desktop

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.Properties
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException

internal class DesktopPortablePreferences(parent: AbstractPreferences?, name: String, private val directory: Path) :
    AbstractPreferences(parent, name) {
    private val file = directory.resolve("preferences.properties")
    private val values = Properties()

    init {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("node-name"), name)
        reload()
    }

    private fun reload() {
        values.clear()
        if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(values::load)
    }

    override fun putSpi(key: String, value: String) {
        values.setProperty(key, value)
        flushSpi()
    }
    override fun getSpi(key: String): String? = values.getProperty(key)
    override fun removeSpi(key: String) {
        values.remove(key)
        flushSpi()
    }
    override fun removeNodeSpi() {
        Files.deleteIfExists(file)
        Files.deleteIfExists(directory.resolve("node-name"))
        Files.delete(directory)
    }
    override fun keysSpi(): Array<String> = values.stringPropertyNames().toTypedArray()
    override fun childrenNamesSpi(): Array<String> = Files.list(directory).use { paths ->
        paths.filter { Files.isRegularFile(it.resolve("node-name")) }
            .map { Files.readString(it.resolve("node-name")) }.toArray { arrayOfNulls<String>(it) }
    }.filterNotNull().toTypedArray()
    override fun childSpi(name: String): AbstractPreferences {
        val id = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DesktopPortablePreferences(this, name, directory.resolve(id))
    }
    override fun syncSpi() = reload()
    override fun flushSpi() {
        val temporary = Files.createTempFile(directory, "preferences-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { values.store(it, "BitChord portable preferences") }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: java.io.IOException) {
            throw BackingStoreException(error)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
