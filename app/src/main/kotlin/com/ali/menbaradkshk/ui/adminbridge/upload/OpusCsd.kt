package com.ali.menbaradkshk.ui.adminbridge.upload

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dormant validator for only the supported 48 kHz mapping-0 Android Opus CSD shapes. */
internal class OpusCsd(private val nativeOrder: ByteOrder = ByteOrder.nativeOrder()) {
    internal data class Format(
        val channels: Int,
        val preSkip48k: Int,
        val seekPreroll48k: Int,
        internal val unified: Boolean,
    )
    private var accepted: Format? = null
    private var failed = false

    fun observe(mime: String, rate: Int, channels: Int, zero: ByteArray?, one: ByteArray?, two: ByteArray?): Format {
        check(!failed)
        try {
            require(mime == "audio/opus" && rate == 48_000 && channels in 1..2)
            requireNotNull(zero)
            val unified = zero.size == 83
            val header: ByteArray
            val delay: Long
            val preroll: Long
            if (unified) {
                require(one == null && two == null)
                val b = ByteBuffer.wrap(zero).order(nativeOrder)
                marker(b, "AOPUSHDR", 19)
                header = ByteArray(19).also { b.get(it) }
                marker(b, "AOPUSDLY", 8)
                delay = b.long
                marker(b, "AOPUSPRL", 8)
                preroll = b.long
                require(!b.hasRemaining())
            } else {
                require(zero.size == 19 && one?.size == 8 && two?.size == 8)
                header = zero
                delay = ByteBuffer.wrap(one).order(nativeOrder).long
                preroll = ByteBuffer.wrap(two).order(nativeOrder).long
            }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(ByteArray(8).also { b.get(it) }.contentEquals("OpusHead".toByteArray(Charsets.US_ASCII)))
            require(b.get().toInt() == 1 && b.get().toInt() == channels)
            val skip = b.short.toInt() and 65535
            require(b.int == 48_000 && b.short.toInt() == 0 && b.get().toInt() == 0)
            // AOSP derives delay by flooring this exact sample-to-ns conversion.
            require(delay == skip * 1_000_000_000L / 48_000)
            // Preroll is not encoder delay. Only admit the inspected AOSP 80 ms policy.
            require(preroll == 80_000_000L)
            val parsed = Format(channels, skip, 3840, unified)
            require(accepted == null || accepted == parsed) { "Opus format changed" }
            accepted = parsed
            return parsed
        } catch (error: RuntimeException) {
            failed = true
            throw error
        }
    }

    private fun marker(b: ByteBuffer, name: String, length: Long) {
        require(ByteArray(8).also { b.get(it) }.contentEquals(name.toByteArray(Charsets.US_ASCII)))
        require(b.long == length)
    }
}
