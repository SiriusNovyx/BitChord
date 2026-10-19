package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWebView2SignInTest {
    @Test fun acceptsOnlyTheExactHttpsServiceHost() {
        val target = "https://music.youtube.com/"
        assertTrue(DesktopWebView2SignIn.isServicePage("https://music.youtube.com/account", target))
        listOf("http://music.youtube.com/", "https://music.youtube.com.evil.test/", "https://music.youtube.com:444/", "javascript:alert(1)", "invalid")
            .forEach { assertFalse(DesktopWebView2SignIn.isServicePage(it, target), it) }
    }
}
