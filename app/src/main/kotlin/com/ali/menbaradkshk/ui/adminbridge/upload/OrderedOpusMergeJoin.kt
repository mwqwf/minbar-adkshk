package com.ali.menbaradkshk.ui.adminbridge.upload

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.ali.menbaradkshk.util.AudioTranscodeMerger
import com.ali.menbaradkshk.util.OggOpusTrimPlan
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Worker-side ordered join, run only inside the private :opusmerge process. Each borrowed,
 * read-only descriptor is copied once into this attempt's private scratch and its SHA-256 is
 * re-checked before decoding; decoded PCM is streamed (no PCM files) into one Opus encoder.
 */
internal object OrderedOpusMergeJoin {
    const val MAX_STAGE_FRAMES = 30L * 60 * 48_000
    private const val BIT_RATE = 64_000

    fun copyVerified(input: ParcelFileDescriptor, expected: Long, sha: String, dest: File, check: () -> Unit) {
        val access = Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0)
        if (access and OsConstants.O_ACCMODE != OsConstants.O_RDONLY || input.statSize != expected)
            throw IOException("Unexpected merge input descriptor")
        if (!dest.createNewFile()) throw IOException("Merge scratch already exists")
        ParcelFileDescriptor.AutoCloseInputStream(input).use { from ->
            dest.outputStream().use { to -> copyStream(from, expected, sha, check) { b, n -> to.write(b, 0, n) } }
        }
    }

    /** Pure copy/verify loop (unit-tested): exact length, exact digest, no trailing bytes. */
    internal fun copyStream(from: InputStream, expected: Long, sha: String, check: () -> Unit, sink: (ByteArray, Int) -> Unit) {
        require(expected in 1..MAX_ORIGINAL_BYTES && sha.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            check()
            val n = from.read(block)
            if (n < 0) break
            if (n == 0) continue
            if (n > expected - count) throw IOException("Merge input longer than declared")
            digest.update(block, 0, n)
            sink(block, n)
            count += n
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (count != expected || hex != sha) throw IOException("Merge input bytes changed")
    }

    /** Returns total audible 48 kHz frames written to [output] as one Ogg Opus stream. */
    fun encode(inputs: List<File>, output: File, byteLimit: Long, budget: OpusCodecBudget, serial: Int): Long {
        require(inputs.size in OrderedOpusMergeWire.MIN_INPUTS..OrderedOpusMergeWire.MAX_INPUTS)
        val clips = inputs.map { original ->
            val frames = original.inputStream().buffered().use { OggOpusTrimPlan.read(it, budget::check) }.audibleFrames
            OrderedPcmInput.Clip(frames) { out ->
                AudioTranscodeMerger.decodePrivateOpusPreview(original, budget::check, out)
            }
        }
        val total = clips.fold(0L) { sum, clip -> Math.addExact(sum, clip.expectedFrames) }
        if (total !in 1..MAX_STAGE_FRAMES) throw IOException("Merge exceeds local review limit")
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var started = false
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            OrderedPcmInput(clips, checkLive = budget::check).use { pcm ->
                check(pcm.expectedFrames == total)
                output.outputStream().buffered().use {
                    AndroidOpusCodecPump.encode(codec, pcm, total, 2, it, byteLimit, serial, budget)
                }
            }
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
        if (output.length() !in 1..byteLimit) throw IOException("Merge output out of bounds")
        return total
    }
}
