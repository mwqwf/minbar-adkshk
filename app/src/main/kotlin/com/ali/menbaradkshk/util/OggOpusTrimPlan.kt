package com.ali.menbaradkshk.util

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Strict, single-stream mono/stereo Ogg Opus inspection for private lossless join accounting. */
internal data class OggOpusTrimPlan(
    val channels: Int,
    val preSkip: Int,
    val packetFrames: Long,
    val audibleFrames: Long,
) {
    val endPadding: Long get() = packetFrames - preSkip - audibleFrames

    companion object {
        private val crc = IntArray(256) { index ->
            var v = index shl 24
            repeat(8) { v = (v shl 1) xor if (v < 0) 0x04c11db7 else 0 }
            v
        }

        // Strict subset: zero-based, single serial, mapping 0, version 1. Reject any
        // unsupported stream instead of treating all decoder output as audible.
        fun read(input: InputStream, checkActive: () -> Unit = {}): OggOpusTrimPlan {
            var packet = ByteArrayOutputStream()
            var packetNo = 0
            var channels = 0
            var preSkip = 0
            var serial = 0L
            var sequence = 0L
            var rawFrames = 0L
            var previousGranule = 0L
            var continuation = false
            while (true) {
                checkActive()
                val fixed = input.exact(27)
                require(fixed.tag("OggS") && fixed.u(4) == 0) { "Invalid Ogg header" }
                val flags = fixed.u(5)
                val thisGranule = fixed.le(6, 8)
                require(flags in 0..7 && (flags and 1 != 0) == continuation) { "Invalid Ogg continuation" }
                require((flags and 2 != 0) == (sequence == 0L)) { "Unexpected beginning of Ogg stream" }
                if (sequence == 0L) serial = fixed.le(14, 4)
                require(fixed.le(14, 4) == serial && fixed.le(18, 4) == sequence++) { "Multiple or discontinuous Ogg streams" }
                val lacing = input.exact(fixed.u(26))
                require(lacing.isNotEmpty()) { "Empty Ogg page" }
                val body = input.exact(lacing.sumOf { it.toInt() and 255 })
                val check = fixed.le(22, 4).toInt()
                fixed.fill(0, 22, 26)
                var computed = 0
                for (buf in listOf(fixed, lacing, body)) {
                    for (b in buf) computed = (computed shl 8) xor crc[(computed ushr 24) xor (b.toInt() and 255)]
                }
                require(computed == check) { "Ogg checksum mismatch" }
                val startPacketNo = packetNo
                var bodyOffset = 0
                var audioFramesOnPage = 0L
                var lastFrames = 0
                for (lace in lacing.indices) {
                    val size = lacing.u(lace)
                    val limit = if (packetNo == 1) 256 * 1024 else 65_536
                    require(packet.size() <= limit - size) { "Ogg packet too large" }
                    packet.write(body, bodyOffset, size)
                    bodyOffset += size
                    continuation = size == 255
                    if (continuation) continue
                    val payload = packet.toByteArray()
                    packet.reset()
                    when (packetNo) {
                        0 -> {
                            require(sequence == 1L && lacing.size == 1 && payload.size == 19 &&
                                payload.tag("OpusHead") && payload.u(8) == 1 && payload.u(18) == 0 &&
                                payload.u(9) in 1..2
                            ) { "Unsupported Opus identification header" }
                            channels = payload.u(9)
                            preSkip = payload.le(10, 2).toInt()
                        }
                        1 -> require(payload.tag("OpusTags") && payload.size >= 16 && lace == lacing.lastIndex) {
                            "Unsupported Opus comments placement"
                        }
                        else -> {
                            lastFrames = packetDuration(payload)
                            rawFrames = Math.addExact(rawFrames, lastFrames.toLong())
                            audioFramesOnPage += lastFrames
                        }
                    }
                    require(packetNo < Int.MAX_VALUE)
                    packetNo++
                }
                val eos = flags and 4 != 0
                if (audioFramesOnPage > 0) {
                    require(thisGranule > previousGranule || eos && thisGranule == previousGranule) { "Invalid Opus granule" }
                    if (eos) {
                        require(!continuation && thisGranule <= rawFrames && rawFrames - thisGranule <= lastFrames &&
                            thisGranule > preSkip && input.read() == -1
                        ) { "Invalid Opus ending or trailing stream" }
                        return OggOpusTrimPlan(channels, preSkip, rawFrames, thisGranule - preSkip)
                    }
                    require(thisGranule == rawFrames) { "Unsupported non-zero Opus time origin or discontinuity" }
                    previousGranule = thisGranule
                } else {
                    require(!eos && thisGranule == if (packetNo != startPacketNo) 0L else -1L) {
                        "Invalid Ogg non-audio page"
                    }
                }
            }
        }

        private fun packetDuration(packet: ByteArray): Int {
            require(packet.isNotEmpty())
            val toc = packet.u(0)
            val config = toc ushr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
                config < 16 -> if (config and 1 == 0) 480 else 960
                else -> 120 shl (config and 3)
            }
            val count = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> {
                    require(packet.size >= 2)
                    packet.u(1) and 63
                }
            }
            require(count * frame in 120..5760) { "Invalid Opus packet duration" }
            return count * frame
        }

        private fun ByteArray.u(i: Int) = this[i].toInt() and 255
        private fun ByteArray.le(i: Int, n: Int): Long =
            (0 until n).fold(0L) { v, b -> v or (u(i + b).toLong() shl (b * 8)) }
        private fun ByteArray.tag(s: String): Boolean = size >= s.length && s.indices.all { u(it) == s[it].code }
        private fun InputStream.exact(n: Int): ByteArray {
            val data = ByteArray(n)
            var filled = 0
            while (filled < n) {
                val read = read(data, filled, n - filled)
                if (read <= 0) throw EOFException("Truncated Ogg")
                filled += read
            }
            return data
        }
    }
}
