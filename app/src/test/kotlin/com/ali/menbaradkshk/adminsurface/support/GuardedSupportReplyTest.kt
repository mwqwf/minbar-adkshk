package com.ali.menbaradkshk.adminsurface.support

import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardedSupportReplyTest {
    private val id="st_1711111111111_aabbcc"
    private val op="a07deb64-0000-4000-8000-000000000001"
    private val t=1_800_000_000_000L
    private val root="/admin/guarded-support-replies/$id"
    private val ticket="${t+120_000}.$op.${"a".repeat(64)}.${"b".repeat(64)}"
    private val thread=SupportInboxThread(id,"idea",false,true,1L,t)
    private val page=SupportInboxPage(listOf(SupportInboxMessage(false,"من المستمع",t,false,false)),false)
    private val message="الرد \"واضح\"\nشكراً"
    private val escaped="""الرد \"واضح\"\nشكراً"""
    private val draft="""{"operationId":"$op","text":"$escaped","expectedLastMessageAtMs":$t,"expectedMessageCount":1}"""
    private val expected=draft.dropLast(1)+""","confirmation":"$ticket"}"""
    private val review="""{"threadId":"$id","operationId":"$op","text":"$escaped","lastMessageAtMs":$t,"messageCount":1,"confirmation":"$ticket"}"""
    private val receipt="""{"threadId":"$id","operationId":"$op","replyStored":true}"""
    private class Wire(vararg output:String) {
        var fail=false
        val planned=output.toMutableList()
        val seen=mutableListOf<Pair<String,String>>()
        suspend fun send(request:LongUploadRequest,guard:()->Unit):LongUploadResponse {
            guard()
            seen+=request.path to request.body!!.decodeToString()
            if(fail){fail=false;throw IOException("synthetic response lost")}
            val bytes=planned.removeAt(0).encodeToByteArray()
            return LongUploadResponse(200,"application/json",bytes.size.toLong(),bytes.inputStream())
        }
    }
    private fun client(w:Wire,active:()->Boolean={true},time:()->Long={t})=
        GuardedSupportReplyClient(active,w::send,time,{op})

    @Test fun exactCanonicalTextAndOneConfirmedOperation()=runBlocking {
        val w=Wire(review,receipt)
        val c=client(w)
        assertTrue(c.available(thread,page))
        val verified=c.review(thread,page,message)
        assertEquals(message,verified.text)
        assertEquals(listOf("$root/review" to draft),w.seen)
        c.confirm(verified)
        assertFalse(c.canConfirm(verified))
        assertTrue(runCatching{c.confirm(verified)}.isFailure)
        assertEquals(listOf("$root/review" to draft,"$root/confirm" to expected),w.seen)
    }

    @Test fun rejectsNoPageOrStaleClosedForeignLeaseAndExpiredReview()=runBlocking {
        var time=t;var enabled=true
        val w=Wire(review,review)
        val c=client(w,{enabled},{time})
        for(t0 in listOf(thread.copy(closed=true),thread.copy(messageCount=500),thread.copy(lastMessageAtMs=t-9))) {
            assertTrue(runCatching{c.review(t0,page,message)}.isFailure)
        }
        assertTrue(runCatching{c.review(thread,SupportInboxPage(emptyList(),false),message)}.isFailure)
        assertTrue(w.seen.isEmpty())
        val check=c.review(thread,page,message)
        assertFalse(client(w).canConfirm(check))
        assertTrue(runCatching{client(w).confirm(check)}.isFailure)
        time+=120_001
        assertTrue(runCatching{c.confirm(check)}.isFailure)
        time=t
        val check2=c.review(thread,page,message)
        enabled=false
        assertTrue(runCatching{c.confirm(check2)}.isFailure)
        assertEquals(2,w.seen.size)
    }

    @Test fun lostAcknowledgementNeverRetriesAndKeysAreExact()=runBlocking {
        val w=Wire(review)
        val c=client(w)
        val check=c.review(thread,page,message)
        w.fail=true
        assertTrue(runCatching{c.confirm(check)}.isFailure)
        assertTrue(runCatching{c.confirm(check)}.isFailure)
        assertEquals(1,w.seen.count{it.first.endsWith("/confirm")})
        for(bad in listOf(review.replace(id,"st_1711111111111_aaaaaa"),
            review.replace("\"messageCount\":1","\"messageCount\":2"),
            review.replace("\"confirmation\":","\"recipient\":\"not allowed\",\"confirmation\":"),
            review.replace(escaped,"تغيّر"))){
            assertTrue(runCatching{client(Wire(bad)).review(thread,page,message)}.isFailure)
        }
    }

    @Test fun rejectsNoncanonicalWrongRouteAndCallerRecipient() {
        val req=LongUploadRequest(LongUploadMethod.POST,"$root/review","application/json",draft.encodeToByteArray(),GUARDED_LESSON_MAX_JSON)
        checkGuardedSupportReplyRequest(req)
        for(bad in listOf(req.copy(path="$root/review?uuid=user"),req.copy(path="/admin/support/threads/$id/reply"),
            req.copy(body=(draft+" ").encodeToByteArray()),
            req.copy(body=(draft.dropLast(1)+""","recipient":"bad"}""").encodeToByteArray()),
            req.copy(path="$root/confirm"),req.copy(method=LongUploadMethod.GET))) {
            assertTrue(runCatching{checkGuardedSupportReplyRequest(bad)}.isFailure)
        }
    }

    @Test fun textNormalizationDoesNotHideUnreviewableControls() {
        assertEquals("أبواب",exactSupportReplyText("  ا\u0654بواب  "))
        assertNull(exactSupportReplyText("hidden\u202E"))
        assertNull(exactSupportReplyText("bad\uD800"))
        assertNull(exactSupportReplyText("ب".repeat(2001)))
        assertEquals("عربى 🪶",exactSupportReplyText("عربى 🪶"))
    }
}
