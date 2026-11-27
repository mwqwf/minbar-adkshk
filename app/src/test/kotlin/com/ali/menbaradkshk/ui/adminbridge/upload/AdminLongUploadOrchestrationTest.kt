package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdminLongUploadOrchestrationTest {
    @Test fun noTransportWithoutExactReviewAndCurrentConfirmation() = runBlocking {
        withFixture { f ->
            val review = f.engine.review(f.draft)
            assertTrue(f.wire.calls.isEmpty())
            val another = AdminLongUploadOrchestration(f.store, { f.lease }, f.wire)
            fails<IOException> { another.confirmAfterReview(review) }
            val invalid = f.engine.confirmAfterReview(review)
            f.lease = AdminLongUploadLease(f.account) // same account, distinct authorization epoch.
            fails<IOException> { f.engine.send(invalid) }
            fails<IOException> { f.engine.confirmAfterReview(review) }
            val correct = f.engine.confirmAfterReview(f.engine.review(f.draft))
            f.mutate(0)
            fails<IOException> { f.engine.send(correct) }
            assertTrue(f.wire.calls.isEmpty())
            assertEquals(listOf(f.draft), f.store.list())
        }
    }

    @Test fun resumesOnlyMissingAfterAmbiguousWriteAndFreshConfirmation() = runBlocking {
        withFixture { f ->
            val review = f.engine.review(f.draft)
            val initial = f.engine.confirmAfterReview(review)
            f.wire.failOnceAfterPut = true
            fails<IOException> { f.engine.send(initial) }
            assertEquals(listOf("begin", "put:1"), f.wire.calls)
            assertTrue(f.wire.exposed!!.all { it == 0.toByte() })
            fails<IOException> { f.engine.send(initial) } // no automatic/duplicate attempt.
            assertEquals(listOf("begin", "put:1"), f.wire.calls)
            val receipt = f.engine.send(f.engine.confirmAfterReview(review))
            assertEquals(AdminLongUploadReceipt(f.draft.id, f.draft.byteCount, f.draft.sha256), receipt)
            assertEquals(listOf("begin", "put:1", "begin", "put:2", "complete"), f.wire.calls)
            assertTrue(f.wire.exposed!!.all { it == 0.toByte() })
            val recovered = f.engine.send(f.engine.confirmAfterReview(review))
            assertEquals(receipt, recovered)
            assertEquals("begin", f.wire.calls.last())
            assertEquals(1, f.wire.calls.count { it == "complete" })
            assertEquals(listOf(f.draft), f.store.list())
            assertNull(f.store.confirmedIntent(f.draft))
        }
    }

    @Test fun invalidBindingOrPartitionStopsWithoutSendingParts() = runBlocking {
        withFixture { f ->
            val review = f.engine.review(f.draft)
            val transforms: List<(AdminLongUploadState) -> AdminLongUploadState> = listOf(
                { it.copy(protocolVersion = 2) },
                { it.copy(draftId = "00000000-0000-4000-8000-000000000000") },
                { it.copy(totalBytes = it.totalBytes - 1) },
                { it.copy(wholeSha256 = "f".repeat(64)) },
                { it.copy(missing = listOf(2, 1)) },
                { it.copy(verified = listOf(1), missing = listOf(1)) },
                { it.copy(completed = true) },
            )
            for (transform in transforms) {
                f.wire.beginTransform = transform
                fails<IOException> { f.engine.send(f.engine.confirmAfterReview(review)) }
            }
            assertEquals(List(transforms.size) { "begin" }, f.wire.calls)
        }
    }

    @Test fun reverifiesChangedPartAfterBeginAndRejectsFalseComplete() = runBlocking {
        withFixture { f ->
            val approved = f.engine.confirmAfterReview(f.engine.review(f.draft))
            f.wire.afterBegin = { f.mutate(0) }
            fails<IOException> { f.engine.send(approved) }
            assertEquals(listOf("begin"), f.wire.calls)
        }
        withFixture { f ->
            val approved = f.engine.confirmAfterReview(f.engine.review(f.draft))
            f.wire.completeTransform = { it.copy(completed = false) }
            fails<IOException> { f.engine.send(approved) }
            assertEquals(listOf("begin", "put:1", "put:2", "complete"), f.wire.calls)
        }
    }

    @Test fun revocationAndCancellationStopBeforeFurtherTransport() = runBlocking {
        withFixture { f ->
            val approved = f.engine.confirmAfterReview(f.engine.review(f.draft))
            f.wire.beforePut = { f.lease = null }
            fails<IOException> { f.engine.send(approved) }
            assertEquals(listOf("begin"), f.wire.calls)
        }
        withFixture { f ->
            val approved = f.engine.confirmAfterReview(f.engine.review(f.draft))
            f.wire.beforePut = { throw CancellationException("cancel fixture") }
            fails<CancellationException> { f.engine.send(approved) }
            assertEquals(listOf("begin"), f.wire.calls)
        }
    }

    private suspend inline fun <reified T : Throwable> fails(crossinline block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java}, got ${failure?.javaClass}", failure is T)
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val path = Files.createTempDirectory("minbar-multipart-flow-").toFile()
        try { block(Fixture(path)) } finally { path.deleteRecursively() }
    }

    private class Fixture(private val path: File) {
        val account = "a".repeat(64)
        var lease: AdminLongUploadLease? = AdminLongUploadLease(account)
        val store = AdminUploadDraftStore(path, account)
        val wire = RecordedWire()
        val engine = AdminLongUploadOrchestration(store, { lease }, wire)
        val draft = store.prepare("synthetic.opus", MULTIPART_CHUNK_BYTES + 1L) { SyntheticBytes() }
        fun mutate(offset: Long) {
            RandomAccessFile(path.resolve("admin-upload-drafts-v1/$account/${draft.id}/original"), "rw").use {
                it.seek(offset); it.write(3)
            }
        }
    }

    private class SyntheticBytes : InputStream() {
        private var left = MULTIPART_CHUNK_BYTES + 1
        override fun read() = if (left <= 0) -1 else (--left).let { 2 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(left, len)
            b.fill(2, off, off + n)
            left -= n
            return n
        }
    }

    private class RecordedWire : AdminLongUploadWire {
        val calls = mutableListOf<String>()
        var exposed: ByteArray? = null
        var failOnceAfterPut = false
        var beforePut: () -> Unit = {}
        var afterBegin: () -> Unit = {}
        var beginTransform: (AdminLongUploadState) -> AdminLongUploadState = { it }
        var completeTransform: (AdminLongUploadState) -> AdminLongUploadState = { it }
        private var immutableManifest: AdminMultipartManifest? = null
        private val verified = sortedSetOf<Int>()
        private var complete = false
        private fun state(id: String): AdminLongUploadState {
            val bound = requireNotNull(immutableManifest)
            return AdminLongUploadState(1, id, bound.totalBytes, bound.wholeSha256,
                verified.toList(), bound.parts.map { it.index } - verified, complete)
        }
        override suspend fun begin(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState {
            guard()
            calls += "begin"
            if (immutableManifest == null) immutableManifest = manifest
            check(immutableManifest == manifest)
            afterBegin()
            return beginTransform(state(draftId))
        }
        override suspend fun put(
            draftId: String, manifest: AdminMultipartManifest, part: AdminMultipartPart, bytes: ByteArray,
            guard: () -> Unit,
        ): AdminLongUploadState {
            beforePut()
            guard()
            calls += "put:${part.index}"
            exposed = bytes // Assert caller wipes the transient buffer; never a production contract.
            assertEquals(part.bytes, bytes.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(part.sha256, digest)
            verified += part.index
            if (failOnceAfterPut) {
                failOnceAfterPut = false
                throw IOException("Ambiguous synthetic receipt")
            }
            return state(draftId)
        }
        override suspend fun complete(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState {
            guard()
            calls += "complete"
            assertTrue(state(draftId).missing.isEmpty())
            complete = true
            return completeTransform(state(draftId))
        }
    }
}
