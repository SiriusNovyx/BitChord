package com.music.bitchord.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DesktopDiscordSessionTest {
    private class Connection : DesktopDiscordConnection {
        val messages = Channel<JsonObject?>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        @Volatile var fail = false
        override fun setActivity(activity: JsonObject?) {
            check(!fail) { "Disconnected" }
            messages.trySend(activity)
        }
        override fun checkActivity(activity: JsonObject?) { check(!fail) { "Disconnected" } }
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

    @Test fun `repeated enable does not open duplicate connections`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = Connection()
            val attempts = AtomicInteger()
            val session = DesktopDiscordSession(scope, { attempts.incrementAndGet(); connection }, {}, 10, 20)
            session.update(activity("One owner"))
            session.setEnabled(true)
            withTimeout(2_000) { connection.messages.receive() }
            repeat(3) { session.setEnabled(true) }
            delay(60)
            assertEquals(1, attempts.get())
            session.setEnabled(false)
            withTimeout(2_000) { connection.closed.await() }
        } finally { scope.cancel() }
    }

    @Test fun `a restarted Discord receives the snapshot without another playback event`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = Connection()
            val second = Connection()
            val attempts = AtomicInteger()
            val session = DesktopDiscordSession(scope, { if (attempts.incrementAndGet() == 1) first else second }, {}, 10, 20)
            session.update(activity("Still playing"))
            session.setEnabled(true)
            withTimeout(2_000) { first.messages.receive() }
            first.fail = true
            assertEquals(activity("Still playing"), withTimeout(2_000) { second.messages.receive() })
            withTimeout(2_000) { first.closed.await() }
            session.setEnabled(false)
        } finally { scope.cancel() }
    }

    @Test fun `gateway fallback is replayed after a local client disappears`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val available = AtomicBoolean(false)
            val local = Connection()
            val fallbacks = Channel<Unit>(Channel.UNLIMITED)
            val session = DesktopDiscordSession(scope, { if (available.get()) local else null }, {}, 10, 20)
            session.update(activity("Same activity")) { fallbacks.send(Unit) }
            session.setEnabled(true)
            withTimeout(2_000) { fallbacks.receive() }
            available.set(true)
            session.probe()
            withTimeout(2_000) { local.messages.receive() }
            available.set(false)
            local.fail = true
            session.probe()
            withTimeout(2_000) { fallbacks.receive() }
            session.setEnabled(false)
        } finally { scope.cancel() }
    }

    @Test fun `rapid disable and enable closes the late old connection before replacing it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val opening = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = Connection()
            val second = Connection()
            val attempts = AtomicInteger()
            val session = DesktopDiscordSession(scope, {
                if (attempts.incrementAndGet() == 1) {
                    withContext(NonCancellable) { opening.complete(Unit); release.await() }
                    first
                } else {
                    assertTrue(first.closed.isCompleted)
                    second
                }
            }, {}, 10, 20)
            session.update(activity("Latest"))
            session.setEnabled(true)
            withTimeout(2_000) { opening.await() }
            session.setEnabled(false)
            session.setEnabled(true)
            release.complete(Unit)
            assertEquals(activity("Latest"), withTimeout(2_000) { second.messages.receive() })
            assertTrue(first.messages.tryReceive().isFailure)
            assertEquals(2, attempts.get())
            session.setEnabled(false)
        } finally { scope.cancel() }
    }

    @Test fun `pause cancels a suspended gateway send before clearing the activity`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val cleared = CompletableDeferred<Unit>()
            val session = DesktopDiscordSession(scope, { null }, {}, 10, 20)
            session.update(activity("Old song")) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            session.setEnabled(true)
            withTimeout(2_000) { entered.await() }
            session.update(null) {
                assertTrue(cancelled.isCompleted)
                cleared.complete(Unit)
            }
            withTimeout(2_000) { cleared.await() }
            session.setEnabled(false)
        } finally { scope.cancel() }
    }

    @Test fun `reauthentication cancels the old fallback before starting its replacement`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = CompletableDeferred<Unit>()
            val replaced = CompletableDeferred<Unit>()
            val stopped = Channel<Unit>(Channel.UNLIMITED)
            val cancelled = AtomicInteger()
            val attempts = AtomicInteger()
            val session = DesktopDiscordSession(scope, { null }, {}, 10, 20,
                onSessionStopped = { stopped.trySend(Unit) })
            session.update(activity("Retained through login")) {
                if (attempts.incrementAndGet() == 1) first.complete(Unit)
                else {
                    assertEquals(1, cancelled.get())
                    assertTrue(stopped.tryReceive().isSuccess)
                    replaced.complete(Unit)
                }
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
            session.setEnabled(true)
            withTimeout(2_000) { first.await() }
            session.restart()
            withTimeout(2_000) { replaced.await() }
            session.setEnabled(false)
            withTimeout(2_000) { stopped.receive() }
            assertEquals(2, cancelled.get())
        } finally { scope.cancel() }
    }
}
