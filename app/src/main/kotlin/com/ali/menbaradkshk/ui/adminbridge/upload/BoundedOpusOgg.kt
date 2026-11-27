package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dormant RFC 7845 mapping-family-zero writer for verified single-frame 20 ms Opus
 * packets from one encoder. It does not validate the encoded frame or infer pre-skip.
 * Callers must own the unpublished sink and discard it after any failure.
 */
internal class BoundedOpusOgg(
    private val sink: OutputStream,
    private val limit: Long,
    private val serial: Int,
    channels: Int,
    private val preSkip: Int,
    private val check: () -> Unit = {},
) {
    private var written = 0L
    private var sequence = 0
    private var granule = 0L
    private var closed = false
    private var poisoned = false

    init {
        require(channels in 1..2 && preSkip in 0..65535 && limit in 1..95L * 1024 * 1024)
        val head = little(19).put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1).put(channels.toByte()).putShort(preSkip.toShort()).putInt(48_000)
            .putShort(0).put(0).array()
        val vendor = "Minbar".toByteArray(Charsets.US_ASCII)
        val tags = little(16 + vendor.size).put("OpusTags".toByteArray(Charsets.US_ASCII))
            .putInt(vendor.size).put(vendor).putInt(0).array()
        if (limit < pageSize(head.size) + pageSize(tags.size)) throw IOException("Ogg output limit")
        page(head, 2, 0)
        page(tags, 0, 0)
    }

    /** Non-final packet advances exactly 960 samples; final packet may trim up to 959. */
    fun packet(data: ByteArray, finalTrim: Int? = null) {
        check(!closed && !poisoned)
        try {
            require(data.size in 2..1276 && isSingle20ms(data[0])) { "Expected single 20 ms Opus frame" }
            require(finalTrim == null || finalTrim in 0..959) { "Invalid final Opus trim" }
            val next = granule + 960L - (finalTrim ?: 0)
            require(finalTrim == null || next > preSkip) { "No playable Opus samples" }
            page(data, if (finalTrim == null) 0 else 4, next)
            granule = next
            closed = finalTrim != null
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    /** A sink without an accepted EOS must never be installed. Does not own or flush sink. */
    fun requireComplete() { check(closed && !poisoned) }

    private fun page(packet: ByteArray, flags: Int, position: Long) {
        check()
        val size = pageSize(packet.size)
        if (size > limit - written) throw IOException("Ogg output limit")
        val segments = packet.size / 255 + 1
        val b = little(size).put("OggS".toByteArray(Charsets.US_ASCII)).put(0)
            .put(flags.toByte()).putLong(position).putInt(serial).putInt(sequence)
            .putInt(0).put(segments.toByte())
        repeat(segments - 1) { b.put(255.toByte()) }
        b.put((packet.size % 255).toByte()).put(packet)
        val bytes = b.array()
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        b.putInt(22, crc)
        sink.write(bytes)
        written += size
        sequence++
    }

    private fun isSingle20ms(toc: Byte): Boolean {
        val v = toc.toInt() and 255
        val config = v ushr 3
        return (v and 3) == 0 && when {
            config < 12 -> config % 4 == 1
            config < 16 -> config % 2 == 1
            else -> config % 4 == 3
        }
    }

    private fun pageSize(n: Int): Int = 28 + n / 255 + n
    private fun little(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
}
