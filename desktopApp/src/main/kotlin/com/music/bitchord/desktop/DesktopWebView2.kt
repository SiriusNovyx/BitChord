package com.music.bitchord.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal object DesktopWebView2 {
    val available: Boolean by lazy {
        runCatching {
            DesktopAnalysisRuntime.loadNative("bitchord_webview")
            check(runtimeAvailableNative()) { "Microsoft Edge WebView2 Runtime is unavailable" }
            Runtime.getRuntime().addShutdownHook(Thread { closeAllNative() })
            true
        }.onFailure { DesktopTrackLog.log("WebView2 unavailable: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    fun open(html: String, loaded: () -> Unit, message: (String) -> Unit, failed: (String) -> Unit): DesktopScriptBrowser {
        check(available) { "Microsoft Edge WebView2 Runtime is required on Windows ARM64" }
        val page = "<html><head><script>${DesktopWebView2Protocol.bootstrap}</script></head>" +
            html.replaceFirst(Regex("(?is)<!doctype[^>]*>"), "").replaceFirst(Regex("(?is)<html[^>]*>"), "")
        val session = Session { kind, value ->
            when (kind) {
                "loaded" -> loaded()
                "message" -> message(value)
                "error" -> failed(value)
                "closed" -> failed("Browser closed")
            }
        }
        session.start(page, "", false, "BitChord BotGuard")
        return session
    }

    class Session(private val listener: (String, String) -> Unit) : DesktopScriptBrowser {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        private val closing = AtomicBoolean()
        private var id = 0L
        private val profile: Path = Files.createTempDirectory(
            DesktopStorage.directory("browser-sessions") {
                Path.of(System.getProperty("user.home"), ".local", "share", "bitchord", "browser-sessions")
            }.also(Files::createDirectories), "session-",
        )

        fun start(html: String, url: String, visible: Boolean, title: String) {
            check(id == 0L)
            id = createNative(this, profile.toString(), html, url, visible, title)
            check(id != 0L) { "WebView2 host could not start" }
        }

        // Called only by the native host; page messages never expose a Java object.
        @Suppress("unused")
        fun event(kind: String, value: String) {
            if (!closing.get()) scope.launch { listener(kind, value) }
        }

        override fun executeScript(script: String) {
            if (!closing.get()) check(executeNative(id, script)) { "Browser is closed or its command queue is full" }
        }

        fun cookies(url: String) {
            require(url in setOf("https://music.youtube.com/", "https://open.spotify.com/"))
            if (!closing.get()) check(cookiesNative(id, url)) { "Browser is closed" }
        }

        override fun close() {
            if (!closing.compareAndSet(false, true)) return
            closeNative(id)
            scope.cancel()
            // Only this session's newly created directory; never an imported browser profile.
            if (!profile.toFile().deleteRecursively()) {
                DesktopTrackLog.log("WebView2 session storage will be removed at shutdown")
                Runtime.getRuntime().addShutdownHook(Thread { profile.toFile().deleteRecursively() })
            }
        }
    }

    private external fun runtimeAvailableNative(): Boolean
    private external fun createNative(owner: Session, profile: String, html: String, url: String, visible: Boolean, title: String): Long
    private external fun executeNative(id: Long, script: String): Boolean
    private external fun cookiesNative(id: Long, url: String): Boolean
    private external fun closeNative(id: Long)
    private external fun closeAllNative()
}
