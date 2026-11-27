package com.ali.menbaradkshk.adminsurface.review

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminTranscriptPublicationTest {
    private val id = "tsub_" + "a".repeat(20)
    private val nested = "/admin/transcript-publication-candidates/$id"
    private val now = 1_800_000_000_000L

    private inner class Fixture {
        var permitted = true
        var text = "نص الاختبار"
        var sent = 0
        var ticketAllowed = true
        var wrongReceipt = false
        fun info() = JSONObject().put("id", id).put("lessonId", "lesson_1").put("lessonTitle", "درس")
            .put("acceptedAtMs", now - 10)
        val client = AdminTranscriptPublication({ permitted }, { now }) { method, path, body ->
            when {
                path == nested.substringBeforeLast('/') -> JSONObject()
                    .put("items", JSONArray().put(info().put("textChars", text.length)))
                    .put("truncated", false)
                path == nested -> info().put("text", text).put("textSha256", transcriptDigest(text))
                path == "$nested/publication-ticket" -> {
                    check(ticketAllowed)
                    info().apply { remove("acceptedAtMs") }.put("textSha256", transcriptDigest(text))
                        .put("ticket", "tp1.${now + 100_000}.${"c".repeat(32)}.${"d".repeat(64)}")
                        .put("revision", "b".repeat(64)).put("expiresAtMs", now + 100_000)
                }
                method == "POST" && path == "$nested/publish" -> {
                    sent++
                    assertEquals(setOf("ticket", "expectedRevision", "expectedTextSha256"), body!!.keys().asSequence().toSet())
                    JSONObject().put("ok", true).put("id", id).put("lessonId", "lesson_1")
                        .put("status", if (wrongReceipt) "approved" else "published")
                }
                else -> error("Unexpected route")
            }
        }
        suspend fun visible() = client.detail(client.queue().items.single())
    }

    @Test fun explicitOneShotPublishUsesExactFinalReceipt() = runBlocking {
        val f = Fixture()
        val ticket = f.client.prepare(f.visible())
        assertEquals(0, f.sent)
        f.client.publish(ticket)
        assertEquals(1, f.sent)
        assertTrue(runCatching { f.client.publish(ticket) }.isFailure)
        assertEquals(1, f.sent)
    }

    @Test fun changedTextLostPermissionOrNoPlaybackWitnessCannotWrite() = runBlocking {
        val f = Fixture()
        val armed = f.client.prepare(f.visible())
        f.text += " جديد"
        assertTrue(runCatching { f.client.publish(armed) }.isFailure)
        f.ticketAllowed = false
        assertTrue(runCatching { f.client.prepare(f.visible()) }.isFailure)
        f.permitted = false
        assertTrue(runCatching { f.client.queue() }.isFailure)
        assertEquals(0, f.sent)
    }

    @Test fun acceptanceReceiptNeverCountsAsPublicationAndNoRetry() = runBlocking {
        val f = Fixture().also { it.wrongReceipt = true }
        val ticket = f.client.prepare(f.visible())
        assertTrue(runCatching { f.client.publish(ticket) }.isFailure)
        assertTrue(runCatching { f.client.publish(ticket) }.isFailure)
        assertEquals(1, f.sent)
        assertFalse(isTranscriptPublicationPath("POST", "$nested/publish?now=1"))
        assertFalse(isTranscriptPublicationPath("GET", "$nested/publish"))
    }
}
