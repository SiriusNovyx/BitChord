package com.music.bitchord.desktop

import java.io.InputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class SpotifyPcmException(val code: Int = 0) : Exception("Spotify audio could not be decoded (code $code). Reconnect Spotify playback and try again.")
internal class SpotifyPcmProtocol(private val input: InputStream) {
    private val stream = DataInputStream(input)
    private var ready = false
    init {
        try {
            val magic = ByteArray(4).also(stream::readFully)
            if (!magic.contentEquals("BCSP".toByteArray()) || number() != 1 || number() != 44_100 || number() != 2) {
                throw SpotifyPcmException()
            }
        } catch (_: Exception) { throw SpotifyPcmException() }
    }

    private fun number(): Int = Integer.reverseBytes(stream.readInt())

    fun next(): FloatArray? = try {
        var kind = stream.readUnsignedByte()
        var count = number()
        if (kind == 0 && count == 0 && !ready) {
            ready = true
            kind = stream.readUnsignedByte()
            count = number()
        }
        when {
            kind == 3 && count in 1..999 -> throw SpotifyPcmException(count)
            !ready -> throw SpotifyPcmException()
            kind == 2 && count == 0 -> null
            kind != 1 || count !in 2..8192 || count % 2 != 0 -> throw SpotifyPcmException()
            else -> {
                val bytes = ByteArray(count * 4).also(stream::readFully)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(count) { buffer.float.also { if (!it.isFinite()) throw SpotifyPcmException() } }
            }
        }
    } catch (failure: Exception) { throw (failure as? SpotifyPcmException ?: SpotifyPcmException()) }
}
