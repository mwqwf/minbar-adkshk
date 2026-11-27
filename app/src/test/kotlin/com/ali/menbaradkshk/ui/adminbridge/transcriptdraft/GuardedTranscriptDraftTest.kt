package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedTranscriptDraftTest {
    private val op = "12345678-1234-4321-aabc-123456abcdef"
    private val text = "فوائد / \"الدرس\"\nسطر\\ جديد"
    private val intent = GuardedTranscriptDraftIntent("lesson_42", op, 100_000L, text)
    private fun receipt(): Map<String, Any?> =
        mapOf("operationId" to op, "lessonId" to intent.lessonId, "lessonUpdatedAtMs" to 100_000L,
            "text" to text, "savedAtMs" to 100_101L)

    @Test fun canonicalPostExactRecoveryAndNoResponseFallback() {
        val seen = mutableListOf<Triple<String, String, String?>>()
        var fault = true
        val client = GuardedTranscriptDraftClient({ true }) { method, path, bytes ->
            seen += Triple(method, path, bytes?.decodeToString())
            if (fault) { fault = false; throw IOException("response lost") }
            receipt()
        }
        assertThrows(IOException::class.java) { runBlocking { client.save(intent) } }
        assertEquals(1, seen.size)
        val response = runBlocking { client.recover(intent) }
        assertEquals(100_101L, response.savedAtMs)
        val path = "/admin/guarded-transcript-drafts/lesson_42/$op"
        assertEquals(Triple("POST", path, """{"text":"فوائد / \"الدرس\"\nسطر\\ جديد","lessonUpdatedAtMs":100000}"""), seen[0])
        assertEquals(Triple("GET", path, null), seen[1])
        assertEquals(response, runBlocking { client.save(intent) })
        assertEquals(seen[0], seen[2])
    }

    @Test fun wrongRoleOrLostLeaseCannotUseAReceipt() {
        var active = false
        var count = 0
        val client = GuardedTranscriptDraftClient({ active }) { _, _, _ ->
            ++count
            active = false
            receipt()
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { client.save(intent) } }
        assertEquals(0, count)
        active = true
        assertThrows(IllegalStateException::class.java) { runBlocking { client.save(intent) } }
        assertEquals(1, count)
    }

    @Test fun failedResponseCannotMasqueradeAsExactReceipt() {
        for (bad in listOf(
            receipt() + ("lessonId" to "another"), receipt() + ("operationId" to "foreign"),
            receipt() + ("lessonUpdatedAtMs" to 100_000.0), receipt() + ("text" to "مختلف"),
            receipt() + ("savedAtMs" to "100101"), receipt() + ("published" to true),
            receipt() - "savedAtMs",
        )) assertThrows(RuntimeException::class.java) {
            runBlocking { GuardedTranscriptDraftClient({ true }) { _, _, _ -> bad }.recover(intent) }
        }
    }

    @Test fun conservativePlainTextAndStrictPathWithoutSilentChanges() {
        assertTrue(isPassiveDraftText("عنوان الدرس\nشرحُه."))
        assertTrue(isPassiveDraftText("😀".repeat(6_000)))
        for (bad in listOf("", " عنوان", "عنوان\n", "a\u0301", "أ\u200fب", "أ\u2028ب",
                "أ\ud800ب", "أ\tب", "وصف <img>", "رابط https://example.invalid", "رقم &#123;",
                "x@y.example", "user/file", "images/file", "نص *عنوان*", "😀".repeat(6_001))) {
            assertFalse("Rejected literal: $bad", isPassiveDraftText(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(lessonId = "a/b") }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(operationId = op.uppercase()) }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(lessonUpdatedAtMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(lessonUpdatedAtMs = 9_007_199_254_740_991L) }
    }
}
