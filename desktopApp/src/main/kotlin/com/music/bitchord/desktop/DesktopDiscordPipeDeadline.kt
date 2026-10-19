package com.music.bitchord.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface DiscordPipeKernel : StdCallLibrary {
    fun CancelSynchronousIo(thread: WinNT.HANDLE): Boolean
}

/** Cancel only this operation's thread; closing RandomAccessFile does not interrupt a pipe read. */
internal fun <T> withDiscordPipeDeadline(timeoutMs: Long = 2_000, operation: () -> T): T {
    if (!Platform.isWindows()) return operation()
    return DiscordPipeDeadlines.run(timeoutMs, operation)
}

private object DiscordPipeDeadlines {
    private val cancellation = Native.load("kernel32", DiscordPipeKernel::class.java)
    private val timer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "discord-pipe-deadline").apply { isDaemon = true }
    }

    fun <T> run(timeoutMs: Long, operation: () -> T): T {
        require(timeoutMs > 0)
        val kernel = Kernel32.INSTANCE
        val owner = kernel.OpenThread(WinNT.THREAD_TERMINATE, false, kernel.GetCurrentThreadId())
            ?: throw IOException("Cannot create Discord pipe deadline")
        val lock = Any()
        var active = true
        var expired = false
        var alarm: ScheduledFuture<*>? = null
        try {
            alarm = timer.scheduleAtFixedRate({
                synchronized(lock) {
                    if (active) {
                        expired = true
                        // Retry cancellation if the owner was descheduled just before entering I/O.
                        cancellation.CancelSynchronousIo(owner)
                    }
                }
            }, timeoutMs, 25, TimeUnit.MILLISECONDS)
            val result = operation()
            if (synchronized(lock) { expired }) throw SocketTimeoutException("Discord pipe deadline exceeded")
            return result
        } catch (error: IOException) {
            if (synchronized(lock) { expired } && error !is SocketTimeoutException) {
                throw SocketTimeoutException("Discord pipe deadline exceeded").apply { initCause(error) }
            }
            throw error
        } finally {
            synchronized(lock) {
                // Fence the timer before this pooled thread can perform any unrelated I/O.
                active = false
                alarm?.cancel(false)
                kernel.CloseHandle(owner)
            }
        }
    }
}
