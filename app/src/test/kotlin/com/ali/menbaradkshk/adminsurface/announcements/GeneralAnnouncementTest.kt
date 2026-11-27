package com.ali.menbaradkshk.adminsurface.announcements

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GeneralAnnouncementTest {
    private val now = 1_800_000_000_000L
    private val expires = now + 120_000L
    private val op = "12345678-1234-4321-aabc-123456abcdef"
    private val capsule = "$expires.bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb.${"a".repeat(64)}"
    private val intent = GeneralNoticeIntent(op, "مَثَل / \"اختبار\"", "سطر\\رابط\n😀 نهاية")
    private val exact = """{"operationId":"$op","title":"مَثَل / \"اختبار\"","body":"سطر\\رابط\n😀 نهاية"}"""
    private fun reviewResponse() = mapOf("operationId" to op, "title" to intent.title, "body" to intent.body,
        "audience" to "public", "delivery" to "polling", "expiresAtMs" to expires, "confirmation" to capsule)
    private fun receipt() = mapOf("operationId" to op, "noticeId" to "gnotice_${"b".repeat(64)}",
        "publishedAtMs" to now, "published" to true, "audience" to "public", "delivery" to "polling")

    @Test fun exactReviewAndExplicitConfirmUseSeparateCanonicalRequests() {
        val seen = mutableListOf<Pair<String, String>>()
        val client = GeneralNoticeClient({ true }, { path, bytes ->
            seen += path to bytes.decodeToString()
            if (path == GENERAL_NOTICE_REVIEW) reviewResponse() else receipt()
        }) { now }
        val review = runBlocking { client.review(intent) }
        assertEquals(listOf(GENERAL_NOTICE_REVIEW to exact), seen)
        val result = runBlocking { client.confirm(review) }
        assertEquals("gnotice_${"b".repeat(64)}", result.noticeId)
        assertEquals(GENERAL_NOTICE_CONFIRM to exact.dropLast(1).plus(""","confirmation":"$capsule"}"""), seen.last())
    }

    @Test fun expiresAndRoleLossNeverStartConfirmation() {
        var clock = now
        var active = false
        var calls = 0
        val client = GeneralNoticeClient({ active }, { _, _ -> ++calls; reviewResponse() }) { clock }
        assertThrows(IllegalStateException::class.java) { runBlocking { client.review(intent) } }
        assertEquals(0, calls)
        active = true
        val review = runBlocking { client.review(intent) }
        active = false
        assertThrows(IllegalStateException::class.java) { runBlocking { client.confirm(review) } }
        active = true; clock = expires
        assertThrows(IllegalStateException::class.java) { runBlocking { client.confirm(review) } }
        assertEquals(1, calls)
    }

    @Test fun anAlteredReviewOrUnverifiableReceiptDoesNotSucceed() {
        for (bad in listOf(
            reviewResponse() + ("title" to "تغيّر"),
            reviewResponse() + ("audience" to "topic"),
            reviewResponse() + ("expiresAtMs" to expires.toString()),
            reviewResponse() + ("extra" to true),
        )) {
            assertThrows(RuntimeException::class.java) {
                runBlocking { GeneralNoticeClient({ true }, { _, _ -> bad }) { now }.review(intent) }
            }
        }
        val wrong = listOf(receipt() + ("published" to "true"), receipt() + ("noticeId" to "foreign"),
            receipt() + ("delivery" to "push"), receipt() + ("publishedAtMs" to expires))
        for (bad in wrong) {
            val client = GeneralNoticeClient({ true }, { path, _ ->
                if (path == GENERAL_NOTICE_REVIEW) reviewResponse() else bad
            }) { now }
            val review = runBlocking { client.review(intent) }
            assertThrows(RuntimeException::class.java) { runBlocking { client.confirm(review) } }
        }
    }

    @Test fun unavailableConfirmDoesNotRetryOrChangeOperation() {
        val sent = mutableListOf<ByteArray>()
        val client = GeneralNoticeClient({ true }, { path, wire ->
            if (path == GENERAL_NOTICE_REVIEW) reviewResponse() else {
                sent += wire
                if (sent.size == 1) throw IOException("response unavailable")
                receipt()
            }
        }) { now }
        val reviewed = runBlocking { client.review(intent) }
        assertThrows(IOException::class.java) { runBlocking { client.confirm(reviewed) } }
        assertEquals(1, sent.size)
        runBlocking { client.confirm(client.review(intent)) }
        assertEquals(sent[0].decodeToString(), sent[1].decodeToString())
    }

    @Test fun unicodeReviewUsesNfcAndOnlyBodyMayContainNewline() {
        assertEquals("á", cleanGeneralNoticeText(" a\u0301 ", 120, false))
        assertEquals("أ\nب", cleanGeneralNoticeText("أ\nب", 640, true))
        for (invalid in listOf("أ\nب", "أ\rب", "أ\u2028ب", "أ\u2029ب", "أ\u200fب", "أ\uD800ب")) {
            assertNull(cleanGeneralNoticeText(invalid, 120, false))
        }
        assertNull(cleanGeneralNoticeText("😀".repeat(61), 120, false))
        assertNull(cleanGeneralNoticeText("\u0000عنوان", 120, false))
    }
}
