package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PrivateOpusPreviewOwnershipTest {
    private class Sink(val events: MutableList<String>) : PrivateOpusPreviewOwnership.Sink {
        var onWrite: () -> Unit = {}
        var head: Long = 0
        var count: Int = 4
        override fun play() { events += "play" }
        override fun writeNonBlocking(bytes: ByteArray, offset: Int, size: Int): Int {
            events += "write:$offset"
            onWrite()
            return count
        }
        override fun presentedFrames() = head
        override fun discardQueued() { events += "discard" }
        override fun close() { events += "release" }
    }

    @Test fun interruptedPositiveShortWriteCannotRetryOrComplete() {
        val owner = PrivateOpusPreviewOwnership()
        val events = mutableListOf<String>()
        val old = owner.begin { Closeable { events += "abandon" } }!!
        val sink = Sink(events)
        owner.attach(old, sink)
        sink.onWrite = { owner.revoke(old) }
        assertThrows(IOException::class.java) { owner.write(old, ByteArray(8), 0, 8, 2) }
        assertThrows(IOException::class.java) { owner.write(old, ByteArray(8), 4, 4, 2) }
        assertThrows(IOException::class.java) { owner.finishIfPresented(old, 2) }
        assertEquals(listOf("play", "write:0", "discard", "release", "abandon"), events)
    }

    @Test fun replacementPausesOldAndIgnoresItsLateCallbacks() {
        val owner = PrivateOpusPreviewOwnership()
        val events = mutableListOf<String>()
        val old = owner.begin { Closeable { events += "abandon-previous" } }!!
        owner.attach(old, Sink(events))
        val new = owner.begin { events += "request-new"; Closeable { events += "abandon-new" } }!!
        owner.attach(new, Sink(events).also { it.head = 1 })
        owner.revoke(old)
        assertThrows(IOException::class.java) { owner.finishIfPresented(old, 1) }
        assertEquals(4, owner.write(new, ByteArray(4), 0, 4, 1))
        assertTrue(owner.finishIfPresented(new, 1))
        assertFalse(owner.consumeCompletion(old))
        assertTrue(owner.consumeCompletion(new))
        assertFalse(owner.consumeCompletion(new))
        assertEquals(listOf("play", "discard", "release", "abandon-previous", "request-new", "play",
            "write:0", "release", "abandon-new"), events)
    }

    @Test fun denialAndReentrantLossDoNotStartTrack() {
        val owner = PrivateOpusPreviewOwnership()
        assertNull(owner.begin { null })
        val events = mutableListOf<String>()
        lateinit var rejected: PrivateOpusPreviewOwnership.Ticket
        assertNull(owner.begin { ticket ->
            rejected = ticket
            owner.revoke(ticket)
            Closeable { events += "abandon" }
        })
        assertThrows(IOException::class.java) { owner.attach(rejected, Sink(events)) }
        assertEquals(listOf("abandon", "release"), events)
    }

    @Test fun healthyPartialWritesWaitForPresentation() {
        val owner = PrivateOpusPreviewOwnership()
        val events = mutableListOf<String>()
        val ticket = owner.begin { Closeable { events += "abandon" } }!!
        val sink = Sink(events).also { it.count = 0 }
        owner.attach(ticket, sink)
        val buffer = ByteArray(8)
        assertEquals(0, owner.write(ticket, buffer, 0, 8, 2))
        sink.count = 4
        assertEquals(4, owner.write(ticket, buffer, 0, 8, 2))
        assertEquals(4, owner.write(ticket, buffer, 4, 4, 2))
        sink.head = 1
        assertFalse(owner.finishIfPresented(ticket, 2))
        sink.head = 2
        assertTrue(owner.finishIfPresented(ticket, 2))
        owner.revoke(ticket)
        assertFalse(owner.consumeCompletion(ticket))
        assertEquals(listOf("play", "write:0", "write:0", "write:4", "release", "abandon"), events)
    }
}
