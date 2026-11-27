package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AdminUploadMultipartManifestTest {
    @Test fun verifiesEntireLegacyCapInTwelvePartsUsingBoundedReads() {
        val source = SyntheticParts(MAX_ORIGINAL_BYTES)
        val manifest = verifyAdminMultipartManifest(source, MAX_ORIGINAL_BYTES, WHOLE) {}
        assertEquals(MAX_ORIGINAL_BYTES, manifest.totalBytes)
        assertEquals(WHOLE, manifest.wholeSha256)
        assertEquals((1..12).toList(), manifest.parts.map { it.index })
        assertEquals(List(11) { MULTIPART_CHUNK_BYTES } + 7 * 1024 * 1024, manifest.parts.map { it.bytes })
        assertEquals(PART_HASHES, manifest.parts.map { it.sha256 })
        assertTrue(source.maxRequest <= 64 * 1024)
        assertEquals(MAX_ORIGINAL_BYTES, source.position)
    }

    @Test fun checksBothSizeEdgesBeforeAccessAndDetectsExtraTruncatedStalledOrAlteredSources() {
        val untouched = SyntheticParts(10)
        for (size in listOf(0L, MAX_ORIGINAL_BYTES + 1)) {
            assertThrows(DraftSizeException::class.java) { verifyAdminMultipartManifest(untouched, size, WHOLE) {} }
        }
        assertEquals(0L, untouched.position)
        assertEquals("4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
            verifyAdminMultipartManifest(byteArrayOf(1).inputStream(), 1, ONE) {}.parts.single().sha256)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2), byteArrayOf(2))) {
            assertThrows(IOException::class.java) { verifyAdminMultipartManifest(bytes.inputStream(), 1, ONE) {} }
        }
        val stalled = object : InputStream() {
            override fun read() = -1
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertThrows(IOException::class.java) { verifyAdminMultipartManifest(stalled, 1, ONE) {} }
    }

    @Test fun cancellationInterruptsStreamingBeforeTheNextRead() {
        val source = SyntheticParts(MAX_ORIGINAL_BYTES)
        var calls = 0
        assertThrows(CancellationException::class.java) {
            verifyAdminMultipartManifest(source, MAX_ORIGINAL_BYTES, WHOLE) {
                if (++calls == 3) throw CancellationException("local session lost")
            }
        }
        assertEquals(128 * 1024L, source.position)
    }

    @Test fun accountAndFrozenDraftAreVerifiedAndOriginalIsNeverReplaced() {
        val directory = Files.createTempDirectory("minbar-multipart-").toFile()
        try {
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            val store = AdminUploadDraftStore(directory, a)
            val length = MULTIPART_CHUNK_BYTES + 1L
            val source = SyntheticParts(length)
            val draft = store.prepare("fixture.opus", length) { source }
            val original = directory.resolve("admin-upload-drafts-v1/$a/${draft.id}/original")
            val manifest = store.verifyMultipartManifest(a, draft) {}
            assertEquals(listOf(MULTIPART_CHUNK_BYTES, 1), manifest.parts.map { it.bytes })
            assertEquals("bb929bbdce85fdbc903a463e96630a25f8b6ef5b76090e24cd92ea8fb47b2f65",
                manifest.parts.first().sha256)
            assertEquals(draft.sha256, manifest.wholeSha256)
            assertThrows(IOException::class.java) { store.verifyMultipartManifest(b, draft) {} }
            assertThrows(IOException::class.java) {
                AdminUploadDraftStore(directory, b).verifyMultipartManifest(b, draft) {}
            }
            assertThrows(CancellationException::class.java) {
                store.verifyMultipartManifest(a, draft) { throw CancellationException("admin locked") }
            }
            assertEquals(manifest, store.verifyMultipartManifest(a, draft) {})
            assertNull(store.confirmedIntent(draft))
            assertEquals(listOf(draft), store.list())
            RandomAccessFile(original, "rw").use { it.write(4) }
            assertThrows(IOException::class.java) { store.verifyMultipartManifest(a, draft) {} }
            assertEquals(4, original.inputStream().use { it.read() })
            assertEquals(length, original.length())
            assertEquals(listOf(draft), store.list())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class SyntheticParts(private val length: Long) : InputStream() {
        var position = 0L
            private set
        var maxRequest = 0
            private set
        override fun read(): Int = if (position >= length) -1 else (position++ / MULTIPART_CHUNK_BYTES).toInt() + 1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= length) return -1
            maxRequest = maxOf(maxRequest, len)
            val value = (position / MULTIPART_CHUNK_BYTES).toInt() + 1
            val n = minOf(len.toLong(), length - position, value.toLong() * MULTIPART_CHUNK_BYTES - position).toInt()
            b.fill(value.toByte(), off, off + n)
            position += n
            return n
        }
    }

    private companion object {
        // Reference output generated independently via Python hashlib, using repeated 1..12 byte values.
        const val ONE = "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a"
        const val WHOLE = "7741c9104bb73d7c67ab02a65268b73a8145d87684fa97087322792a4eea73a2"
        val PART_HASHES = listOf(
            "bb929bbdce85fdbc903a463e96630a25f8b6ef5b76090e24cd92ea8fb47b2f65",
            "854edd4e82fb2fcf0dbaa5c21f28cd6f1e14b6bd2d91cfa53a875b9f9c9bffe2",
            "5c2f9c648170787fbd27f7f65f575844241ccce148b1cb78760d60736dcb3380",
            "0833809a95afb59419677c63883d4ec63222ac834611e69d98bd3e049a919212",
            "a91cb230394104debd08c5e863a319c6079c7ca7af3583f7a5b09c79146b08d9",
            "d19ec822f3c1bca5664b4dd2f76eb9b82884b333e2890b60d9829106cce1eeec",
            "15d3670274ee9cc36af50fee9e6d49164c9eec2228d48fe29e4f313aa3784a91",
            "c337af78a0a16c6da6b35c3b46ea2291cfadc7a55cbb0d3f09fd80134f26a5a4",
            "4c17898c47e67206e412a8092e4a8d33f29ae6bf775a58072cb483428049cf21",
            "de17c9003877ef557ee21033d7cb839c4ea64cc3365f26f7ec429c428d269050",
            "63d102527006eb9d7ceaa749bf66302543d599dc720c2615f06e88fba890de58",
            "4f64009f1db53b6f82b9b05dafaecd9cf443a230d0b6e89453d8e974d95f0f34",
        )
    }
}
