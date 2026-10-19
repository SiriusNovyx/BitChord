package com.music.bitchord.desktop

import java.io.IOException

/** Retains processed PCM across short output writes, yielding to commands between attempts. */
internal class DesktopAudioWrite(
    private val samples: FloatArray,
    private val count: Int,
    val format: DesktopPcmFormat,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var offset = 0
    private var progressedAt = nowNanos()
    val complete: Boolean get() = offset == count
    val remainingFrames: Long get() = ((count - offset) / format.channels).toLong()

    init {
        require(count in 0..samples.size && format.channels > 0 && count % format.channels == 0)
    }

    fun resetDeadline() { progressedAt = nowNanos() }

    /** Returns only the frames actually accepted in this attempt. */
    fun write(outputFormat: DesktopPcmFormat = format, output: (FloatArray, Int) -> Int): Int {
        if (complete) return 0
        if (outputFormat.sampleRate != format.sampleRate || outputFormat.channels != format.channels) {
            throw IOException("Audio output format changed during a pending write")
        }
        val remaining = count - offset
        val pcm = if (offset == 0) samples else samples.copyOfRange(offset, count)
        val bytes = output(pcm, remaining)
        if (bytes < 0 || bytes > remaining * outputFormat.bytesPerSample || bytes % outputFormat.frameBytes != 0) {
            throw IOException("Audio output returned an invalid frame count")
        }
        val frames = bytes / outputFormat.frameBytes
        offset += frames * outputFormat.channels
        if (frames > 0) progressedAt = nowNanos()
        else if (nowNanos() - progressedAt >= 5_000_000_000L) throw IOException("Audio output stopped accepting samples")
        return frames
    }
}
