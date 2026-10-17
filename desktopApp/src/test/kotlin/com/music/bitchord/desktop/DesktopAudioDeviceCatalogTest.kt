package com.music.bitchord.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

class DesktopAudioDeviceCatalogTest {
    @Test fun `blocked discovery preserves reads and coalesces device notifications`() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val firstPublished = CountDownLatch(1)
        val thirdFinished = CountDownLatch(1)
        val calls = AtomicInteger()
        val speakers = DesktopAudioDevice("speakers", "Speakers", "")
        val headphones = DesktopAudioDevice("bluetooth", "Headphones", "")
        try {
            val catalog = DesktopAudioDeviceCatalog(scope, discover = {
                when (calls.incrementAndGet()) {
                    1 -> listOf(speakers)
                    2 -> {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        listOf(headphones)
                    }
                    else -> { thirdFinished.countDown(); listOf(headphones) }
                }
            }, onChanged = { firstPublished.countDown() })
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS))
            catalog.refresh()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(100) {
                catalog.refresh()
                assertEquals(listOf(speakers), catalog.devices.value)
            }
            assertEquals(2, calls.get())
            release.countDown()
            assertTrue(thirdFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(headphones), catalog.devices.value)
            assertEquals(3, calls.get())
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test fun `driver failure retains the last complete device list`() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val published = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val calls = AtomicInteger()
        val speakers = DesktopAudioDevice("speakers", "Speakers", "")
        try {
            val catalog = DesktopAudioDeviceCatalog(scope, discover = {
                if (calls.incrementAndGet() > 1) {
                    failed.countDown()
                    error("Endpoint changing")
                }
                listOf(speakers)
            }, onChanged = { published.countDown() })
            assertTrue(published.await(5, TimeUnit.SECONDS))
            catalog.refresh()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(speakers), catalog.devices.value)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }
}
