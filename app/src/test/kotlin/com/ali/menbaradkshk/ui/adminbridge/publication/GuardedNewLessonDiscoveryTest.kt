package com.ali.menbaradkshk.ui.adminbridge.publication

import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardedNewLessonDiscoveryTest {
    private val id = "a07deb64-0000-4000-8000-000000000001"
    private val id2 = "a07deb64-0000-4000-8000-000000000002"
    private val title = "شرح الفقه"
    private val lesson = "private_" + "a".repeat(32)
    private val stamp = 1_800_000_000_000L
    private val ticket = "${stamp+300_000}.$id.${"b".repeat(64)}"
    private fun row(draft:String = id, label:String = title) = """{"draftId":"$draft","title":"$label"}"""
    private fun list(items:String = row(), truncated:Boolean = false) =
        """{"state":"encoded_review_required","items":[$items],"truncated":$truncated}"""
    private fun frame(draft:String=id, label:String=title) =
        """{"draftId":"$draft","lessonId":"$lesson","title":"$label","speaker":"","description":"",
        "categoryName":"قسم","sectionName":"فرع","decodedDurationMs":47,"audioSha256":"${"c".repeat(64)}",
        "sizeBytes":47,"confirmation":"$ticket"}"""
    private val done="""{"draftId":"$id","lessonId":"$lesson","created":true}"""

    private class Wire(vararg reply:String) {
        val replies=reply.toMutableList()
        val sent=mutableListOf<Triple<LongUploadMethod,String,String?>>()
        var fail=false
        var after: ()->Unit = {}
        suspend fun exchange(request:LongUploadRequest,guard:()->Unit): LongUploadResponse {
            guard()
            sent += Triple(request.method,request.path,request.body?.decodeToString())
            if (fail) { fail=false; throw IOException("synthetic outage") }
            val bytes=replies.removeAt(0).encodeToByteArray()
            after()
            return LongUploadResponse(200,"application/json",bytes.size.toLong(),bytes.inputStream())
        }
    }
    private fun client(w: Wire,active:()->Boolean={true},time:()->Long={stamp}) =
        GuardedNewLessonDiscoveryClient(active,w::exchange,time)

    @Test fun roundtripListAndOpaqueDraftReviewAndOnceOnlyConfirmation() = runBlocking {
        val w=Wire(list(truncated=true),frame(),done)
        val c=client(w)
        val page=c.list()
        assertTrue(page.truncated)
        assertEquals(title,page.rows.single().title)
        val review=c.review(page.rows.single())
        assertEquals(lesson,review.details.lessonId)
        assertEquals(47L,review.details.sizeBytes)
        assertTrue(c.canConfirm(review))
        assertEquals(lesson,c.publish(review).lessonId)
        assertFalse(c.canConfirm(review))
        assertTrue(runCatching { c.publish(review) }.isFailure)
        val root="/admin/guarded-new-lessons"
        assertEquals(listOf(Triple(LongUploadMethod.GET,"$root/candidates",null),
            Triple(LongUploadMethod.POST,"$root/by-draft/$id/review",null),
            Triple(LongUploadMethod.POST,"$root/by-draft/$id/confirm","""{"confirmation":"$ticket"}""")),w.sent)
    }

    @Test fun deniesUnknownRowsChangedTitlePoisonedIdsOrExtraActor() = runBlocking {
        val many=(1..21).joinToString(",") { i ->
            row("a07deb64-0000-4000-8000-"+i.toString().padStart(12,'0'))
        }
        for (bad in listOf(list(many),list(row()+","+row()),list(row("manual")), list("""{"draftId":"$id","title":"$title","actor":"never"}"""),
            """{"state":"ready","items":[],"truncated":false}""",
            """{"state":"encoded_review_required","items":[],"items":[],"truncated":false}""",
            """{"state":"encoded_review_required","items":[],"truncated":"false"}""",
            """{"state":"encoded_review_required","items":[{"draftId":"$id","title":[]}],"truncated":false}""")) {
            assertTrue(runCatching { client(Wire(bad)).list() }.isFailure)
        }
        for (bad in listOf(frame(id2),frame(label="عنوان آخر"))) {
            val c=client(Wire(list(),bad))
            assertTrue(runCatching { c.review(c.list().rows.single()) }.isFailure)
        }
        val good=client(Wire(list(""),list()))
        assertTrue(good.list().rows.isEmpty())
        assertEquals(1,good.list().rows.size)
    }

    @Test fun forbidsRequestsFromInactiveOrUnlistedCandidateAndDropsOldSnapshot() = runBlocking {
        val w=Wire(list(),frame(),list(row(id2)))
        var active=false
        val c=client(w,{active})
        assertTrue(runCatching { c.list() }.isFailure)
        assertTrue(w.sent.isEmpty())
        active=true
        val listed=c.list().rows.single()
        val manual=GuardedNewLessonDiscoveryClient.Candidate(id,title)
        assertTrue(runCatching { c.review(manual) }.isFailure)
        val reviewed=c.review(listed)
        c.list()
        assertFalse(c.canConfirm(reviewed))
        assertTrue(runCatching { c.publish(reviewed) }.isFailure)
        assertTrue(w.sent.none { it.second.endsWith("/confirm") })
    }

    @Test fun timeoutConsumesTicketAndNoAutomaticMutations() = runBlocking {
        val w=Wire(list(),frame())
        val c=client(w)
        val review=c.review(c.list().rows.single())
        w.fail=true
        assertTrue(runCatching { c.publish(review) }.isFailure)
        assertTrue(runCatching { c.publish(review) }.isFailure)
        assertEquals(1,w.sent.count { it.second.endsWith("/confirm") })
    }

    @Test fun noGenericActorEndpointCrossUse() {
        val req=LongUploadRequest(LongUploadMethod.GET,"/admin/guarded-new-lessons/candidates",null,null,
            GUARDED_LESSON_MAX_JSON)
        checkGuardedDiscoveryRequest(req)
        assertTrue(runCatching { checkGuardedNewLessonRequest(req) }.isFailure)
        for (bad in listOf(req.copy(path=req.path+"?n=20"),
            req.copy(path="/admin/guarded-new-lessons/"+"a".repeat(64)+"/$id/review",method=LongUploadMethod.POST),
            req.copy(path="/admin/guarded-new-lessons/by-draft/$id/review",method=LongUploadMethod.GET))) {
            assertTrue(runCatching { checkGuardedDiscoveryRequest(bad) }.isFailure)
        }
    }
}
