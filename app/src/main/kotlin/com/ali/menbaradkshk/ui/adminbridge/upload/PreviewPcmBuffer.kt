package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException

/** Fixed-size PCM16 stereo pre-buffer for a short preview excerpt (decode ahead of playback). */
internal class PreviewPcmBuffer(frames: Long, private val exact: Boolean) {
    init { require(frames in 1..(Int.MAX_VALUE / 4).toLong()) }
    val bytes = ByteArray((frames * 4).toInt())
    private var filled = 0
    val full: Boolean get() = filled == bytes.size

    /** Returns true when bytes were taken. With [exact], decoding past the end is an error. */
    fun append(source: ByteArray, length: Int): Boolean {
        if (length <= 0 || length % 4 != 0 || length > source.size) throw IOException("Misaligned preview decode")
        val room = bytes.size - filled
        if (exact && length > room) throw IOException("Extra preview frames")
        val take = minOf(room, length)
        if (take == 0) return false
        System.arraycopy(source, 0, bytes, filled, take)
        filled += take
        return true
    }
}
