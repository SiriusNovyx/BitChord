package com.music.bitchord.desktop

import java.nio.file.Files
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPortablePreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `preferences persist unicode and removal across instances`() {
        val folder = temporary.newFolder().toPath()
        val first = DesktopPortablePreferences(null, "", folder).node("settings")
        first.put("language", "เนื้อเพลง")
        first.put("mode", "EFFICIENT")
        val second = DesktopPortablePreferences(null, "", folder).node("settings")
        assertEquals("เนื้อเพลง", second.get("language", null))
        second.remove("mode")
        assertNull(DesktopPortablePreferences(null, "", folder).node("settings").get("mode", null))
    }

    @Test fun `nested nodes enumerate and remove only their own data`() {
        val folder = temporary.newFolder().toPath()
        val root = DesktopPortablePreferences(null, "", folder)
        root.node("account/saved").put("value", "one")
        root.node("other").put("value", "two")
        root.node("account").removeNode()
        val restored = DesktopPortablePreferences(null, "", folder)
        assertEquals(listOf("other"), restored.childrenNames().toList())
        assertEquals("two", restored.node("other").get("value", null))
        assertEquals(0L, Files.walk(folder).use { it.filter { file -> file.toString().endsWith(".tmp") }.count() })
    }
}
