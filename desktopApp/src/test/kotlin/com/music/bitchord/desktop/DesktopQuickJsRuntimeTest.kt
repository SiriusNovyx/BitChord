package com.music.bitchord.desktop

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopQuickJsRuntimeTest {
    @Test fun cipherEngineExecutesJavaScriptAndPromises() = runBlocking {
        quickJs {
            assertEquals(42, evaluate<Int>("6 * 7"))
            assertEquals("cipher", evaluate<String>("await Promise.resolve('cipher')"))
        }
    }
}
