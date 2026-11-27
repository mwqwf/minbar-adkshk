package com.ali.menbaradkshk.ui.adminbridge.upload

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/** Dormant bridge for a session that owns dequeuing/releasing MediaCodec buffers. */
internal class AndroidOpusCodecDrain(private val drain: OpusCodecDrain) {
    fun onOutputFormatChanged(format: MediaFormat) = guarded {
        drain.format(
            requireNotNull(format.getString(MediaFormat.KEY_MIME)),
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            format.csd("csd-0"), format.csd("csd-1"), format.csd("csd-2"),
        )
    }

    /** Buffer ownership remains with caller; release it in finally after this returns. */
    fun onOutputBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo) {
        drain.output(
            buffer, info.offset, info.size,
            eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0,
            config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0,
            partial = info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0,
        )
    }

    fun requireComplete() = drain.requireComplete()

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (failure: Exception) { drain.invalidate(); throw failure }
    }

    private fun MediaFormat.csd(key: String): ByteArray? {
        val data = getByteBuffer(key)?.duplicate() ?: return null
        require(data.remaining() <= 83) { "Oversized Opus codec-specific data" }
        return ByteArray(data.remaining()).also { data.get(it) }
    }
}
