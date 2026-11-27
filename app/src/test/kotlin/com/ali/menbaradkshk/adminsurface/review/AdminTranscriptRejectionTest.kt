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
class AdminTranscriptRejectionTest {
    private val root = "/admin/transcript-review"
    private val id = "tsub_0123456789abcdef0123"
    private val detailPath = "$root/$id"
    private val ticketPath = "$detailPath/rejection-ticket"
    private val rejectPath = "$detailPath/reject"
    private val revision = "b".repeat(64)

    @Test fun onlyLiteralGrantAndExactMethodPathsAreAccepted() = runBlocking {
        for (value in listOf("true", 1, false, JSONObject.NULL)) {
            assertFalse(canRejectPendingTranscriptReview(JSONObject().put("canRejectPendingTranscriptReview", value)))
        }
        assertTrue(canRejectPendingTranscriptReview(JSONObject().put("canRejectPendingTranscriptReview", true)))
        assertTrue(isTranscriptRejectionPath("GET", ticketPath))
        assertTrue(isTranscriptRejectionPath("POST", rejectPath))
        assertFalse(isTranscriptRejectionPath("POST", ticketPath))
        assertFalse(isTranscriptRejectionPath("GET", "$ticketPath?status=all"))
        assertFalse(isTranscriptRejectionPath("POST", "$detailPath/%2e%2e/reject"))
        val f = Fixture()
        f.rejectAllowed = false
        assertTrue(runCatching { f.rejection.prepare(f.item, TranscriptPreview(f.text)) }.isFailure)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun twoExplicitStepsReopenSameTextAndPostOnceWithClosedReason() = runBlocking {
        val f = Fixture()
        val approved = f.rejection.prepare(f.item, TranscriptPreview(f.text))
        assertEquals(listOf("GET $detailPath", "GET $ticketPath"), f.calls)
        assertNull(f.body)
        f.rejection.reject(approved, TranscriptRejectionReason.Incomplete)
        assertEquals(listOf("GET $detailPath", "GET $ticketPath", "GET $detailPath", "POST $rejectPath"), f.calls)
        val body = requireNotNull(f.body)
        assertEquals(setOf("ticket", "expectedRevision", "expectedTextSha256", "reasonCode"), body.keys().asSequence().toSet())
        assertEquals(revision, body.getString("expectedRevision"))
        assertEquals(f.originalHash, body.getString("expectedTextSha256"))
        assertEquals("incomplete", body.getString("reasonCode"))
        assertTrue(runCatching { f.rejection.reject(approved, TranscriptRejectionReason.Duplicate) }.isFailure)
        assertEquals(1, f.calls.count { it.startsWith("POST ") })
    }

    @Test fun changedTextPagesExpiryAndRevocationNeverReachPost() = runBlocking {
        val changed = Fixture()
        val confirmed = changed.rejection.prepare(changed.item, TranscriptPreview(changed.text))
        changed.text = "نص مستجد"
        assertTrue(runCatching { changed.rejection.reject(confirmed, TranscriptRejectionReason.Duplicate) }.isFailure)
        assertNull(changed.body)

        val pages = Fixture()
        val incomplete = TranscriptPreviewItem(id, TranscriptPreviewKind.TextAndPages, 13, 3_600_000)
        assertTrue(runCatching { pages.rejection.prepare(incomplete, TranscriptPreview(pages.text)) }.isFailure)
        assertTrue(pages.calls.isEmpty())

        val expired = Fixture()
        val old = expired.rejection.prepare(expired.item, TranscriptPreview(expired.text))
        expired.now += 300_000
        assertTrue(runCatching { expired.rejection.reject(old, TranscriptRejectionReason.Unspecified) }.isFailure)
        assertNull(expired.body)

        val revoked = Fixture()
        val armed = revoked.rejection.prepare(revoked.item, TranscriptPreview(revoked.text))
        revoked.rejectAllowed = false
        assertTrue(runCatching { revoked.rejection.reject(armed, TranscriptRejectionReason.Inaccurate) }.isFailure)
        assertNull(revoked.body)
    }

    @Test fun malformedTicketOrAmbiguousReceiptRequiresFreshReview() = runBlocking {
        val badTicket = Fixture()
        badTicket.ticketHash = "a".repeat(64)
        assertTrue(runCatching { badTicket.rejection.prepare(badTicket.item, TranscriptPreview(badTicket.text)) }.isFailure)
        assertNull(badTicket.body)

        val badReceipt = Fixture()
        val prepared = badReceipt.rejection.prepare(badReceipt.item, TranscriptPreview(badReceipt.text))
        badReceipt.extraReceiptField = true
        assertTrue(runCatching { badReceipt.rejection.reject(prepared, TranscriptRejectionReason.Inaccurate) }.isFailure)
        assertTrue(runCatching { badReceipt.rejection.reject(prepared, TranscriptRejectionReason.Inaccurate) }.isFailure)
        assertEquals(1, badReceipt.calls.count { it.startsWith("POST ") })
    }

    private inner class Fixture {
        var now = 1_000L
        var text = "نص أصلي"
        val originalHash = transcriptDigest(text)
        var ticketHash = originalHash
        var rejectAllowed = true
        var extraReceiptField = false
        var body: JSONObject? = null
        val calls = mutableListOf<String>()
        val item = TranscriptPreviewItem(id, TranscriptPreviewKind.Text, 13, 3_600_000)
        private val reader = AdminTranscriptPreviewReader({ true }) { path ->
            assertEquals(detailPath, path)
            calls += "GET $path"
            JSONObject().put("id", id).put("kind", "text").put("text", text).put("createdHourMs", 3_600_000)
        }
        val rejection = AdminTranscriptRejection(reader, { rejectAllowed }, { now }) { method, path, sent ->
            calls += "$method $path"
            if (method == "GET") {
                assertEquals(ticketPath, path)
                assertNull(sent)
                JSONObject().put("id", id).put("ticket", "challenge.v1_safe").put("revision", revision)
                    .put("textSha256", ticketHash).put("expiresAtMs", now + 300_000)
            } else {
                assertEquals(rejectPath, path)
                body = sent
                JSONObject().put("ok", true).put("id", id).put("status", "rejected").also {
                    if (extraReceiptField) it.put("future", JSONArray())
                }
            }
        }
    }
}
