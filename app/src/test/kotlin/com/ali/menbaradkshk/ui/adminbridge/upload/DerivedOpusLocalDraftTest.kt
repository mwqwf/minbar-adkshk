package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Test

class DerivedOpusLocalDraftTest {
    private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private class Fixture(dir: File) {
        val account = "a".repeat(64)
        val store = AdminUploadDraftStore(dir, account)
        val one = store.prepare("first.wav", 4L) { byteArrayOf(1,2,3,4).inputStream() }
        val two = store.prepare("second.wav", 4L) { byteArrayOf(5,6,7,8).inputStream() }
        val inOrder = listOf(two, one)
        val job = DerivedOpusLocalDraft.Job(9L, inOrder, 9289L, 1)
    }
    private fun using(block: (Fixture, ByteArray) -> Unit) {
        val dir = Files.createTempDirectory("minbar-draft-fixture").toFile()
        try {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/derived-opus-native.ogg")).use { it.readBytes() }
            check(hash(bytes) == "2e619f483b095dc1c78317aae9baa22e5f20e3f65179ca7acb821dfea6cc0a83")
            block(Fixture(dir), bytes)
        } finally { dir.deleteRecursively() }
    }

    private fun Fixture.result(data: ByteArray) = DerivedOpusLocalDraft.Result(9, inOrder, data.size.toLong(), hash(data))
    private fun Fixture.onlyOriginals() {
        check(store.list().toSet() == inOrder.toSet())
        check(store.stageSnapshot(one.id, 9).second.contentEquals(byteArrayOf(1,2,3,4)))
        check(store.stageSnapshot(two.id, 9).second.contentEquals(byteArrayOf(5,6,7,8)))
    }
    @Test fun exactGuestOutputBecomesSeparateVerifiedLocalDraft() = using { f, bytes ->
        var readbackCalls = 0
        val result = DerivedOpusLocalDraft(f.store, f.account) {}.keepForLocalReview(f.job, f.result(bytes), "review",
            { readbackCalls++; DerivedOpusLocalDraft.PlaybackEvidence(hash(bytes), 9289L) },
            { ByteArrayInputStream(bytes) })
        check(readbackCalls == 1 && result.fileName == "review.ogg" && result.sha256 == hash(bytes))
        check(f.store.list().size == 3 && f.store.stageSnapshot(result.id, 3000).second.contentEquals(bytes))
        check(f.store.stageSnapshot(f.one.id, 9).second.contentEquals(byteArrayOf(1,2,3,4)))
        check(f.store.stageSnapshot(f.two.id, 9).second.contentEquals(byteArrayOf(5,6,7,8)))
    }
    @Test fun refusesWrongAccountOrderOrGenerationBeforeReadingOutput() = using { f, bytes ->
        val cases = listOf(f.account to f.result(bytes).copy(generation = 8),
            f.account to f.result(bytes).copy(orderedOriginals = f.inOrder.reversed()),
            "b".repeat(64) to f.result(bytes))
        for ((account, receipt) in cases) {
            val error = runCatching {
                DerivedOpusLocalDraft(f.store, account) {}.keepForLocalReview(f.job, receipt, "review",
                    { error("must not inspect") }, { error("must not open") })
            }.exceptionOrNull()
            check(error is IOException)
            f.onlyOriginals()
        }
    }
    @Test fun refusesBadHashCrcTruncationTrailingBytesAndCrcCorrectWrongTail() = using { f, bytes ->
        fun badTail(): ByteArray {
            val changed = bytes.copyOf()
            var at = 0
            fun size(): Int {
                val laces = changed[at+26].toInt() and 255
                return 27+laces+(at+27 until at+27+laces).sumOf { changed[it].toInt() and 255 }
            }
            while (at + size() != changed.size) at += size()
            val b = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN)
            b.putLong(at+6, b.getLong(at+6)-1L)
            b.putInt(at+22, 0)
            var crc = 0
            for (i in at until changed.size) {
                crc = crc xor ((changed[i].toInt() and 255) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
            }
            b.putInt(at+22, crc)
            return changed
        }
        val corrupt = bytes.copyOf().apply { this[lastIndex] = this.last().inc() }
        val cases = listOf(f.result(bytes).copy(sha256 = "0".repeat(64)) to bytes) +
            listOf(corrupt, bytes.copyOf(bytes.size-1), bytes + byteArrayOf(0), badTail()).map { f.result(it) to it }
        for ((receipt, stream) in cases) {
            val error = runCatching {
                DerivedOpusLocalDraft(f.store, f.account) {}.keepForLocalReview(f.job, receipt, "review",
                    { DerivedOpusLocalDraft.PlaybackEvidence(receipt.sha256, 9289L) }, { stream.inputStream() })
            }.exceptionOrNull()
            check(error is IOException) { error.toString() }
            f.onlyOriginals()
        }
    }
    @Test fun refusesPlaybackPaddingCancellationAndBytesSwappedAfterEvidence() = using { f, bytes ->
        for (i in 0..2) {
            var atReview = false
            val error = runCatching {
                DerivedOpusLocalDraft(f.store, f.account) {
                    if (i == 1 && atReview) throw CancellationException("lease lost")
                }.keepForLocalReview(f.job, f.result(bytes), "review", {
                    atReview = true
                    DerivedOpusLocalDraft.PlaybackEvidence(hash(bytes), if (i == 0) 9600L else 9289L)
                }, { (if (i == 2) bytes + byteArrayOf(0) else bytes).inputStream() })
            }.exceptionOrNull()
            check(if (i == 1) error is CancellationException else error is IOException)
            f.onlyOriginals()
        }
    }
}
