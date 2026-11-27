package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranDownloadRangeTest {
    @Test fun acceptsOnlyTheRemainingBytesOfTheSameSizedRepresentation() {
        assertEquals(8L, quranDownloadRangeTotal("bytes 4-7/8", 4, 4))
        assertEquals(8L, quranDownloadRangeTotal("bytes 4-7/8", 4, null))
        for (header in listOf("bytes 0-3/8", "bytes 4-6/8", "bytes 4-8/8", "bytes 4-7/*",
            "bytes 4-7/9223372036854775808", "bytes 4-3/4", null)) {
            assertNull(header, quranDownloadRangeTotal(header, 4, 4))
        }
        assertNull(quranDownloadRangeTotal("bytes 4-7/8", 4, 3))
        assertNull(quranDownloadRangeTotal("bytes 0-7/8", 0, 8))
    }

    @Test fun rejectsWeakAndMalformedValidators() {
        assertEquals("\"v1\"", strongQuranDownloadETag("\"v1\""))
        for (tag in listOf("W/\"v1\"", "v1", "\"a b\"", "\"a\nb\"", null)) {
            assertNull(tag, strongQuranDownloadETag(tag))
        }
    }
}
