package com.ali.menbaradkshk.ui.adminbridge.upload

import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

internal enum class ReviewedAudioContainer(val mime: String, val trackMime: String) {
    Opus("audio/opus", "audio/opus"),
    Vorbis("audio/ogg", "audio/vorbis"),
    Flac("audio/flac", "audio/flac"),
    Wav("audio/wav", "audio/raw"),
    M4aLc("audio/mp4", "audio/mp4a-latm"),
    Mp3("audio/mpeg", "audio/mpeg"),
}

private fun ByteArray.matchesAt(offset: Int, value: String) =
    offset >= 0 && size - offset >= value.length && value.indices.all { this[offset + it].toInt() == value[it].code }
private fun ByteArray.u(offset: Int) = this[offset].toInt() and 255
private fun ByteArray.le32(offset: Int): Long =
    (0..3).sumOf { u(offset + it).toLong() shl (8 * it) }

private fun isPcmWav(bytes: ByteArray): Boolean {
    if (bytes.size < 44 || !bytes.matchesAt(0, "RIFF") || !bytes.matchesAt(8, "WAVE") ||
        bytes.le32(4) != bytes.size.toLong() - 8) return false
    var offset = 12
    var fmt = false
    var payload = false
    while (offset <= bytes.size - 8) {
        val length = bytes.le32(offset + 4)
        val end = offset + 8L + length + length % 2
        if (end > bytes.size) return false
        if (bytes.matchesAt(offset, "fmt ")) {
            if (fmt || length < 16 || bytes.u(offset + 8) != 1 || bytes.u(offset + 9) != 0) return false
            fmt = true
        } else if (bytes.matchesAt(offset, "data")) {
            if (!fmt || payload || length == 0L) return false
            payload = true
        }
        offset = end.toInt()
    }
    return fmt && payload && offset == bytes.size
}

/** Deliberate subset: unknown containers and ambiguous Ogg/RIFF variants remain local. */
internal fun readAudioContainer(bytes: ByteArray): ReviewedAudioContainer? {
    if (bytes.size < 16) return null
    if (bytes.matchesAt(0, "fLaC") && bytes.u(4) and 127 == 0 &&
        bytes.u(5) == 0 && bytes.u(6) == 0 && bytes.u(7) == 34 && bytes.size >= 42)
        return ReviewedAudioContainer.Flac
    if (isPcmWav(bytes)) return ReviewedAudioContainer.Wav
    if (isStrictMergedLcM4a(bytes)) return ReviewedAudioContainer.M4aLc
    if (isStrictMergedMp3(bytes)) return ReviewedAudioContainer.Mp3

    // First Ogg page, noncontinued BOS, stream sequence zero, complete identification packet.
    if (bytes.size < 29 || !bytes.matchesAt(0, "OggS") || bytes.u(4) != 0 ||
        bytes.u(5) != 2 || (18..21).any { bytes.u(it) != 0 }) return null
    val segments = bytes.u(26)
    val packet = 27 + segments
    if (segments == 0 || packet >= bytes.size) return null
    val pageBytes = (27 until packet).sumOf { bytes.u(it) }
    if (pageBytes > bytes.size - packet) return null
    val first = bytes.u(27)
    if (first == 255) return null
    return when {
        first >= 19 && bytes.matchesAt(packet, "OpusHead") && bytes.u(packet + 8) == 1 -> ReviewedAudioContainer.Opus
        first >= 30 && bytes.u(packet) == 1 && bytes.matchesAt(packet + 1, "vorbis") -> ReviewedAudioContainer.Vorbis
        else -> null
    }
}

internal fun detectVerifiedAudioMime(bytes: ByteArray): String? {
    val container = readAudioContainer(bytes) ?: return null
    val source = object : MediaDataSource() {
        override fun getSize() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() {}
    }
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(source)
        if (extractor.trackCount != 1) return null
        val format = extractor.getTrackFormat(0)
        if (format.getString(MediaFormat.KEY_MIME) != container.trackMime ||
            !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) !in 1..8 ||
            !format.containsKey(MediaFormat.KEY_SAMPLE_RATE) || format.getInteger(MediaFormat.KEY_SAMPLE_RATE) !in 8000..192000)
            return null
        if (container == ReviewedAudioContainer.M4aLc && format.containsKey(MediaFormat.KEY_AAC_PROFILE) &&
            format.getInteger(MediaFormat.KEY_AAC_PROFILE) != 2) return null
        if (container != ReviewedAudioContainer.Wav &&
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) == null) return null
        extractor.selectTrack(0)
        if (extractor.sampleTime < 0 || extractor.sampleSizeCompat() !in 1L..bytes.size.toLong()) return null
        // MediaExtractor also supplies the payload deterministically; no full transcode/decoder run.
        val firstSample = ByteBuffer.allocate(bytes.size)
        container.mime.takeIf { extractor.readSampleData(firstSample, 0) > 0 }
    } catch (_: Exception) {
        null
    } finally {
        try { extractor.release() } finally { source.close() }
    }
}

private fun MediaExtractor.sampleSizeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= 28) sampleSize else 1L
