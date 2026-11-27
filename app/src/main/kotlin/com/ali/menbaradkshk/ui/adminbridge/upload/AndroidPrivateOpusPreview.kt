package com.ali.menbaradkshk.ui.adminbridge.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ali.menbaradkshk.util.AudioTranscodeMerger
import com.ali.menbaradkshk.util.OggOpusTrimPlan
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Local, foreground-only review audio. Does not approve, upload or publish a draft.
 * UI must use the same instance for the reviewer lifetime and call close on view/session loss.
 */
internal class AndroidPrivateOpusPreview(context: Context) : Closeable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val playback = PrivateOpusPreviewOwnership()
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /**
     * Foreground main thread. The screen supplies a synchronous barrier that pauses regular
     * lesson/Quran audio and rejects if it cannot confirm the handoff. No automatic resume.
     */
    fun begin(pauseExistingAudio: () -> Boolean): PrivateOpusPreviewOwnership.Ticket? {
        check(Looper.myLooper() == main.looper)
        return playback.begin acquire@{ ticket ->
            val grant = acquireFocus(ticket) ?: return@acquire null
            try {
                if (pauseExistingAudio()) grant else { grant.close(); null }
            } catch (t: Throwable) {
                grant.close()
                throw t
            }
        }
    }

    /**
     * Worker thread. The result binds the exact staged file to frames that reached the platform
     * playback head. Use only the app's private, immutable candidate, never an external URI.
     */
    fun render(
        ticket: PrivateOpusPreviewOwnership.Ticket,
        candidate: File,
        expectedHash: String,
        expectedFrames: Long,
        checkLease: () -> Unit,
        onFramesQueued: (Long) -> Unit = {},
    ): DerivedOpusLocalDraft.PlaybackEvidence {
        val audible = play(ticket, candidate, expectedHash, expectedFrames, expectedFrames, checkLease, onFramesQueued)
        return DerivedOpusLocalDraft.PlaybackEvidence(audible.sha256, audible.presentedFrames)
    }

    internal data class Audition(val sha256: String, val presentedFrames: Long)

    /** Human audition is not the independent qualification and does not mint full-file proof. */
    fun renderExcerpt(
        ticket: PrivateOpusPreviewOwnership.Ticket,
        candidate: File,
        expectedHash: String,
        totalFrames: Long,
        checkLease: () -> Unit,
        limitFrames: Long = 20 * 48_000L,
    ): Audition {
        require(limitFrames in 1..20 * 48_000L)
        return play(ticket, candidate, expectedHash, totalFrames, minOf(totalFrames, limitFrames), checkLease) {}
    }

    private fun play(
        ticket: PrivateOpusPreviewOwnership.Ticket,
        candidate: File,
        expectedHash: String,
        totalFrames: Long,
        framesToPlay: Long,
        checkLease: () -> Unit,
        onFramesQueued: (Long) -> Unit,
    ): Audition {
        check(Looper.myLooper() != main.looper)
        require(expectedHash.matches(Regex("[0-9a-f]{64}")) && totalFrames in 1..MAX_SOURCE_FRAMES && framesToPlay in 1..minOf(totalFrames, MAX_PLAY_FRAMES))
        val watchdog = PreviewProgressWatchdog(framesToPlay, SystemClock::elapsedRealtime)
        fun requireLive() {
            playback.requireActive(ticket)
            checkLease()
            if (Thread.currentThread().isInterrupted) throw IOException("Preview interrupted")
            watchdog.check()
        }
        var rendered = false
        try {
            requireLive()
            if (!candidate.isFile || candidate.absoluteFile != candidate.canonicalFile ||
                candidate.length() !in 1..MAX_ORIGINAL_BYTES)
                throw IOException("Unavailable private preview")
            val digest = MessageDigest.getInstance("SHA-256")
            candidate.inputStream().buffered().use { source ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    requireLive()
                    val size = source.read(buffer)
                    if (size == -1) break
                    digest.update(buffer, 0, size)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val trim = candidate.inputStream().buffered().use { OggOpusTrimPlan.read(it, ::requireLive) }
            if (hash != expectedHash || trim.audibleFrames != totalFrames || trim.channels != 2)
                throw IOException("Unexpected private preview content")

            var queued = 0L
            fun writeAll(bytes: ByteArray, offset0: Int, end: Int) {
                var offset = offset0
                while (offset < end) {
                    requireLive()
                    val count = playback.write(ticket, bytes, offset, end - offset, framesToPlay)
                    if (count == 0) Thread.sleep(4) else {
                        watchdog.progress()
                        offset += count
                        queued += count / 4
                        onFramesQueued(queued)
                    }
                }
            }
            if (framesToPlay * 4 <= PREBUFFER_MAX_BYTES) {
                // Decode the whole excerpt ahead of playback so a slow decoder cannot underrun the
                // track; progress during decoding keeps the watchdog alive.
                val pcm = PreviewPcmBuffer(framesToPlay, exact = framesToPlay == totalFrames)
                try {
                    AudioTranscodeMerger.decodePrivateOpusPreview(candidate, ::requireLive) { bytes, length ->
                        if (pcm.append(bytes, length)) watchdog.progress()
                        if (pcm.full && framesToPlay < totalFrames) throw ExcerptEnded
                    }
                } catch (_: ExcerptEnded) {
                    // Closing the decoder early is intentional for a short audition of a verified candidate.
                }
                if (!pcm.full) throw IOException("Short preview decode")
                requireLive()
                playback.attach(ticket, createSink())
                writeAll(pcm.bytes, 0, pcm.bytes.size)
            } else {
                playback.attach(ticket, createSink())
                try {
                    AudioTranscodeMerger.decodePrivateOpusPreview(candidate, ::requireLive) { bytes, length ->
                        require(length > 0 && length % 4 == 0)
                        watchdog.progress()
                        val end = minOf(length.toLong(), (framesToPlay - queued) * 4L).toInt()
                        if (framesToPlay == totalFrames && end != length) throw IOException("Extra preview frames")
                        writeAll(bytes, 0, end)
                        if (framesToPlay < totalFrames && queued == framesToPlay) throw ExcerptEnded
                    }
                } catch (_: ExcerptEnded) {
                    // Closing the decoder early is intentional for a short audition of a verified candidate.
                }
            }
            playback.endOfStream(ticket, framesToPlay)
            var presented = -1L
            while (true) {
                requireLive()
                if (playback.finishIfPresented(ticket, framesToPlay)) break
                val now = playback.presentedFrames(ticket)
                if (now != presented) { presented = now; watchdog.progress() }
                Thread.sleep(8)
            }
            checkLease()
            rendered = true
            return Audition(hash, framesToPlay)
        } finally {
            if (!rendered) playback.revoke(ticket)
        }
    }

    /** Call on the foreground screen after background render. False means stale or already handled. */
    fun consumeCompletion(ticket: PrivateOpusPreviewOwnership.Ticket): Boolean {
        check(Looper.myLooper() == main.looper)
        return playback.consumeCompletion(ticket)
    }

    fun revoke(ticket: PrivateOpusPreviewOwnership.Ticket) = playback.revoke(ticket)
    override fun close() = playback.revokeAll()

    @Suppress("DEPRECATION")
    private fun acquireFocus(ticket: PrivateOpusPreviewOwnership.Ticket): Closeable? {
        val am = manager ?: return null
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) playback.revoke(ticket)
            // Gain is deliberately ignored. A revoked private preview never restarts itself.
        }
        val request = if (Build.VERSION.SDK_INT >= 26)
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener, main).build() else null
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) playback.revoke(ticket)
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(noisy, filter)
        fun release() {
            try {
                if (Build.VERSION.SDK_INT >= 26 && request != null) am.abandonAudioFocusRequest(request)
                else am.abandonAudioFocus(listener)
            } finally {
                app.unregisterReceiver(noisy)
            }
        }
        try {
            val state = if (Build.VERSION.SDK_INT >= 26 && request != null) am.requestAudioFocus(request)
            else am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            if (state == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return Closeable { release() }
        } catch (t: Throwable) {
            release()
            throw t
        }
        release()
        return null
    }

    private fun createSink(): PrivateOpusPreviewOwnership.Sink {
        val mask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val size = AudioTrack.getMinBufferSize(48_000, mask, encoding)
        if (size <= 0) throw IOException("Preview output unavailable")
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioFormat(AudioFormat.Builder().setChannelMask(mask).setEncoding(encoding)
                .setSampleRate(48_000).build())
            .setBufferSizeInBytes(maxOf(8192, size)).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IOException("Preview output unavailable")
        }
        return object : PrivateOpusPreviewOwnership.Sink {
            override fun play() = track.play()
            override fun writeNonBlocking(bytes: ByteArray, offset: Int, size: Int) =
                track.write(bytes, offset, size, AudioTrack.WRITE_NON_BLOCKING)
            private var stopped: StoppedTrackPosition? = null
            private fun head() = track.playbackHeadPosition.toLong() and 0xffffffffL
            override fun presentedFrames(): Long =
                stopped?.position(head(), SystemClock.elapsedRealtime()) ?: head()
            override fun discardQueued() { try { track.pause() } finally { track.flush() } }
            override fun endOfStream(totalFrames: Long) {
                // stop() lets the queued tail drain; the head may read 0 afterwards (see StoppedTrackPosition).
                val clock = StoppedTrackPosition(head().coerceIn(0, totalFrames), SystemClock.elapsedRealtime(), totalFrames)
                track.stop()
                stopped = clock
            }
            override fun close() = track.release()
        }
    }

    private object ExcerptEnded : RuntimeException(null, null, false, false)

    private companion object {
        const val MAX_SOURCE_FRAMES = 8L * 60 * 60 * 48_000
        const val MAX_PLAY_FRAMES = 30L * 60 * 48_000
        /** 20 s of 48 kHz stereo PCM16 is 3.84 MB; anything up to this is decoded ahead. */
        const val PREBUFFER_MAX_BYTES = 4L * 1024 * 1024
    }
}
