package com.music.bitchord.data.lyrics

import kotlinx.coroutines.*

/** Share an in-flight text request without making one visible panel own another panel's work. */
internal class LyricsRequestCoalescer<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Flight<T>(val result: Deferred<T>, var waiters: Int = 0)
    private val lock = Any()
    private val flights = mutableMapOf<String, Flight<T>>()

    suspend fun run(key: String, request: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val flight = synchronized(lock) {
            flights.getOrPut(key) {
                Flight(scope.async(start = CoroutineStart.LAZY) { request() })
            }.also { it.waiters++ }
        }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            val cancel = synchronized(lock) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    !flight.result.isCompleted
                } else false
            }
            if (cancel) flight.result.cancel()
        }
    }
}
