package com.music.bitchord.desktop

import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopStringsStartupTest {
    @Test fun `a fresh catalogue starts in the saved Thai language`() {
        val persistence = DesktopPersistence()
        val before = persistence.string("app_language")
        val urls = listOf(DesktopPersistence::class.java, com.music.bitchord.data.LocaleTags::class.java,
            kotlin.jvm.internal.Intrinsics::class.java, kotlinx.coroutines.flow.StateFlow::class.java,
            kotlinx.serialization.json.Json::class.java, kotlinx.serialization.KSerializer::class.java)
            .map { it.protectionDomain.codeSource.location }.distinct() +
            javaClass.getResource("/strings/th.xml")!!.toURI().resolve("../").toURL()
        try {
            persistence.saveString("app_language", "th")
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val strings = loader.loadClass("com.music.bitchord.desktop.DesktopStrings")
                val instance = strings.getField("INSTANCE").get(null)
                val settings = strings.getMethod("get", String::class.java, String::class.java)
                    .invoke(instance, "settings", "WRONG")
                assertEquals("การตั้งค่า", settings)
            }
        } finally {
            persistence.saveString("app_language", before)
        }
    }
}
