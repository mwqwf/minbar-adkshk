package com.ali.menbaradkshk.adminsurface.review

import org.junit.Assert.*
import org.junit.Test

class AdminTranscriptImagePreviewTest {
    @Test fun onlyCanonicalFirstPage() {
        val base = "/admin/transcript-review/tsub_${"b".repeat(20)}/images/"
        assertTrue(isPendingImagePath(base + "0"))
        for (bad in listOf("1", "00", "-1", "0?x=1", "0/other", "0#x")) assertFalse(isPendingImagePath(base + bad))
        assertTrue(isPendingImagePath("/admin/transcript-review/tsub_1234567890123_abcd12/images/0"))
    }

    @Test fun responseMustBeExplicitBoundedNoStoreBinary() {
        for (mime in listOf("image/jpeg", "image/png", "image/webp")) {
            assertEquals(10_485_760, previewResponseLength(mime, "10485760", "private, no-store", null))
        }
        for (length in listOf(null, "0", "01", "10485761", "1, 1", "7e3")) {
            assertThrows(IllegalArgumentException::class.java) { previewResponseLength("image/png", length, "private, no-store", null) }
        }
        for (cache in listOf(null, "public, no-store", "private", "private, no-cache")) {
            assertThrows(IllegalArgumentException::class.java) { previewResponseLength("image/png", "1", cache, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { previewResponseLength("image/svg+xml", "1", "private, no-store", null) }
        assertThrows(IllegalArgumentException::class.java) { previewResponseLength("image/png", "1", "private, no-store", "gzip") }
    }

    @Test fun decodedBoundsCheckBeforeSamplingAndRejectExcessivePixelCount() {
        assertEquals(4, privateImageSample(6144, 3000))
        assertEquals(2, privateImageSample(3072, 1536))
        assertEquals(1, privateImageSample(1536, 1500))
        for ((w, h) in listOf(6144 to 6144, 6145 to 1, 0 to 20, -1 to 1)) {
            assertThrows(IllegalArgumentException::class.java) { privateImageSample(w, h) }
        }
    }
}
