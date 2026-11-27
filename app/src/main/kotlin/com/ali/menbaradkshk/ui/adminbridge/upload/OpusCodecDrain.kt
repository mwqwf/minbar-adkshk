package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Unpublished output transaction for the inspected mapping-0, single-frame 20 ms path.
 * Original PCM length excludes any encoder post-roll. No caller may publish the sink
 * without successful codec EOS, requireComplete(), and independent read-back validation.
 */
internal class OpusCodecDrain(
    private val originalSamples48k: Long,
    private val sink: OutputStream,
    private val limit: Long,
    private val streamSerial: Int,
    private val checkpoint: () -> Unit = {},
) {
    init { require(originalSamples48k in 1..Long.MAX_VALUE - 65535 - FRAME * 2) }

    private val csd = OpusCsd()
    private var writer: BoundedOpusOgg? = null
    private var target = 0L
    private var covered = 0L
    private var final: ByteArray? = null
    private var surplus = false
    private var complete = false
    private var failed = false
    var outputFormat: OpusCsd.Format? = null
        private set

    fun format(mime: String, rate: Int, channels: Int, zero: ByteArray?, one: ByteArray?, two: ByteArray?) = step {
        val format = csd.observe(mime, rate, channels, zero, one, two)
        if (writer == null) {
            target = originalSamples48k + format.preSkip48k
            writer = BoundedOpusOgg(sink, limit, streamSerial, channels, format.preSkip48k, checkpoint)
            outputFormat = format
        }
    }

    fun output(buffer: ByteBuffer?, offset: Int, size: Int, eos: Boolean, config: Boolean, partial: Boolean) = step {
        require(!partial && size >= 0 && offset >= 0)
        require(!config || !eos) { "Codec configuration cannot end the stream" }
        if (size > 0) {
            val view = requireNotNull(buffer).duplicate()
            require(offset >= view.position() && offset <= view.limit() && size <= view.limit() - offset)
            if (config) return@step // CSD is accepted only through the output format.
            val mux = requireNotNull(writer) { "Opus packet before format" }
            require(size in 2..1276)
            val toc = view.get(offset).toInt() and 255
            val mode = toc ushr 3
            val ms20 = when {
                mode < 12 -> mode % 4 == 1
                mode < 16 -> mode % 2 == 1
                else -> mode % 4 == 3
            }
            require(toc and 3 == 0 && ms20) { "Unsupported Opus packet duration or layout" }
            if (final != null) {
                require(!surplus) { "Unexpected excess codec output" }
                surplus = true
            } else {
                val packet = ByteArray(size)
                view.position(offset)
                view.get(packet)
                covered += FRAME
                if (covered >= target) final = packet else mux.packet(packet)
            }
        }
        if (eos) {
            val mux = requireNotNull(writer) { "EOS before Opus format" }
            val packet = requireNotNull(final) { "Codec output shorter than original PCM plus encoder delay" }
            mux.packet(packet, finalTrim = (covered - target).toInt())
            mux.requireComplete()
            complete = true
        }
    }

    fun requireComplete() {
        check(complete && !failed)
        try { checkpoint() } catch (failure: Exception) { failed = true; throw failure }
    }

    /** Also poisons the transaction if Android metadata cannot be parsed. */
    fun invalidate() { failed = true }

    private inline fun step(block: () -> Unit) {
        check(!failed)
        try {
            check(!complete) { "Codec output after EOS" }
            checkpoint()
            block()
        } catch (failure: Exception) {
            failed = true
            throw failure
        }
    }

    private companion object { const val FRAME = 960L }
}
