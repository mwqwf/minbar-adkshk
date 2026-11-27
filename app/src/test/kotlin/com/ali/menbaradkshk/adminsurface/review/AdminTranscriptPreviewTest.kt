package com.ali.menbaradkshk.adminsurface.review

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminTranscriptPreviewTest {
    private val id = "tsub_0123456789abcdef0123"
    private val path = "/admin/transcript-review"

    @Test fun onlyLiteralTrueEnablesReadingAndNoCallsWithoutLease() = runBlocking {
        assertFalse(canReadPendingTranscriptReview(JSONObject()))
        assertFalse(canReadPendingTranscriptReview(JSONObject().put("canReadPendingTranscriptReview", "true")))
        assertFalse(canReadPendingTranscriptReview(JSONObject().put("canReadPendingTranscriptReview", 1)))
        assertTrue(canReadPendingTranscriptReview(JSONObject().put("canReadPendingTranscriptReview", true)))
        var calls = 0
        val reader = AdminTranscriptPreviewReader({ false }) { calls++; page() }
        assertTrue(runCatching { reader.queue() }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun previewRequiresExplicitSecondReadAndDropsRevokedLeaseResponse() = runBlocking {
        var allowed = true
        val paths = mutableListOf<String>()
        val reader = AdminTranscriptPreviewReader({ allowed }) {
            paths += it
            if (it == path) page() else {
                allowed = false
                detail()
            }
        }
        val entry = reader.queue().items.single()
        assertEquals(listOf(path), paths)
        assertTrue(runCatching { reader.preview(entry) }.isFailure)
        assertEquals(listOf(path, "$path/$id"), paths)
        assertFalse(reader.isAvailable())
    }

    @Test fun boundedProjectionRejectsExtraFieldsMalformedIdAndOversizedQueue() = runBlocking {
        val arrays = listOf(
            JSONArray().put(row().put("submitter_name", "synthetic")),
            JSONArray().put(row().put("id", "../bad/path")),
            JSONArray().put(row().put("textChars", "14")),
            JSONArray().put(row()).put(row()),
            JSONArray(List(21) { row().put("id", "tsub_" + it.toString(16).padStart(20, '0')) }),
        )
        for (entries in arrays) {
            val reader = AdminTranscriptPreviewReader({ true }) { page(entries) }
            assertTrue(runCatching { reader.queue() }.isFailure)
        }
    }

    @Test fun detailMatchesExactRequestedOpaqueIdAndRendersPlainUnlinkedText() = runBlocking {
        var response = detail()
        val reader = AdminTranscriptPreviewReader({ true }) { if (it == path) page() else response }
        val entry = reader.queue().items.single()
        assertEquals("نصّ <b>عربي</b>", reader.preview(entry).text)
        for (invalid in listOf(
            detail().put("id", "tsub_0123456789abcdef2333"),
            detail().put("imageKeys", JSONArray()),
            detail().put("createdHourMs", 7_200_000),
            detail().put("text", "x".repeat(20_001)),
            detail().put("text", "x\u202Ey"),
        )) {
            response = invalid
            assertTrue(runCatching { reader.preview(entry) }.isFailure)
        }
        val pages = AdminTranscriptPreviewReader({ true }) {
            require(it == path)
            page(JSONArray().put(row().put("kind", "pages").put("textChars", 0)))
        }
        assertTrue(runCatching { pages.queue() }.isFailure)
    }

    private fun row() = JSONObject().put("id", id).put("kind", "text").put("textChars", 15)
        .put("createdHourMs", 3_600_000)
    private fun page(entries: JSONArray = JSONArray().put(row())) =
        JSONObject().put("items", entries).put("truncated", false)
    private fun detail() = JSONObject().put("id", id).put("kind", "text")
        .put("text", "نصّ <b>عربي</b>").put("createdHourMs", 3_600_000)
}
