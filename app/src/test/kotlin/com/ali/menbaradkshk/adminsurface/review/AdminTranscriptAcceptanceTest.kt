package com.ali.menbaradkshk.adminsurface.review

import android.app.Application
import com.ali.menbaradkshk.ui.adminbridge.decodeAdminGrant
import com.ali.menbaradkshk.ui.adminbridge.openVerifiedSession
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
class AdminTranscriptAcceptanceTest {
    private val root = "/admin/transcript-review"
    private val id = "tsub_0123456789abcdef0123"
    private val detailPath = "$root/$id"
    private val ticketPath = "$detailPath/acceptance-ticket"
    private val acceptPath = "$detailPath/accept"
    private val revision = "b".repeat(64)

    @Test fun onlyLiteralGrantAndExactMethodPathsAreAccepted() = runBlocking {
        for (value in listOf("true", 1, false, JSONObject.NULL)) {
            assertFalse(canAcceptPendingTranscriptReview(JSONObject().put("canAcceptPendingTranscriptReview", value)))
        }
        assertTrue(canAcceptPendingTranscriptReview(JSONObject().put("canAcceptPendingTranscriptReview", true)))
        assertTrue(isTranscriptAcceptancePath("GET", ticketPath))
        assertTrue(isTranscriptAcceptancePath("POST", acceptPath))
        assertFalse(isTranscriptAcceptancePath("POST", ticketPath))
        assertFalse(isTranscriptAcceptancePath("GET", "$ticketPath?status=all"))
        assertFalse(isTranscriptAcceptancePath("POST", "$detailPath/%2e%2e/accept"))
        val f = Fixture()
        f.acceptAllowed = false
        assertTrue(runCatching { f.acceptance.prepare(f.item, TranscriptPreview(f.text)) }.isFailure)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun twoExplicitStepsReopenSameTextAndPostOnceWithoutReasonPayload() = runBlocking {
        val f = Fixture()
        val approved = f.acceptance.prepare(f.item, TranscriptPreview(f.text))
        assertEquals(listOf("GET $detailPath", "GET $ticketPath"), f.calls)
        assertNull(f.body)
        f.acceptance.accept(approved)
        assertEquals(listOf("GET $detailPath", "GET $ticketPath", "GET $detailPath", "POST $acceptPath"), f.calls)
        val body = requireNotNull(f.body)
        assertEquals(setOf("ticket", "expectedRevision", "expectedTextSha256"), body.keys().asSequence().toSet())
        assertEquals(revision, body.getString("expectedRevision"))
        assertEquals(f.originalHash, body.getString("expectedTextSha256"))
        assertTrue(runCatching { f.acceptance.accept(approved) }.isFailure)
        assertEquals(1, f.calls.count { it.startsWith("POST ") })
    }

    @Test fun changedTextPagesExpiryAndRevocationNeverReachPost() = runBlocking {
        val changed = Fixture()
        val confirmed = changed.acceptance.prepare(changed.item, TranscriptPreview(changed.text))
        changed.text = "نص مستجد"
        assertTrue(runCatching { changed.acceptance.accept(confirmed) }.isFailure)
        assertNull(changed.body)

        val pages = Fixture()
        val incomplete = TranscriptPreviewItem(id, TranscriptPreviewKind.TextAndPages, 13, 3_600_000)
        assertTrue(runCatching { pages.acceptance.prepare(incomplete, TranscriptPreview(pages.text)) }.isFailure)
        assertTrue(pages.calls.isEmpty())

        val expired = Fixture()
        val old = expired.acceptance.prepare(expired.item, TranscriptPreview(expired.text))
        expired.now += 300_000
        assertTrue(runCatching { expired.acceptance.accept(old) }.isFailure)
        assertNull(expired.body)

        val revoked = Fixture()
        val armed = revoked.acceptance.prepare(revoked.item, TranscriptPreview(revoked.text))
        revoked.acceptAllowed = false
        assertTrue(runCatching { revoked.acceptance.accept(armed) }.isFailure)
        assertNull(revoked.body)
    }

    @Test fun malformedTicketOrAmbiguousReceiptRequiresFreshReview() = runBlocking {
        val badTicket = Fixture()
        badTicket.ticketHash = "a".repeat(64)
        assertTrue(runCatching { badTicket.acceptance.prepare(badTicket.item, TranscriptPreview(badTicket.text)) }.isFailure)
        assertNull(badTicket.body)

        val badReceipt = Fixture()
        val prepared = badReceipt.acceptance.prepare(badReceipt.item, TranscriptPreview(badReceipt.text))
        badReceipt.extraReceiptField = true
        assertTrue(runCatching { badReceipt.acceptance.accept(prepared) }.isFailure)
        assertTrue(runCatching { badReceipt.acceptance.accept(prepared) }.isFailure)
        assertEquals(1, badReceipt.calls.count { it.startsWith("POST ") })
    }

    @Test fun previewAcceptanceAndRejectionCannotSubstituteOneAnother() {
        fun session(vararg flags: String) = requireNotNull(decodeAdminGrant(
            JSONObject().put("who", "admin@example.invalid").put("role", "supervisor").also {
                for (flag in flags) it.put(flag, true)
            },
        )).openVerifiedSession("ms_" + "a".repeat(64)) { error("No network expected") }

        val read = "canReadPendingTranscriptReview"
        val accept = "canAcceptPendingTranscriptReview"
        val reject = "canRejectPendingTranscriptReview"
        assertNull(session(accept).transcriptPreviewReader())
        val reading = session(read)
        assertNull(reading.transcriptAcceptance(requireNotNull(reading.transcriptPreviewReader())))
        val accepting = session(read, accept)
        val acceptedReader = requireNotNull(accepting.transcriptPreviewReader())
        assertNotNull(accepting.transcriptAcceptance(acceptedReader))
        assertNull(accepting.transcriptRejection(acceptedReader))
        val rejecting = session(read, reject)
        assertNull(rejecting.transcriptAcceptance(requireNotNull(rejecting.transcriptPreviewReader())))
        accepting.close()
        assertNull(accepting.transcriptAcceptance(acceptedReader))
    }

    private inner class Fixture {
        var now = 1_000L
        var text = "نص أصلي"
        val originalHash = transcriptDigest(text)
        var ticketHash = originalHash
        var acceptAllowed = true
        var extraReceiptField = false
        var body: JSONObject? = null
        val calls = mutableListOf<String>()
        val item = TranscriptPreviewItem(id, TranscriptPreviewKind.Text, 13, 3_600_000)
        private val reader = AdminTranscriptPreviewReader({ true }) { path ->
            assertEquals(detailPath, path)
            calls += "GET $path"
            JSONObject().put("id", id).put("kind", "text").put("text", text).put("createdHourMs", 3_600_000)
        }
        val acceptance = AdminTranscriptAcceptance(reader, { acceptAllowed }, { now }) { method, path, sent ->
            calls += "$method $path"
            if (method == "GET") {
                assertEquals(ticketPath, path)
                assertNull(sent)
                JSONObject().put("id", id).put("ticket", "challenge.v1_safe").put("revision", revision)
                    .put("textSha256", ticketHash).put("expiresAtMs", now + 300_000)
            } else {
                assertEquals(acceptPath, path)
                body = sent
                JSONObject().put("ok", true).put("id", id).put("status", "approved").also {
                    if (extraReceiptField) it.put("future", JSONArray())
                }
            }
        }
    }
}
