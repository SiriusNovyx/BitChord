package com.music.bitchord.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keeps driver discovery off callers, with at most one pending refresh during a device storm. */
internal class DesktopAudioDeviceCatalog(
    scope: CoroutineScope,
    discover: () -> List<DesktopAudioDevice>,
    onChanged: () -> Unit = {},
) {
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val _devices = MutableStateFlow<List<DesktopAudioDevice>>(emptyList())
    val devices: StateFlow<List<DesktopAudioDevice>> = _devices

    init {
        scope.launch {
            for (ignored in pending) {
                try {
                    val next = discover().toList()
                    if (next != _devices.value) {
                        _devices.value = next
                        onChanged()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retain the last complete list while an endpoint or its driver is changing.
                }
            }
        }
        refresh()
    }

    fun refresh() { pending.trySend(Unit) }
}
