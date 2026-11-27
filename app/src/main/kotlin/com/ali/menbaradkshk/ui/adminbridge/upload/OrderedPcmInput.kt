package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PCM16 LE stereo, already normalized to 48 kHz. One sequential producer in the
 * isolated codec worker and at most one pipe buffer of queued PCM. No PCM files.
 * The caller must still enforce the process-level deadline for native codec I/O.
 */
internal class OrderedPcmInput(
    sources: List<Clip>,
    bufferBytes: Int = 64 * 1024,
    private val checkLive: () -> Unit = {},
) : InputStream() {
    data class Clip(val expectedFrames: Long, val decode: ((ByteArray, Int) -> Unit) -> Unit)

    private val ordered = sources.toList()
    private val bytesPerFrame = 4
    private val maxFrames = 48_000L * 8 * 60 * 60
    val expectedFrames: Long
    private val input: PipedInputStream
    private val output: PipedOutputStream
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "minbar-opus-pcm").apply { isDaemon = true }
    }
    private val error = AtomicReference<Throwable?>()
    private val closed = AtomicBoolean(false)
    private var delivered = 0L // only the single encoder-reading thread
    private val single = ByteArray(1)

    init {
        require(bufferBytes in 4..64 * 1024)
        require(ordered.size in 1..32)
        expectedFrames = ordered.fold(0L) { sum, clip ->
            require(clip.expectedFrames in 1..(maxFrames - sum))
            sum + clip.expectedFrames
        }
        input = PipedInputStream(bufferBytes)
        output = PipedOutputStream(input)
        executor.execute {
            try {
                for (clip in ordered) {
                    var remaining = clip.expectedFrames * bytesPerFrame
                    live()
                    clip.decode { bytes, n ->
                        live()
                        if (n !in 1..bytes.size || n % bytesPerFrame != 0 || n > remaining) {
                            throw IOException("Misaligned or longer decoded clip")
                        }
                        output.write(bytes, 0, n)
                        remaining -= n
                    }
                    if (remaining != 0L) throw IOException("Short decoded clip")
                }
                live()
            } catch (failure: Throwable) {
                error.compareAndSet(null, failure)
            } finally {
                try { output.close() } finally { executor.shutdown() }
            }
        }
    }

    private fun live() {
        if (closed.get()) throw IOException("Ordered PCM closed")
        checkLive()
    }

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255

    override fun read(dst: ByteArray, offset: Int, length: Int): Int {
        live()
        error.get()?.let { throw IOException("Clip decode failed", it) }
        val n = input.read(dst, offset, length)
        if (n < 0) {
            error.get()?.let { throw IOException("Clip finalization failed", it) }
            check(delivered == expectedFrames * bytesPerFrame) { "Truncated PCM pipe" }
        } else delivered += n
        return n
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { input.close() } finally {
                try { output.close() } finally { executor.shutdownNow() }
            }
        }
    }
}
