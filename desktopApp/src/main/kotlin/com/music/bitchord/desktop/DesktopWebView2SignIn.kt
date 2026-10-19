package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal object DesktopWebView2SignIn {
    suspend fun capture(service: DesktopWebSignIn.Service): String = suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean()
        var session: DesktopWebView2.Session? = null
        var location = ""

        fun finish(result: Result<String>) {
            if (!done.compareAndSet(false, true)) return
            session?.close()
            if (continuation.isActive) continuation.resumeWith(result)
        }

        fun open() {
            session?.close()
            location = ""
            session = DesktopWebView2.Session { kind, value ->
                when (kind) {
                    "navigated" -> {
                        location = value
                        if (service == DesktopWebSignIn.Service.SPOTIFY && isServicePage(location, service.cookieUrl))
                            session?.cookies(service.cookieUrl)
                    }
                    "confirm" -> if (isServicePage(location, service.cookieUrl)) session?.cookies(service.cookieUrl)
                    "restart" -> open()
                    "cookies" -> {
                        val credential = when (service) {
                            DesktopWebSignIn.Service.YOUTUBE_MUSIC -> value.takeIf(DesktopBrowserCookies::hasSigningSecret)
                            DesktopWebSignIn.Service.SPOTIFY -> value.split(';').map(String::trim)
                                .firstOrNull { it.substringBefore('=') == "sp_dc" }?.substringAfter('=')?.takeIf(String::isNotBlank)
                        }
                        credential?.let { finish(Result.success(it)) }
                    }
                    "closed" -> finish(Result.failure(CancellationException("sign-in window closed")))
                    "error" -> finish(Result.failure(IllegalStateException(value)))
                }
            }.also { it.start("", service.startUrl, true, service.title) }
        }

        continuation.invokeOnCancellation {
            if (done.compareAndSet(false, true)) session?.close()
        }
        runCatching { check(DesktopWebView2.available); open() }.onFailure { finish(Result.failure(it)) }
    }

    internal fun isServicePage(url: String, expected: String): Boolean = runCatching {
        val actual = URI(url)
        actual.scheme == "https" && actual.host == URI(expected).host && (actual.port == -1 || actual.port == 443)
    }.getOrDefault(false)
}
