package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class DesktopUpdateCheckerTest {
    private val x64 = "BitChord-2-windows-x64-setup.exe"
    private val arm64 = "BitChord-2-windows-arm64-setup.exe"
    private val portable = "BitChord-2-windows-arm64-portable.zip"

    private fun assets(vararg names: String, state: String = "uploaded") = buildJsonObject {
        put("assets", buildJsonArray {
            for (name in names) add(buildJsonObject {
                put("name", name)
                put("state", state)
                put("browser_download_url", "https://example.invalid/$name")
            })
        })
    }

    @Test fun `ARM64 JVM selects native setup regardless of asset order`() {
        for (architecture in listOf("aarch64", "arm64", "ARM64")) {
            assertEquals("https://example.invalid/$arm64",
                DesktopUpdateChecker.installerUrl(assets(x64, portable, arm64), "windows", architecture))
        }
    }

    @Test fun `ARM64 can offer portable when no native installer exists`() {
        assertEquals("https://example.invalid/$portable",
            DesktopUpdateChecker.installerUrl(assets(x64, portable), "windows", "aarch64"))
    }

    @Test fun `ARM64 never substitutes an x64 update`() {
        assertNull(DesktopUpdateChecker.installerUrl(assets(x64), "windows", "aarch64"))
    }

    @Test fun `x64 update selection is preserved`() {
        for (architecture in listOf("amd64", "x86_64", "x64")) {
            assertEquals("https://example.invalid/$x64",
                DesktopUpdateChecker.installerUrl(assets(arm64, x64), "windows", architecture))
        }
    }

    @Test fun `unknown Windows architecture has no automatic asset selection`() {
        assertNull(DesktopUpdateChecker.installerUrl(assets(x64, arm64), "windows", "unknown"))
    }

    @Test fun `unfinished upload is not offered`() {
        assertNull(DesktopUpdateChecker.installerUrl(assets(arm64, state = "new"), "windows", "aarch64"))
    }
}
