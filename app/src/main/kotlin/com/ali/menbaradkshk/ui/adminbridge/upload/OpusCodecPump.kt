package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer

/** Bounds cooperative work; cannot interrupt a vendor-native or blocking source call. */
internal class OpusCodecBudget(
    maxMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime,
    private val cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) {
    init { require(maxMillis in 1..1_800_000) }
    private val start = nowNanos()
    private val limit = maxMillis * 1_000_000

    fun check() {
        if (cancelled()) throw InterruptedIOException("Opus codec cancelled")
        if (nowNanos() - start >= limit) throw InterruptedIOException("Opus codec deadline")
    }
}

/** Single-use PCM16 little-endian 48 kHz driver; the session caller owns all resources. */
internal class OpusCodecPump(
    private val pcm: InputStream,
    private val expectedFrames: Long,
    private val channels: Int,
    private val codec: Port,
    private val drain: OpusCodecDrain,
    private val budget: OpusCodecBudget,
    private val inputLimitFrames: Int = 960,
    private val idle: () -> Unit = { Thread.sleep(1) },
) {
    internal interface Port {
        /** One actual codec/output-drain event. Releases any acquired output in finally. */
        fun drainOne(beforeEos: Boolean): Output
        /** Returns null without invoking [fill] when no input buffer is available. */
        fun queue(ptsUs: Long, eos: Boolean, fill: (ByteBuffer) -> Int): Int?
    }
    enum class Output { IDLE, PROGRESS, EOS }

    init {
        require(expectedFrames in 1..48_000L * 8 * 60 * 60)
        require(channels in 1..2 && inputLimitFrames in 1..960)
    }
    private var begun = false

    fun run() {
        check(!begun)
        begun = true
        try {
            val frameBytes = 2 * channels
            val chunk = ByteArray(frameBytes * inputLimitFrames)
            val zeroes = ByteArray(chunk.size)
            var offered = 0L
            var eos = false
            var sourceEnded = false
            val verifiedFormat = {
                drain.outputFormat?.also { require(it.channels == channels) { "Encoder changed PCM channels" } }
            }
            while (true) {
                budget.check()
                val output = codec.drainOne(beforeEos = !eos)
                budget.check()
                val format = verifiedFormat()
                if (output == Output.EOS) {
                    check(eos) { "Encoder ended before queued PCM EOS" }
                    drain.requireComplete()
                    budget.check()
                    return
                }
                var input = false
                if (offered == expectedFrames && !sourceEnded) {
                    check(pcm.read() == -1) { "PCM longer than declared frame count" }
                    budget.check()
                    sourceEnded = true
                    input = true
                }
                if (!eos) {
                    val target = when {
                        offered < expectedFrames -> expectedFrames
                        format != null -> expectedFrames + format.preSkip48k
                        else -> null // Never guess a delay; undocumented late-format codec times out.
                    }
                    if (target != null) {
                        val ending = offered == target
                        val ptsUs = offered * 1_000_000L / 48_000
                        budget.check()
                        val queued = codec.queue(ptsUs, ending) { buffer ->
                            budget.check()
                            if (ending) {
                                0 // Always a separate empty EOS, after original PCM and actual CSD post-roll.
                            } else {
                                val frames = minOf(target - offered, inputLimitFrames.toLong(),
                                    (buffer.remaining() / frameBytes).toLong()).toInt()
                                require(frames > 0) { "Codec input buffer cannot hold one PCM frame" }
                                val bytes = frames * frameBytes
                                if (offered < expectedFrames) {
                                    var count = 0
                                    while (count < bytes) {
                                        budget.check()
                                        val n = pcm.read(chunk, count, bytes - count)
                                        check(n in 1..(bytes - count)) { "PCM ended early or failed to advance" }
                                        count += n
                                    }
                                    buffer.put(chunk, 0, bytes)
                                } else {
                                    buffer.put(zeroes, 0, bytes)
                                }
                                budget.check()
                                bytes
                            }
                        }
                        budget.check()
                        if (queued != null) {
                            require(if (ending) queued == 0 else queued > 0 && queued % frameBytes == 0)
                            require(queued / frameBytes <= inputLimitFrames && queued / frameBytes <= target - offered)
                            if (ending) eos = true else offered += queued / frameBytes
                            input = true
                        }
                    }
                }
                if (!input && output == Output.IDLE) idle()
            }
        } catch (failure: Exception) {
            drain.invalidate()
            throw failure
        }
    }
}
