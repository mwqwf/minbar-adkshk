package com.ali.menbaradkshk.ui.adminbridge.upload

import android.media.MediaCodec
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Dormant, synchronous entry point for an already started PCM16/48 kHz Opus encoder.
 * Caller owns codec lifetime, source, private scratch sink, and independent Ogg read-back.
 */
internal object AndroidOpusCodecPump {
    fun encode(
        codec: MediaCodec, pcm: InputStream, originalFrames: Long, channels: Int,
        scratch: OutputStream, byteLimit: Long, serial: Int, budget: OpusCodecBudget,
        inputLimitFrames: Int = 960,
    ) {
        val drain = OpusCodecDrain(originalFrames, scratch, byteLimit, serial, budget::check)
        val bridge = AndroidOpusCodecDrain(drain)
        OpusCodecPump(pcm, originalFrames, channels, MediaCodecPort(codec, bridge, budget), drain,
            budget, inputLimitFrames).run()
    }

    private class MediaCodecPort(
        private val codec: MediaCodec,
        private val drain: AndroidOpusCodecDrain,
        private val budget: OpusCodecBudget,
    ) : OpusCodecPump.Port {
        private val info = MediaCodec.BufferInfo()

        override fun drainOne(beforeEos: Boolean): OpusCodecPump.Output {
            val index = codec.dequeueOutputBuffer(info, 0)
            return when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> OpusCodecPump.Output.IDLE
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    budget.check()
                    drain.onOutputFormatChanged(codec.outputFormat)
                    OpusCodecPump.Output.PROGRESS
                }
                // getOutputBuffer(index) handles a changed set without a cached array.
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> OpusCodecPump.Output.PROGRESS
                index >= 0 -> {
                    var failure: Throwable? = null
                    try {
                        budget.check()
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        check(!eos || !beforeEos) { "Encoder ended before queued PCM EOS" }
                        val buffer = if (info.size > 0) codec.getOutputBuffer(index) else null
                        budget.check()
                        drain.onOutputBuffer(buffer, info)
                        if (eos) OpusCodecPump.Output.EOS else OpusCodecPump.Output.PROGRESS
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        try { codec.releaseOutputBuffer(index, false) }
                        catch (close: Throwable) {
                            if (failure == null) throw close
                            if (failure !== close) failure.addSuppressed(close)
                        }
                    }
                }
                else -> error("Unexpected MediaCodec output index")
            }
        }

        override fun queue(ptsUs: Long, eos: Boolean, fill: (ByteBuffer) -> Int): Int? {
            val index = codec.dequeueInputBuffer(0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return null
            check(index >= 0)
            budget.check()
            // On failure the application must discard this codec session; it cannot release a
            // checked-out input buffer independently without queuing or stopping the codec.
            val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
            val count = fill(buffer)
            budget.check()
            codec.queueInputBuffer(index, 0, count, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            return count
        }
    }
}
