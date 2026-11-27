package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDraftImageTest {
    private val fixture = Base64.getDecoder().decode("/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAAQABADASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAFF/f//Z")
    private val target = DraftImageTarget("fixture_lesson", "12345678-1234-4567-8abc-abcdef012340")
    private val cache = "private, no-store"
    private val name = "attachment; filename=\"transcript-draft.jpg\""

    @Test fun exactWorkerFixtureAndFreshMetadataRemoval() {
        val expected = OwnedDraftJpeg(fixture)
        assertEquals("89221cae367a53719ef1159e9dd465bab49a38fba84e18af840baeb49100ba69", expected.digest)
        val app1 = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 4, 1, 2)
        val withExif = fixture.copyOfRange(0, 2) + app1 + fixture.copyOfRange(2, fixture.size)
        assertNull(draftJpegShape(withExif))
        val stripped = stripFreshJpegMetadata(withExif)
        assertEquals(expected.shape, draftJpegShape(stripped))
        assertTrue(stripped.size < withExif.size)
        assertNull(draftJpegShape(fixture + byteArrayOf(0)))
        assertNull(draftJpegShape(ByteArray(MAX_DRAFT_JPEG_BYTES + 1)))
        assertFalse(validDraftImageShape(2048,2048))
        assertTrue(validDraftImageShape(1000,2000))
    }

    @Test fun privateHeaderAndLengthChecks() {
        val raw = DraftImageFetch(fixture, "image/jpeg", fixture.size.toString(), cache, "nosniff", name)
        assertArrayEquals(fixture, raw.verified().copyBytes())
        for (other in listOf(raw.copy(mediaType = "text/html"), raw.copy(disposition = null),
            raw.copy(cacheControl = "public, no-store"), raw.copy(length = "0" + fixture.size), raw.copy(noSniff = null))) {
            assertThrows(IllegalArgumentException::class.java) { other.verified() }
        }
    }

    @Test fun exactReceiptAndActiveSessionBoundary() {
        val bytes = OwnedDraftJpeg(fixture)
        var active = false
        var count = 0
        var size: Any = bytes.size
        val client = TranscriptDraftImageClient({ active }, { path, jpeg ->
            ++count
            assertEquals(target.path, path)
            assertArrayEquals(fixture, jpeg)
            mapOf("operationId" to target.operationId, "lessonId" to target.lessonId, "sha256" to bytes.digest, "sizeBytes" to size)
        }, { error("No GET during PUT") })
        assertThrows(IllegalStateException::class.java) { runBlocking { client.save(target, bytes) } }
        assertEquals(0, count)
        active = true
        runBlocking { client.save(target, bytes) }
        size = bytes.size.toDouble()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.save(target, bytes) } }
        assertEquals(2, count)
        assertFalse(isDraftImagePath(target.path + "?a=1"))
        assertTrue(isDraftImagePath(target.path))
    }
}
