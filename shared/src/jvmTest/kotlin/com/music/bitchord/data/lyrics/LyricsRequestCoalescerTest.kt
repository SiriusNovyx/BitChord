package com.music.bitchord.data.lyrics

import java.io.IOException
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyricsRequestCoalescerTest {
    @Test fun identicalRequestsShareWorkAndOneCancelledViewDoesNotCancelTheOther() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coalescer = LyricsRequestCoalescer<String>(scope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.run("same") { calls++; started.complete(Unit); release.await(); "translated" }
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.run("same") { error("Duplicate request") }
            }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals("translated", second.await())
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun cancellingTheLastViewStopsTransportAndAllowsAFreshRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coalescer = LyricsRequestCoalescer<String>(scope)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.run("same") {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                }
            }
            started.await()
            request.cancelAndJoin()
            withTimeout(1_000) { cancelled.await() }
            assertEquals("new", coalescer.run("same") { "new" })
        } finally { scope.cancel() }
    }

    @Test fun failuresDoNotPoisonLaterRequestsAndKeysStayIndependent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coalescer = LyricsRequestCoalescer<String>(scope)
        try {
            assertFailsWith<IOException> { coalescer.run("same") { throw IOException("Synthetic failure") } }
            assertEquals("recovered", coalescer.run("same") { "recovered" })
            val first = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.run("translation:th") { first.complete(Unit); release.await(); "thai" }
            }
            first.await()
            assertEquals("latin", coalescer.run("romanization") { "latin" })
            assertTrue(request.isActive)
            release.complete(Unit)
            assertEquals("thai", request.await())
        } finally { scope.cancel() }
    }
}
