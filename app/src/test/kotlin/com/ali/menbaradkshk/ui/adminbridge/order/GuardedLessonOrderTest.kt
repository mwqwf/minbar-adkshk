package com.ali.menbaradkshk.ui.adminbridge.order

import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LessonDisplayOrder
import com.ali.menbaradkshk.data.orderedSubcategoryLessons
import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuardedLessonOrderTest {
    private val path = "/admin/guarded-lesson-order/cat/sub"
    private val digest = "a".repeat(64)
    private val frame = """{"categoryId":"cat","subcategoryId":"sub","revision":"$digest","items":[{"id":"one","title":"أول"},{"id":"two","title":"ثان"}]}"""
    private val receipt = """{"categoryId":"cat","subcategoryId":"sub","lessonId":"two","direction":"up","version":42,"position":1}"""
    private fun response(s: String): LongUploadResponse {
        val b=s.encodeToByteArray()
        return LongUploadResponse(200,"application/json",b.size.toLong(),b.inputStream())
    }
    private suspend fun fails(f:suspend () -> Unit) { try { f(); fail("expected fail closed") } catch (_: IOException) {} }
    @Test fun exactAdjacentSingleRequestAndNoReplayEvenAfterNewFrame() = runBlocking {
        var posts=0
        val client=GuardedLessonOrderClient({true}, LongUploadTransport { req,guard ->
            guard(); assertEquals(path,req.path); assertFalse(req.followRedirects)
            when(req.method) {
                LongUploadMethod.GET -> response(frame)
                LongUploadMethod.POST -> {
                    posts++
                    assertEquals("""{"revision":"$digest","lessonId":"two","direction":"up"}""",req.body?.decodeToString())
                    response(receipt)
                }
                else -> error("Unexpected method")
            }
        })
        val first=client.review("cat","sub","two")
        assertNull(first.after)
        assertEquals("one",first.before?.id)
        fails { client.confirm(first,GuardedOrderDirection.Down) }
        assertEquals(1,client.confirm(first,GuardedOrderDirection.Up))
        fails { client.confirm(first,GuardedOrderDirection.Up) }
        val next=client.review("cat","sub","two")
        fails { client.confirm(first,GuardedOrderDirection.Up) }
        assertNotSame(first,next)
        assertEquals(1,posts)
    }
    @Test fun leaseLossDiscardLookalikeAndBadResponseCannotSendOrReplay() = runBlocking {
        var active=true
        var posts=0
        val wire=LongUploadTransport { req, guard ->
            guard()
            if (req.method == LongUploadMethod.GET) response(frame) else {
                posts++
                response(receipt.replace("\"position\":1","\"position\":2"))
            }
        }
        val client=GuardedLessonOrderClient({active},wire)
        val foreign=GuardedLessonOrderClient({true},wire).review("cat","sub","two")
        val r=client.review("cat","sub","two")
        fails { client.confirm(foreign,GuardedOrderDirection.Up) }
        active=false
        fails { client.confirm(r,GuardedOrderDirection.Up) }
        active=true
        client.discard()
        fails { client.confirm(r,GuardedOrderDirection.Up) }
        val next=client.review("cat","sub","two")
        fails { client.confirm(next,GuardedOrderDirection.Up) }
        fails { client.confirm(next,GuardedOrderDirection.Up) }
        assertEquals(1,posts)
    }
    @Test fun snapshotDuplicateWrongCategoryAndExtraRouteAreRefused() = runBlocking {
        for (str in listOf(frame.replace("\"two\"","\"one\""),frame.replace("\"cat\"","\"wrong\""),
                           frame.replace("\"title\":\"ثان\"","\"title\":\"ثان\",\"name\":\"alias\""))) {
            val client=GuardedLessonOrderClient({true}, LongUploadTransport { _,_ -> response(str) })
            fails { client.review("cat","sub","two") }
        }
        fails { checkGuardedLessonOrderRequest(LongUploadRequest(LongUploadMethod.GET,path+"?x=1",null,null,GUARDED_LESSON_MAX_JSON)) }
        fails { checkGuardedLessonOrderRequest(LongUploadRequest(LongUploadMethod.GET,"/admin/guarded-lesson-order/cat/..",null,null,GUARDED_LESSON_MAX_JSON)) }
    }
    private fun lesson(id: String, created: Long, mark: LessonDisplayOrder? = null) =
        Lesson(id,"عنوان","cat","sub","",created,displayOrder=mark)
    private fun mark(pos: Int, frame: Long = 42L, count: Int = 2, cat: String = "cat") =
        LessonDisplayOrder(cat,"sub",frame,pos,count)

    @Test fun completeScopedFrameOnlyOtherwiseBaselineChronologyRemains() {
        val ordered=listOf(lesson("first",1,mark(2)),lesson("second",2,mark(1)))
        fun order(rows: List<Lesson>)=orderedSubcategoryLessons(rows,"sub").map(Lesson::id)
        assertEquals(listOf("second","first"),order(ordered))
        for (wrong in listOf(null,mark(1,43),mark(2),mark(1,count=3),mark(1,cat="other"))) {
            assertEquals(listOf("first","second"),order(listOf(ordered[0],lesson("second",2,wrong))))
        }
        assertEquals(listOf("first","second","new"),order(ordered+lesson("new",3)))
        assertEquals(listOf(2L,1L),orderedSubcategoryLessons(ordered,"sub").map(Lesson::createdAtMs))
        assertEquals(listOf(1L,2L),ordered.map(Lesson::createdAtMs))
    }
    @Test fun optionalMarkerSurvivesRealCatalogAndCacheJsonWithoutChangingTimestamp() {
        val checked=lesson("one",7,mark(1))
        assertEquals(checked,Lesson.fromJson(JSONObject(checked.toJson().toString())))
        val row=mapOf("title" to "عنوان","categoryId" to "cat","subcategoryId" to "sub",
                      "createdAt" to 7L, "displayOrder" to mark(1).toJson())
        val catalog=Lesson.fromMap("one",row)
        assertEquals(checked,catalog)
        assertNull(Lesson.fromMap("one",row + ("displayOrder" to mark(1).toJson().put("position","1"))).displayOrder)
        assertNull(Lesson.fromMap("one",row + ("displayOrder" to mark(1).toJson().put("categoryId","outside"))).displayOrder)
    }
}
