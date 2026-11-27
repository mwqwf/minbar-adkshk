package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.Closeable
import java.io.IOException

/**
 * One local moderator preview at a time. Control calls may come from main; media writes
 * use a background driver. The sink must never perform a blocking write while holding this lock.
 * Every event carries a particular Ticket, including focus loss and driver completion.
 */
internal class PrivateOpusPreviewOwnership {
    internal class Ticket internal constructor(val generation: Long)
    internal interface Sink : Closeable {
        fun play()
        fun writeNonBlocking(bytes: ByteArray, offset: Int, size: Int): Int
        fun presentedFrames(): Long
        fun discardQueued() // pause, then flush; never stop() on interruption
        /** All frames written: let the platform play out the tail (stream-mode stop drains). */
        fun endOfStream(totalFrames: Long) {}
    }

    private data class Active(
        val ticket: Ticket,
        var grant: Closeable? = null,
        var sink: Sink? = null,
        var accepted: Long = 0,
        var presented: Long = 0,
    )

    private var sequence = 0L
    private var current: Active? = null
    private var completed: Ticket? = null

    @Synchronized
    fun begin(acquire: (Ticket) -> Closeable?): Ticket? {
        completed = null
        current?.let { retire(it, discard = true) }
        check(sequence < Long.MAX_VALUE)
        val ticket = Ticket(++sequence)
        val active = Active(ticket)
        current = active
        val focus = try { acquire(ticket) } catch (t: Throwable) {
            if (current === active) retire(active, discard = true)
            throw t
        }
        if (current !== active || focus == null) {
            if (current === active) retire(active, discard = true)
            focus?.close()
            return null
        }
        active.grant = focus
        return ticket
    }

    @Synchronized
    fun attach(ticket: Ticket, sink: Sink) {
        val live = current?.takeIf { it.ticket === ticket && it.grant != null && it.sink == null }
        if (live == null) {
            sink.close()
            throw IOException("Preview no longer active")
        }
        live.sink = sink
        try { sink.play() } catch (t: Throwable) {
            retire(live, discard = true)
            throw t
        }
    }

    @Synchronized
    fun write(ticket: Ticket, bytes: ByteArray, offset: Int, length: Int, maxFrames: Long): Int {
        val active = requireLive(ticket)
        val sink = checkNotNull(active.sink)
        require(length in 1..bytes.size && offset in 0..bytes.size - length && length % 4 == 0)
        require(length / 4 <= maxFrames - active.accepted)
        val count = sink.writeNonBlocking(bytes, offset, length)
        // A short positive write does not keep a revoked generation alive.
        if (current !== active) throw IOException("Preview was interrupted")
        if (count !in 0..length || count % 4 != 0) throw IOException("Invalid preview write")
        active.accepted += count / 4
        return count
    }

    @Synchronized
    fun finishIfPresented(ticket: Ticket, expectedFrames: Long): Boolean {
        val active = requireLive(ticket)
        val read = checkNotNull(active.sink).presentedFrames()
        if (current !== active) throw IOException("Preview was interrupted")
        if (active.accepted != expectedFrames || read !in active.presented..active.accepted)
            throw IOException("Invalid preview presentation count")
        active.presented = read
        if (read != expectedFrames) return false
        completed = ticket
        try { retire(active, discard = false) } catch (t: Throwable) {
            if (completed === ticket) completed = null
            throw t
        }
        return true
    }

    @Synchronized
    fun requireActive(ticket: Ticket) { requireLive(ticket) }

    /** Last presented count observed by [finishIfPresented]; used only as a progress signal. */
    @Synchronized
    fun presentedFrames(ticket: Ticket): Long = requireLive(ticket).presented

    @Synchronized
    fun endOfStream(ticket: Ticket, expectedFrames: Long) {
        val active = requireLive(ticket)
        if (active.accepted != expectedFrames) throw IOException("Preview not fully queued")
        checkNotNull(active.sink).endOfStream(expectedFrames)
    }

    /** A UI accepts a completed preview only once and only for the unrevoked view. */
    @Synchronized
    fun consumeCompletion(ticket: Ticket): Boolean {
        if (completed !== ticket) return false
        completed = null
        return true
    }

    @Synchronized
    fun revoke(ticket: Ticket) {
        if (completed === ticket) completed = null
        current?.takeIf { it.ticket === ticket }?.let { retire(it, discard = true) }
    }

    @Synchronized
    fun revokeAll() {
        completed = null
        current?.let { retire(it, discard = true) }
    }

    private fun requireLive(ticket: Ticket) =
        current?.takeIf { it.ticket === ticket && it.grant != null } ?: throw IOException("Preview no longer active")

    private fun retire(active: Active, discard: Boolean) {
        // Invalidate before touching platform resources, which may call back while being closed.
        current = null
        try {
            active.sink?.let { sink ->
                try { if (discard) sink.discardQueued() } finally { sink.close() }
            }
        } finally {
            active.grant?.close()
        }
    }
}
