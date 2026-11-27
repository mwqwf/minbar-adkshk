package com.ali.menbaradkshk.ui.adminbridge.feature

import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.publication.parseFlatObject
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardedLessonFeatureTest {
    private val path = "/admin/guarded-lesson-feature/known"
    private fun row(revision: Int = 10, featured: Boolean = false, name: String = "known", until: Long = 0L): String =
        """{"id":"$name","title":"حديث \"معلوم\"","categoryId":"cat","subcategoryId":"sub","updatedAtMs":$revision,"featured":$featured,"featuredUntilMs":$until}"""
    private fun resp(text: String): LongUploadResponse {
        val b = text.encodeToByteArray()
        return LongUploadResponse(200,"application/json",b.size.toLong(),b.inputStream())
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("unexpected successful guard") } catch (_: IOException) { }
    }
    @Test fun exactlyReviewedTransitionAndNoReplay() = runBlocking {
        var requests = 0
        val client = GuardedLessonFeatureClient({true},LongUploadTransport { req, guard ->
            guard(); assertEquals(path,req.path); assertFalse(req.followRedirects)
            requests++
            if (req.method == LongUploadMethod.GET) resp(row()) else {
                assertEquals(LongUploadMethod.POST,req.method)
                val wire = parseFlatObject(checkNotNull(req.body))
                assertEquals("حديث \"معلوم\"", wire["expectedTitle"])
                assertEquals(10L,wire["expectedUpdatedAtMs"]); assertEquals("sub",wire["expectedSubcategoryId"])
                assertEquals(false,wire["expectedFeatured"]); assertEquals(true,wire["featured"])
                assertFalse(wire.containsKey("featuredUntilMs")) // old release's seven-field request remains exact
                resp(row(11,true))
            }
        })
        val frame = client.review("known")
        assertFalse(frame.featured)
        assertTrue(client.confirm(frame))
        rejects { client.confirm(frame) }
        assertEquals(2,requests)
    }
    @Test fun staleForeignRevokedOrDiscardedReviewCannotSend() = runBlocking {
        var active = true
        var posts = 0
        val wire = LongUploadTransport { req,guard ->
            guard(); if(req.method == LongUploadMethod.POST) posts++
            resp(row())
        }
        val client = GuardedLessonFeatureClient({active},wire)
        val foreign = GuardedLessonFeatureClient({true},wire).review("known")
        val first = client.review("known")
        val next = client.review("known")
        rejects { client.confirm(first) }
        rejects { client.confirm(foreign) }
        active = false
        rejects { client.confirm(next) }
        active = true
        client.discard()
        rejects { client.confirm(next) }
        rejects { client.review("../known") }
        assertEquals(0,posts)
    }
    @Test fun badReceiptFailsClosedAndCannotBeResent() = runBlocking {
        for (response in listOf(row(10,true),row(12,true,"somewhere"),row(11,false),
                                row(11,true).replace("\"featured\":true","\"featured\":true,\"extra\":false"))) {
            var posts = 0
            val client = GuardedLessonFeatureClient({true},LongUploadTransport { req,_ ->
                if(req.method == LongUploadMethod.GET) resp(row()) else { posts++; resp(response) }
            })
            val frame = client.review("known")
            rejects { client.confirm(frame) }
            rejects { client.confirm(frame) }
            assertEquals(1,posts)
        }
    }
    @Test fun malformedTransportResponseAndExtraRouteDeniedBeforeUse() = runBlocking {
        val malformed = GuardedLessonFeatureClient({true},LongUploadTransport { _,_ ->
            resp(row().replace("\"featured\":false","\"featured\":true,\"featured\":false"))
        })
        rejects { malformed.review("known") }
        rejects { checkGuardedLessonFeatureRequest(LongUploadRequest(LongUploadMethod.GET,path+"?foo=true",null,null,GUARDED_LESSON_MAX_JSON)) }
        rejects { checkGuardedLessonFeatureRequest(LongUploadRequest(LongUploadMethod.PUT,path,null,null,GUARDED_LESSON_MAX_JSON)) }
    }

    @Test fun deadlineExactCompareAndSwapAndAcknowledgement() = runBlocking {
        val time = 1_900_000_000_000L
        val old = time + 2000L
        val deadline = time + 4000L
        var posts = 0
        val client = GuardedLessonFeatureClient({ true }, LongUploadTransport { req, guard ->
            guard()
            if (req.method == LongUploadMethod.GET) resp(row(featured = true, until = old)) else {
                posts++
                val body = parseFlatObject(checkNotNull(req.body))
                assertEquals(8, body.keys.size)
                assertEquals(old, body["expectedFeaturedUntilMs"])
                assertEquals(deadline, body["featuredUntilMs"])
                assertEquals(true, body["expectedFeatured"])
                assertEquals(true, body["featured"])
                val oldServerReceipt = posts == 1
                resp(row(11, true, until = if (oldServerReceipt) 0L else deadline))
            }
        }, timedEnabled = true, nowMs = { time })
        assertTrue(client.canSetDeadline())
        val seen = client.review("known")
        rejects { client.confirmDeadline(seen, deadline) } // a mismatching receipt must not be shown as success
        rejects { client.confirmDeadline(seen, deadline) }
        assertTrue(client.confirmDeadline(client.review("known"), deadline))
        assertEquals(2, posts)
    }

    @Test fun expiredNoOpOutOfRangeOrUnavailableDeadlineNeverTransmits() = runBlocking {
        val time = 1_900_000_000_000L
        val old = time + 3000L
        var active = true
        var posts = 0
        val wire = LongUploadTransport { req, guard ->
            guard()
            if (req.method == LongUploadMethod.GET) resp(row(featured = true, until = old)) else {
                posts++
                val body = parseFlatObject(checkNotNull(req.body))
                assertEquals(0L, body["featuredUntilMs"])
                resp(row(11, true))
            }
        }
        val gated = GuardedLessonFeatureClient({ true }, wire, nowMs = { time })
        assertFalse(gated.canSetDeadline())
        rejects { gated.confirmDeadline(gated.review("known"), old + 1) }
        val client = GuardedLessonFeatureClient({ active }, wire, timedEnabled = true, nowMs = { time })
        val seen = client.review("known")
        for (deadline in listOf(-1L, time - 1, time, old, time + 31 * 86_400_000L))
            rejects { client.confirmDeadline(seen, deadline) }
        val current = client.review("known")
        rejects { client.confirmDeadline(seen, old + 1) }
        active = false
        assertFalse(client.canSetDeadline())
        rejects { client.confirmDeadline(current, old + 1) }
        assertEquals(0, posts)
        active = true
        assertTrue(client.confirmDeadline(current, 0L))
        assertEquals(1, posts)
    }

    @Test fun uncheckedMalformedDeadlineCommandCannotBypassTransport() {
        val prefix = """{"expectedTitle":"درس","expectedCategoryId":"a","expectedSubcategoryId":"b","expectedUpdatedAtMs":3,"expectedFeatured":true,"expectedFeaturedUntilMs":5"""
        for (tail in listOf(""","featured":false,"featuredUntilMs":1}""",
                            ""","featured":true,"featuredUntilMs":-1}""",
                            ""","featured":true,"featuredUntilMs":"6"}""",
                            ""","featured":true,"featuredUntilMs":5}""",
                            ""","featured":true,"featuredUntilMs":6,"extra":false}""")) {
            val req = LongUploadRequest(LongUploadMethod.POST, path, "application/json",
                (prefix + tail).encodeToByteArray(), GUARDED_LESSON_MAX_JSON)
            assertThrows(IOException::class.java) { checkGuardedLessonFeatureRequest(req) }
        }
    }
}
