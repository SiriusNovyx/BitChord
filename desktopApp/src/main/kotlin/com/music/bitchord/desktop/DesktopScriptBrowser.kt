package com.music.bitchord.desktop

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import netscape.javascript.JSObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

internal interface DesktopScriptBrowser {
    fun executeScript(script: String)
    fun close()
}

internal object DesktopBrowserPlatform {
    val usesWebView2: Boolean = DesktopPlatform.isWindows &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    val dispatcher: CoroutineDispatcher get() = if (usesWebView2) Dispatchers.Default else JavaFxBrowser.dispatcher
    val available: Boolean get() = if (usesWebView2) DesktopWebView2.available else JavaFxBrowser.available
}

internal object JavaFxBrowser {
    val available: Boolean by lazy {
        runCatching {
            val started = CountDownLatch(1)
            try { Platform.startup { started.countDown() } }
            catch (_: IllegalStateException) { started.countDown() }
            check(started.await(15, TimeUnit.SECONDS)) { "JavaFX startup timed out" }
            Platform.setImplicitExit(false)
            true
        }.getOrDefault(false)
    }

    private fun run(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }

    val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = run { block.run() }
        override fun isDispatchNeeded(context: CoroutineContext) = !Platform.isFxApplicationThread()
    }

    fun open(html: String, userAgent: String, bridge: Any, loaded: () -> Unit, failed: (String) -> Unit): DesktopScriptBrowser {
        val engine = WebEngine()
        engine.userAgent = userAgent
        engine.setOnError { failed(it.message ?: "WebEngine error") }
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            when (state) {
                Worker.State.SUCCEEDED -> {
                    (engine.executeScript("window") as JSObject).setMember("PoTokenWebView", bridge)
                    loaded()
                }
                Worker.State.FAILED -> failed("BotGuard page failed to load")
                else -> Unit
            }
        }
        engine.loadContent(html, "text/html")
        return object : DesktopScriptBrowser {
            override fun executeScript(script: String) { engine.executeScript(script) }
            override fun close() = run { engine.load("about:blank") }
        }
    }
}
