package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopModuleRuntimeTest {
    @Test fun javaScriptModuleRunsWithoutPlatformNativeAccess() {
        DesktopModuleRuntime.context().use { context ->
            val result = context.eval("js", "JSON.stringify({tracks:[]})").asString()
            assertTrue(result.contains("tracks"))
        }
    }
}
