package com.ali.menbaradkshk.ui.adminbridge.upload

/**
 * Presentation position of a streaming AudioTrack after stop().
 *
 * On many Android versions a non-offloaded track reports a playback head of 0 as soon as it is
 * STOPPED, even while the queued tail is still draining. After stop we therefore extrapolate from
 * the head observed just before stop at the nominal sample rate, never exceeding the queued total
 * and never going backwards. A device that keeps reporting the real head wins if it is ahead.
 */
internal class StoppedTrackPosition(
    private val headAtStop: Long,
    private val stoppedAtMs: Long,
    private val totalFrames: Long,
    private val sampleRate: Int = 48_000,
) {
    init { require(headAtStop in 0..totalFrames && sampleRate > 0) }

    private var last = headAtStop

    fun position(rawHead: Long, nowMs: Long): Long {
        val elapsed = (nowMs - stoppedAtMs).coerceAtLeast(0)
        val simulated = headAtStop + elapsed * sampleRate / 1000
        val raw = if (rawHead in headAtStop..totalFrames) rawHead else 0L
        last = maxOf(last, minOf(totalFrames, maxOf(simulated, raw)))
        return last
    }
}
