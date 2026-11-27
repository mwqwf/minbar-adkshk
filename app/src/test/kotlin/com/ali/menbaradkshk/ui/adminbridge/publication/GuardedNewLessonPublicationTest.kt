package com.ali.menbaradkshk.ui.adminbridge.publication

import com.ali.menbaradkshk.ui.adminbridge.upload.AdminLongPreparationReceipt
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraft
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardedNewLessonPublicationTest {
    private val id = "cd790800-0000-4000-8000-000000000001"
    private val digest = "9".repeat(64)
    private val actor = guardedNewLessonOwnerScope("owner@example.test")
    private val lesson = sealedLessonIdentity(actor,id)
    private val draft = AdminUploadDraft(id,"صوت.ogg",9_000_000L,digest)
    private val prepared = AdminLongPreparationReceipt(id,draft.byteCount,digest)
    private val local = GuardedNewLessonLocalIntent("درس الفقه","محمد","الجزء الأول\nباب الطهارة","الفقه","الطهارة")
    private val now = 1_800_000_000_000L
    private val ticket = "${now + 300_000}.$id.${"a".repeat(64)}"
    private val reviewJson = """{"draftId":"$id","lessonId":"$lesson","title":"درس الفقه","speaker":"محمد",
        "description":"الجزء الأول\nباب الطهارة","categoryName":"الفقه","sectionName":"الطهارة",
        "decodedDurationMs":12000,"audioSha256":"${"b".repeat(64)}","sizeBytes":50000,"confirmation":"$ticket"}"""
    private val confirmed = """{"draftId":"$id","lessonId":"$lesson","created":true}"""

    private class Wire(val planned: MutableList<String>) {
        var errorNext = false
        var after: () -> Unit = {}
        val requests = mutableListOf<Pair<String,String?>>()
        suspend fun send(request: LongUploadRequest, guard: () -> Unit): LongUploadResponse {
            guard()
            requests += request.path to request.body?.decodeToString()
            if (errorNext) { errorNext = false; throw IOException("synthetic timeout") }
            val bytes = planned.removeAt(0).encodeToByteArray()
            after()
            return LongUploadResponse(200,"application/json; charset=utf-8",bytes.size.toLong(),bytes.inputStream())
        }
    }

    private fun client(w: Wire, allow: () -> Boolean = { true },
                       stamp: () -> Long = { now }, ref: () -> GuardedNewLessonLocalIntent? = { local }) =
        GuardedNewLessonPublicationClient(actor,allow, { ref() },w::send,stamp)

    @Test fun oneShotReviewAndConfirmedPublishUseExactProofAndBodyOnly() = runBlocking {
        val w = Wire(mutableListOf(reviewJson,confirmed))
        val c = client(w)
        assertTrue(c.eligible(draft,prepared))
        assertTrue(runCatching { c.review(draft,prepared.copy(wholeSha256="0".repeat(64))) }.isFailure)
        assertTrue(w.requests.isEmpty())
        val frame = c.review(draft,prepared)
        assertEquals(local.title,frame.details.title)
        assertEquals("b".repeat(64),frame.details.audioSha256)
        assertEquals(50000L,frame.details.sizeBytes)
        assertEquals(listOf("/admin/guarded-new-lessons/$actor/$id/review" to null),w.requests)
        assertEquals(lesson,c.publish(frame).lessonId)
        assertFalse(c.canConfirm(frame))
        assertTrue(runCatching { c.publish(frame) }.isFailure)
        assertEquals("""{"confirmation":"$ticket"}""",w.requests.last().second)
        assertEquals(2,w.requests.size)
    }

    @Test fun staleExpiredForeignOrChangedReviewDoesNotSendMutation() = runBlocking {
        var allow = true
        var clock = now
        var frozen: GuardedNewLessonLocalIntent? = local
        val w = Wire(mutableListOf(reviewJson,reviewJson,reviewJson))
        val c = client(w, { allow }, { clock }, { frozen })
        val first = c.review(draft,prepared)
        val different = client(w)
        assertFalse(different.canConfirm(first))
        assertTrue(runCatching { different.publish(first) }.isFailure)
        clock += 300_000
        assertTrue(runCatching { c.publish(first) }.isFailure)
        clock = now
        val second = c.review(draft,prepared)
        frozen = local.copy(sectionName="آخر")
        assertTrue(runCatching { c.publish(second) }.isFailure)
        frozen = local
        val third = c.review(draft,prepared)
        allow = false
        assertTrue(runCatching { c.publish(third) }.isFailure)
        assertTrue(w.requests.none { it.first.endsWith("/confirm") })
    }

    @Test fun timeoutDoesNotRetryAndRevocationDropsInFlightReply() = runBlocking {
        var allow = true
        val w = Wire(mutableListOf(reviewJson,reviewJson))
        val c = client(w, { allow })
        val frame = c.review(draft,prepared)
        w.errorNext = true
        assertTrue(runCatching { c.publish(frame) }.isFailure)
        assertTrue(runCatching { c.publish(frame) }.isFailure)
        assertEquals(1,w.requests.count { it.first.endsWith("/confirm") })
        w.after = { allow = false }
        assertTrue(runCatching { c.review(draft,prepared) }.isFailure)
    }

    @Test fun rejectsMalformedForeignAndUnboundedServerData() = runBlocking {
        for (bad in listOf(
            reviewJson.replace(lesson,"private_"+"0".repeat(32)),
            reviewJson.replace("50000","46"),
            reviewJson.replace("50000","99614721"),
            reviewJson.replace("12000","0"),
            reviewJson.replace("50000","5e4"),
            reviewJson.replace("دروس",""),
            reviewJson.replace("درس الفقه","درس آخر"),
            reviewJson.replace("\"confirmation\":","\"confirmation\":\"x\",\"confirmation\":"),
            reviewJson.replace("\"speaker\":","\"extra\":\"data\",\"speaker\":"),
        ).filter { it != reviewJson }) {
            val w = Wire(mutableListOf(bad))
            assertTrue(runCatching { client(w).review(draft,prepared) }.isFailure)
            assertEquals(1,w.requests.size)
        }
        assertTrue(runCatching { parseFlatObject("""{"a":"\ud800"}""".encodeToByteArray()) }.isFailure)
        assertTrue(runCatching { parseFlatObject("""{"a":0,"a":0}""".encodeToByteArray()) }.isFailure)
        assertTrue(runCatching { parseFlatObject("""{"a":1}{}""".encodeToByteArray()) }.isFailure)
        assertTrue(runCatching { parseFlatObject(byteArrayOf(0xC0.toByte(),0xAF.toByte())) }.isFailure)
    }

    @Test fun networkPathsAreClosedToOnlyTheTwoSealedPosts() {
        val path="/admin/guarded-new-lessons/$actor/$id"
        val review=LongUploadRequest(LongUploadMethod.POST, "$path/review",null,null,GUARDED_LESSON_MAX_JSON)
        checkGuardedNewLessonRequest(review)
        val confirm=review.copy(path="$path/confirm",contentType="application/json",
            body="""{"confirmation":"$ticket"}""".encodeToByteArray())
        checkGuardedNewLessonRequest(confirm)
        for (bad in listOf(
            review.copy(path="$path/review?skip=1"), review.copy(followRedirects=true),
            review.copy(method=LongUploadMethod.GET),
            review.copy(body=byteArrayOf(32)), confirm.copy(body="{}".encodeToByteArray()),
            confirm.copy(body="""{"confirmation":"$ticket","other":1}""".encodeToByteArray()),
        )) assertTrue(runCatching { checkGuardedNewLessonRequest(bad) }.isFailure)
    }
}
