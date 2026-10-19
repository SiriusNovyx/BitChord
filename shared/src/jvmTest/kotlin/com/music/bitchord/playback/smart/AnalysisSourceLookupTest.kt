package com.music.bitchord.playback.smart

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnalysisSourceLookupTest {
    @Test fun laterQueryCanMatchAfterAnEarlierLookupFails() = runBlocking {
        val calls = mutableListOf<String>()
        val result = findAnalysisSource(listOf("first", "second", "unused")) {
            calls += it
            if (it == "first") throw IOException("Synthetic outage")
            "match"
        }
        assertEquals("match", result)
        assertEquals(listOf("first", "second"), calls)
    }

    @Test fun genuineNoMatchIsDifferentFromAnIncompleteFailedLookup() = runBlocking {
        assertNull(findAnalysisSource<String>(listOf("first", "second")) { null })
        val calls = mutableListOf<String>()
        assertFailsWith<IOException> {
            findAnalysisSource<String>(listOf("first", "second")) {
                calls += it
                if (it == "first") throw IOException("Synthetic outage")
                null
            }
        }
        assertEquals(listOf("first", "second"), calls)
    }

    @Test fun cancellationDoesNotStartAnotherQuery() = runBlocking {
        val calls = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            findAnalysisSource<String>(listOf("first", "second")) {
                calls += it
                throw CancellationException("View no longer needs analysis")
            }
        }
        assertEquals(listOf("first"), calls)
    }
}
