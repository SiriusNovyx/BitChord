package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopWebView2ProtocolTest {
    @Test fun acceptsOnlyFixedMethodsWithStringArguments() {
        assertEquals("onMinterCreated" to emptyList(), DesktopWebView2Protocol.message("[\"onMinterCreated\"]"))
        assertEquals("onObtainPoTokenResult" to listOf("key", "1,2"),
            DesktopWebView2Protocol.message("[\"onObtainPoTokenResult\",\"key\",\"1,2\"]"))
        listOf("[]", "null", "{}", "[\"close\"]", "[\"log\",1,2]", "[\"onMinterCreated\",\"extra\"]", "broken")
            .forEach { assertNull(DesktopWebView2Protocol.message(it), it) }
    }

    @Test fun rejectsOversizedPayload() {
        assertNull(DesktopWebView2Protocol.message(" ".repeat(1_048_577)))
    }
}
