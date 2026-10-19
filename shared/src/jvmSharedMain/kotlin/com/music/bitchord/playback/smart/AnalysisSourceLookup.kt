package com.music.bitchord.playback.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exhaust alternate catalogue queries before distinguishing a lookup failure from no match. */
suspend fun <T> findAnalysisSource(queries: Iterable<String>, lookup: suspend (String) -> T?): T? {
    var failure: Exception? = null
    for (query in queries) {
        currentCoroutineContext().ensureActive()
        try {
            lookup(query)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
    }
    failure?.let { throw it }
    return null
}
