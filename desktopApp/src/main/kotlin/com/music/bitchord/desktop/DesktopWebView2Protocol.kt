package com.music.bitchord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object DesktopWebView2Protocol {
    private val arities = mapOf(
        "downloadAndRunBotguard" to 0, "onJsInitializationError" to 1,
        "onRunBotguardResult" to 1, "onMinterCreated" to 0,
        "onObtainPoTokenError" to 2, "onObtainPoTokenResult" to 2, "log" to 2,
    )

    fun message(json: String): Pair<String, List<String>>? {
        if (json.length > 1_048_576) return null
        val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
        val method = (array.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (arities[method] != array.size - 1) return null
        val args = array.drop(1).map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: return null
        }
        return method to args
    }

    val bootstrap: String = """
        window.PoTokenWebView = Object.freeze(Object.fromEntries(
            ${JsonArray(arities.keys.map(::JsonPrimitive))}.map(function(name) {
                return [name, function() {
                    window.chrome.webview.postMessage([name].concat(Array.from(arguments, String)));
                }];
            })
        ));
    """.trimIndent()
}
