package com.ali.menbaradkshk.adminsurface.support

import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.publication.parseFlatObject
import com.ali.menbaradkshk.ui.adminbridge.publication.readGuardedLessonResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.io.IOException
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

private const val ROOT = "/admin/guarded-support-replies"
private const val UUID4 = "[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"
private const val THREAD = "st_(?:[a-f0-9]{20}|[0-9]{10,16}_[a-f0-9]{6})"
private val threadId = Regex(THREAD)
private val operation = Regex(UUID4)
private val path = Regex("$ROOT/$THREAD/(review|confirm)")
private val ticketShape = Regex("([0-9]{12,16})\\.$UUID4\\.[a-f0-9]{64}\\.[a-f0-9]{64}")
private val fields = setOf("operationId","text","expectedLastMessageAtMs","expectedMessageCount")
private fun fail(): Nothing = throw IOException("Support reply unavailable")

internal fun exactSupportReplyText(input: String): String? {
    val value=Normalizer.normalize(input.trim(),Normalizer.Form.NFC)
    if (value.isEmpty() || value.length>2000) return null
    var i=0
    while (i < value.length) {
        val ch=value[i++]
        val cp=if (ch.isHighSurrogate()) {
            val next=value.getOrNull(i++)?.takeIf(Char::isLowSurrogate) ?: return null
            Character.toCodePoint(ch,next)
        } else if (ch.isLowSurrogate()) return null else ch.code
        if (cp != 10 && (Character.isISOControl(cp) || Character.getType(cp)==Character.FORMAT.toInt())) return null
    }
    return value
}
private fun jsonText(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '\n' -> append("\\n")
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        else -> append(c)
    }
    append('"')
}

internal fun matchesLoadedSupportThread(thread: SupportInboxThread, page: SupportInboxPage<SupportInboxMessage>): Boolean =
    !thread.closed && threadId.matches(thread.id) && thread.lastMessageAtMs in 1..9_007_199_254_740_991L &&
        thread.messageCount in 1..499 && page.items.size.toLong() == minOf(100,thread.messageCount) &&
        page.truncated == (thread.messageCount > 100) &&
        page.items.maxOfOrNull { it.createdAtMs } == thread.lastMessageAtMs

internal fun checkGuardedSupportReplyRequest(req: LongUploadRequest) {
    val match=path.matchEntire(req.path) ?: fail()
    val review=match.groupValues.last()=="review"
    val bytes=req.body?.takeIf { it.size in 1..12288 } ?: fail()
    if(req.method!=LongUploadMethod.POST || req.contentType!="application/json" || req.followRedirects ||
        req.maxResponseBytes!=GUARDED_LESSON_MAX_JSON) fail()
    val m=parseFlatObject(bytes)
    if(m.keys!=(if(review) fields else fields+"confirmation")) fail()
    val op=(m["operationId"] as? String)?.takeIf(operation::matches) ?: fail()
    val text=(m["text"] as? String)?.takeIf { exactSupportReplyText(it)==it } ?: fail()
    val revision=(m["expectedLastMessageAtMs"] as? Long)?.takeIf { it in 1..9_007_199_254_740_991L } ?: fail()
    val count=(m["expectedMessageCount"] as? Long)?.takeIf { it in 1..499 } ?: fail()
    val ticket=if(review)null else (m["confirmation"] as? String)?.takeIf(ticketShape::matches) ?: fail()
    if(!bytes.contentEquals(body(op,text,revision,count,ticket))) fail()
}
private fun body(op:String,text:String,revision:Long,count:Long,ticket:String?)=buildString {
    append("""{"operationId":"$op","text":""")
    append(jsonText(text))
    append(""","expectedLastMessageAtMs":$revision,"expectedMessageCount":$count""")
    if(ticket!=null) append(""","confirmation":"$ticket"""")
    append('}')
}.encodeToByteArray()

internal class GuardedSupportReplyClient(
    private val active: ()->Boolean,
    private val transport: LongUploadTransport,
    private val now: ()->Long=System::currentTimeMillis,
    private val operationId: ()->String={ UUID.randomUUID().toString() },
) {
    private val engine=Any()
    private val mutex=Mutex()
    internal class Review internal constructor(
        val thread: SupportInboxThread, val text: String, val expiresAtMs: Long,
        private val op:String, private val ticket:String, private val engine:Any,
    ) {
        private val unused=AtomicBoolean(true)
        internal fun fresh(owner:Any)=owner===engine&&unused.get()
        internal fun take(owner:Any): Pair<String,String> {
            if(owner!==engine||!unused.compareAndSet(true,false)) fail()
            return op to ticket
        }
    }

    fun available(thread:SupportInboxThread,page:SupportInboxPage<SupportInboxMessage>) =
        active()&&matchesLoadedSupportThread(thread,page)

    fun canConfirm(review:Review)=active()&&review.fresh(engine)&&now()<review.expiresAtMs

    suspend fun review(thread:SupportInboxThread,page:SupportInboxPage<SupportInboxMessage>,text:String):Review = locked {
        val guard=guard()
        guard()
        if(!available(thread,page)||exactSupportReplyText(text)!=text)fail()
        val op=operationId().takeIf(operation::matches) ?: fail()
        val response=call(thread,"review",op,text,null,guard)
        if(response.keys!=setOf("threadId","operationId","text","lastMessageAtMs","messageCount","confirmation")||
            response["threadId"]!=thread.id||response["operationId"]!=op||response["text"]!=text||
            response["lastMessageAtMs"]!=thread.lastMessageAtMs||response["messageCount"]!=thread.messageCount)fail()
        val ticket=response["confirmation"] as? String ?: fail()
        val expires=ticketShape.matchEntire(ticket)?.groupValues?.get(1)?.toLongOrNull()?:fail()
        val at=now()
        if(expires<=at||expires-at>135_000L)fail()
        guard()
        Review(thread,text,expires,op,ticket,engine)
    }

    suspend fun confirm(review:Review) = locked {
        val guard=guard(review.expiresAtMs)
        guard()
        val (op,ticket)=review.take(engine)
        val response=call(review.thread,"confirm",op,review.text,ticket,guard)
        if(response.keys!=setOf("threadId","operationId","replyStored")||response["threadId"]!=review.thread.id||
            response["operationId"]!=op||response["replyStored"]!=true)fail()
        guard()
    }

    private suspend fun call(t:SupportInboxThread, mode:String, op:String,text:String,ticket:String?,guard:()->Unit):Map<String,Any>{
        val payload=body(op,text,t.lastMessageAtMs,t.messageCount,ticket)
        val req=LongUploadRequest(LongUploadMethod.POST,"$ROOT/${t.id}/$mode","application/json",payload,GUARDED_LESSON_MAX_JSON)
        return try {
            checkGuardedSupportReplyRequest(req)
            parseFlatObject(readGuardedLessonResponse(transport.exchange(req,guard),guard))
        }finally { payload.fill(0) }
    }
    private suspend fun guard(expiry:Long=Long.MAX_VALUE):()->Unit {
        val context=currentCoroutineContext()
        return { context.ensureActive(); if(!active()||now()>=expiry)fail() }
    }
    private suspend fun <T> locked(block:suspend()->T):T {
        if(!mutex.tryLock())fail()
        try{return block()}finally{mutex.unlock()}
    }
}
