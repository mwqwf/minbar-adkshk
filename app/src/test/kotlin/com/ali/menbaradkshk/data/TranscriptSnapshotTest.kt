package com.ali.menbaradkshk.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 📦 لقطة النصوص المضمّنة ووثيقة الخادم تُحلَّلان بالدالّة نفسها — فما يصل مع
 * التطبيق يُقرأ كما يُقرأ ما يصل من الشبكة، والصور بصيغتيها (رابط خام أو `{url}`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSnapshotTest {
    @Test
    fun bundledSnapshotIsParsedLikeServerDocuments() {
        val snapshot = """
            {"generatedAt": 1, "lessons": 3, "items": {
              "lesson-a": {"text": "متن الدرس", "bookTitle": "الكتاب", "sourceRef": "ص ٣",
                           "images": ["https://x/1.jpg", {"url": "https://x/2.jpg"}, {"url": ""}],
                           "contributorName": "زيد"},
              "lesson-b": {"text": "نصّ بلا صور"},
              "": {"text": "معرّف فارغ يُهمل"},
              "lesson-c": "ليست وثيقة"
            }}
        """.trimIndent()
        val items = TranscriptRepository.parseBundledSnapshot(snapshot)
        assertEquals(setOf("lesson-a", "lesson-b"), items.keys)

        val a = TranscriptRepository.parseDocument("lesson-a", items.getValue("lesson-a"))
        assertEquals("متن الدرس", a.text)
        assertEquals("الكتاب", a.bookTitle)
        assertEquals("ص ٣", a.sourceRef)
        assertEquals(listOf("https://x/1.jpg", "https://x/2.jpg"), a.imageUrls)
        assertEquals("زيد", a.contributorName)

        val b = TranscriptRepository.parseDocument("lesson-b", items.getValue("lesson-b"))
        assertTrue(b.imageUrls.isEmpty())
        assertEquals("", b.bookTitle)
    }

    @Test
    fun emptyOrMalformedSnapshotYieldsNothing() {
        assertTrue(TranscriptRepository.parseBundledSnapshot("""{"items":{}}""").isEmpty())
        assertTrue(TranscriptRepository.parseBundledSnapshot("""{"nothing": 1}""").isEmpty())
        assertTrue(TranscriptRepository.parseDocument("x", JSONObject()).text.isEmpty())
    }
}
