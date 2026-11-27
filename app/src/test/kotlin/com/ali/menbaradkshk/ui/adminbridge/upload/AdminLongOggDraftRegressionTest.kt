package com.ali.menbaradkshk.ui.adminbridge.upload

import com.ali.menbaradkshk.ui.adminbridge.AdminRole
import com.ali.menbaradkshk.ui.adminbridge.AdminGrant
import com.ali.menbaradkshk.ui.adminbridge.openVerifiedSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdminLongOggDraftRegressionTest {
    @Test fun oversizedValidLongOggRemainsLocalAndUnchanged() {
        val limit = 8_388_607
        assertEquals(limit, MAX_STAGE_BYTES)
        val source = makeLongOgg(limit + 1)
        val fixtureOutput = System.getProperty("minbar.syntheticOggProof")
        if (!fixtureOutput.isNullOrBlank()) File(fixtureOutput).writeBytes(source)
        assertEquals(8_388_608, source.size)
        assertRetainedOversize(source, limit)
        val merged = System.getProperty("minbar.syntheticMergedOggInput")
        if (!merged.isNullOrBlank()) {
            val file = File(merged)
            require(file.length() in (limit + 1L)..MAX_ORIGINAL_BYTES)
            assertRetainedOversize(file.readBytes(), limit)
        }
    }

    private fun assertRetainedOversize(source: ByteArray, limit: Int) {
        assertTrue(source.size > limit)
        val temp = kotlin.io.path.createTempDirectory().toFile()
        try {
            val account = "a".repeat(64)
            val store = AdminUploadDraftStore(temp, account)
            val draft = store.prepare("long.opus", source.size.toLong(), validateAudio = {
                assertEquals(ReviewedAudioContainer.Opus, readAudioContainer(it.inputStream().use { input -> input.readNBytes(128) }))
            }) { source.inputStream() }
            val original = temp.resolve("admin-upload-drafts-v1/$account/${draft.id}/original")
            assertEquals(source.size.toLong(), draft.byteCount)
            assertEquals(sha(source), draft.sha256)
            val session = AdminGrant(
                role = AdminRole.Supervisor, canIssueInvites = false, canEditLessonMetadata = true,
                accountKey = account, stageUploadMaxBytes = limit,
            ).openVerifiedSession("ms_" + "0".repeat(64)) { fail("No request/auth rejection expected") }
            val intent = UploadStageIntent("Synthetic lesson", "", "", "cat", "child", "Section", "Child", "audio/opus")
            var calls = 0
            val fakeWire = UploadStageWire { _, _, _, _ ->
                calls++
                throw AssertionError("No wire call expected for oversized audio")
            }
            runBlocking {
                assertTrue(runCatching { session.inspectPrivateUpload(store, draft.id) }.exceptionOrNull() is DraftSizeException)
                assertTrue(runCatching { session.stagePrivateUpload(store, draft.id, intent, offlineTestWire = fakeWire) }.exceptionOrNull() is DraftSizeException)
            }
            assertEquals(0, calls)
            val reopened = AdminUploadDraftStore(temp, account)
            assertEquals(listOf(draft), reopened.list())
            assertNull(reopened.confirmedIntent(draft))
            assertEquals(source.size.toLong(), original.length())
            assertEquals(draft.sha256, sha(original.readBytes()))
        } finally { temp.deleteRecursively() }
    }

    @Test fun exactlyAdvertisedByteBoundaryCanBeSnapshottedLocally() {
        val temp = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = makeLongOgg(8_388_607)
            val store = AdminUploadDraftStore(temp, "a".repeat(64))
            val draft = store.prepare("boundary.opus", null) { source.inputStream() }
            val (record, snapshot) = store.stageSnapshot(draft.id, MAX_STAGE_BYTES)
            assertEquals(draft, record)
            assertEquals(8_388_607, snapshot.size)
            assertEquals(sha(source), sha(snapshot))
            assertTrue(runCatching { store.stageSnapshot(draft.id, MAX_STAGE_BYTES - 1) }.exceptionOrNull() is DraftSizeException)
        } finally { temp.deleteRecursively() }
    }

    private fun makeLongOgg(size: Int): ByteArray {
        val out = ByteArrayOutputStream(size)
        val opusHead = Base64.getDecoder().decode("T3B1c0hlYWQBATgBgLsAAAAAAA==")
        val packet = Base64.getDecoder().decode(OPUS_PACKET)
        // One real 20 ms Opus packet from a synthetic 880 Hz tone, repaged with valid sequence,
        // granules and CRC. Vendor bytes only tune the exact boundary, never audio quality.
        val packetPageBytes = 27 + 2 + packet.size
        val packetCount = (size - 110) / packetPageBytes
        val tagSize = size - (27 + 1 + opusHead.size) - packetCount * packetPageBytes - 27
        val vendorSize = (0..tagSize).first { val length = it + 16; length + length / 255 + 1 == tagSize }
        val tags = ByteBuffer.allocate(vendorSize + 16).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".encodeToByteArray()).putInt(vendorSize)
            .put(ByteArray(vendorSize) { 'x'.code.toByte() }).putInt(0).array()
        out.write(page(opusHead, 0, 0, 2))
        out.write(page(tags, 1, 0, 0))
        repeat(packetCount) { index ->
            out.write(page(packet, index + 2, (index + 1) * 960L, if (index == packetCount - 1) 4 else 0))
        }
        return out.toByteArray().also { check(it.size == size) }
    }

    private fun page(packet: ByteArray, sequence: Int, granule: Long, flags: Int): ByteArray {
        val full = packet.size / 255
        val bytes = ByteBuffer.allocate(27 + full + 1 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".encodeToByteArray()).put(0).put(flags.toByte()).putLong(granule)
            .putInt(37).putInt(sequence).putInt(0).put((full + 1).toByte())
            .apply { repeat(full) { put(255.toByte()) } }.put((packet.size % 255).toByte()).put(packet).array()
        var crc = 0
        for (byte in bytes) crc = (crc shl 8) xor CRC[((crc ushr 24) xor byte.toInt()) and 255]
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        return bytes
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private companion object {
        val CRC = IntArray(256) { n ->
            var x = n shl 24
            repeat(8) { x = (x shl 1) xor if (x < 0) 0x04c11db7 else 0 }
            x
        }
        const val OPUS_PACKET = "+J66K8zyJLBFm3/lZu1oB2dXdFbTz2VweMxPzKBBryPy/9nnYOfC+ESWPOsvDPiUDU6xL2CqUbKTXSGs0SmE27np6QxrCgccne7jfy57VRd40SZiWJe12lbbtWEb/+Yzznz80vWpZtbTOxSAXpYrB2aXST1QKag7qjAm9u7m+iciDsffG+ImRtEz4QjSeDF1Dlq0qwV1BnERKp65Byi1kgkKRb6wF479EOfTA0q1EOfQVssH6nn96pEuR1KnQ2MjbWcDto4/iRwZ0gcRJSR3hTPoVEbsSPi0HuekYIm9sdVwsCvFe75Dx+e6sugV9x1EHjjmgDjU8WDSWZlzek4PmCr5TJmUMe10POFzFPUHp1UFa7Mo2iQC1w=="
    }
}
