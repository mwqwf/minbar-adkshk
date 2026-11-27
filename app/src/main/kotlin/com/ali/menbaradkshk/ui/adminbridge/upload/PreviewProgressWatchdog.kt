package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException

/**
 * Stall detection for the private preview instead of a fixed wall-clock deadline: slow phones
 * may decode/play slower than real time, which is fine as long as something keeps moving.
 * Fails only when no decode or playback progress is seen for [stallMs], or when the generous
 * overall cap (3 × excerpt length + 30 s) is exceeded.
 */
internal class PreviewProgressWatchdog(
    framesToPlay: Long,
    private val nowMs: () -> Long,
    private val stallMs: Long = STALL_MS,
) {
    init { require(framesToPlay > 0 && stallMs > 0) }
    private val start = nowMs()
    val capMs: Long = 3 * (framesToPlay * 1000 / 48_000) + 30_000
    private var lastProgress = start

    fun progress() { lastProgress = nowMs() }

    fun check() {
        val now = nowMs()
        if (now - lastProgress >= stallMs) throw IOException("Preview stalled")
        if (now - start >= capMs) throw IOException("Preview overall cap")
    }

    companion object { const val STALL_MS = 10_000L }
}
