package com.music.bitchord.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDiscordSessionTest {
    private class Connection : DesktopDiscordConnection {
        val messages = Channel<JsonObject?>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        var fail = false
        override fun setActivity(activity: JsonObject?) {
            check(!fail) { "Disconnected" }
            messages.trySend(activity)
        }
        override fun close() { closed.complete(Unit) }
    }

    private fun activity(title: String) = buildJsonObject { put("details", title) }

    @Test fun `restored enabled state connects and publishes the already playing snapshot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = Connection()
            val session = DesktopDiscordSession(scope, { connection }, {}, 10, 20)
            session.update(activity("Playing before connection"))
            session.setEnabled(true)
            assertEquals(activity("Playing before connection"), withTimeout(2_000) { connection.messages.receive() })
            session.setEnabled(false)
            withTimeout(2_000) { connection.closed.await() }
        } finally { scope.cancel() }
    }

    @Test fun `Discord appearing after startup receives the latest snapshot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val attempted = CompletableDeferred<Unit>()
            val connection = Connection()
            var attempts = 0
            val session = DesktopDiscordSession(scope, {
                if (++attempts == 1) { attempted.complete(Unit); null } else connection
            }, {}, 10, 20)
            session.setEnabled(true)
            withTimeout(2_000) { attempted.await() }
            session.update(activity("Latest"))
            assertEquals(activity("Latest"), withTimeout(2_000) { connection.messages.receive() })
            assertTrue(attempts >= 2)
            session.setEnabled(false)
        } finally { scope.cancel() }
    }

    @Test fun `a failed send reconnects and replays without another playback change`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val failed = Connection().apply { fail = true }
            val replacement = Connection()
            var attempts = 0
            val session = DesktopDiscordSession(scope, { if (++attempts == 1) failed else replacement }, {}, 10, 20)
            session.update(activity("Same track"))
            session.setEnabled(true)
            assertEquals(activity("Same track"), withTimeout(2_000) { replacement.messages.receive() })
            withTimeout(2_000) { failed.closed.await() }
            session.setEnabled(false)
        } finally { scope.cancel() }
    }

    @Test fun `disable closes a connection that finishes opening after cancellation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val opening = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val connection = Connection()
            val session = DesktopDiscordSession(scope, {
                withContext(NonCancellable) { opening.complete(Unit); release.await() }
                connection
            }, {}, 10, 20)
            session.update(activity("Must not publish"))
            session.setEnabled(true)
            withTimeout(2_000) { opening.await() }
            session.setEnabled(false)
            release.complete(Unit)
            withTimeout(2_000) { connection.closed.await() }
            assertTrue(connection.messages.tryReceive().isFailure)
        } finally { scope.cancel() }
    }

    @Test fun `enabling and display changes replay the retained snapshot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = Connection()
            val session = DesktopDiscordSession(scope, { connection }, {}, 10, 20)
            session.update(activity("Initial"))
            session.setEnabled(true)
            assertEquals(activity("Initial"), withTimeout(2_000) { connection.messages.receive() })
            session.update(activity("Changed display"))
            assertEquals(activity("Changed display"), withTimeout(2_000) { connection.messages.receive() })
            session.update(null)
            assertEquals(null, withTimeout(2_000) { connection.messages.receive() })
            session.setEnabled(false)
        } finally { scope.cancel() }
    }
}
