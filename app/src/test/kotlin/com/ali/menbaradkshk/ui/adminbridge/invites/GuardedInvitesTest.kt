package com.ali.menbaradkshk.ui.adminbridge.invites

import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardedInvitesTest {
    private val time=1_800_000_000_000L
    private val ticket="${time+120_000}.a07deb64-0000-4000-8000-000000000001.${"a".repeat(64)}"
    private fun row(email:String="new@example.test",role:String="supervisor",proof:String=ticket)=
        """{"email":"$email","role":"$role","createdAtMs":${time-15},"expiresAtMs":${time+500_000},"cancelTicket":"$proof"}"""
    private fun list(items:String=row())="""{"items":[$items],"truncated":false}"""
    private val okay="""{"cancelled":true}"""
    private class Wire(vararg reply:String) {
        var fail=false
        var afterward:()->Unit={}
        val pending=reply.toMutableList()
        val requests=mutableListOf<Triple<LongUploadMethod,String,String?>>()
        suspend fun send(request:LongUploadRequest,guard:()->Unit):LongUploadResponse {
            guard()
            requests+=Triple(request.method,request.path,request.body?.decodeToString())
            if (fail) { fail=false; throw IOException("mock refused") }
            val bytes=pending.removeAt(0).encodeToByteArray()
            afterward()
            return LongUploadResponse(200,"application/json",bytes.size.toLong(),bytes.inputStream())
        }
    }
    private fun client(w:Wire,allowed:()->Boolean={true},now:()->Long={time})=GuardedInviteInventory(allowed,w::send,now)

    @Test fun exactOpenInvitationMetadataAndOnceOnlyCancellation() = runBlocking {
        val w=Wire(list(),okay)
        val c=client(w)
        val item=c.list().items.single()
        assertEquals("new@example.test",item.email)
        assertEquals("supervisor",item.role)
        assertTrue(c.canCancel(item))
        c.cancel(item)
        assertFalse(c.canCancel(item))
        assertTrue(runCatching { c.cancel(item) }.isFailure)
        assertEquals(listOf(Triple(LongUploadMethod.GET,"/admin/guarded-invites",null),
            Triple(LongUploadMethod.POST,"/admin/guarded-invites/cancel","""{"confirmation":"$ticket"}""")),w.requests)
    }

    @Test fun closedLeaseDifferentOwnerInstanceExpiredAndStaleSnapshotCannotCancel() = runBlocking {
        var allow=false
        var now=time
        val w=Wire(list(),list())
        val c=client(w,{allow},{now})
        assertTrue(runCatching { c.list() }.isFailure)
        assertTrue(w.requests.isEmpty())
        allow=true
        val item=c.list().items.single()
        assertTrue(runCatching { client(w).cancel(item) }.isFailure)
        now+=120_001
        assertFalse(c.canCancel(item))
        assertTrue(runCatching { c.cancel(item) }.isFailure)
        now=time
        val sameMetadata=c.list().items.single()
        assertFalse(c.canCancel(item))
        assertTrue(c.canCancel(sameMetadata))
        allow=false
        assertTrue(runCatching { c.cancel(sameMetadata) }.isFailure)
        assertTrue(w.requests.none { it.first==LongUploadMethod.POST })
    }

    @Test fun timeoutAndMalformedReceiptConsumeSnapshotNoRetry() = runBlocking {
        val w=Wire(list(),list(),"""{"cancelled":true,"email":"unexpected"}""")
        val c=client(w)
        val first=c.list().items.single()
        w.fail=true
        assertTrue(runCatching { c.cancel(first) }.isFailure)
        assertTrue(runCatching { c.cancel(first) }.isFailure)
        assertEquals(1,w.requests.count { it.first==LongUploadMethod.POST })
        val next=c.list().items.single()
        assertTrue(runCatching { c.cancel(next) }.isFailure)
        assertTrue(runCatching { c.cancel(next) }.isFailure)
        assertEquals(2,w.requests.count { it.first==LongUploadMethod.POST })
    }

    @Test fun deniesBadMetadataRawKeysBadClockAndExcessRows() = runBlocking {
        val big=(1..31).joinToString(",") { i -> row("inviter$i@example.test",proof=ticket.replace("000000000001",i.toString().padStart(12,'0'))) }
        for (bad in listOf(list(big),list(row()+","+row()),list(row(role="owner")),list(row(email=" upper@example.test")),
            list(row(proof=ticket.replace((time+120_000).toString(),(time+140_000).toString()))),
            list(row().replace("\"cancelTicket\":","\"code\":\"ACTUALCODE\",\"cancelTicket\":")),
            list().replace("\"truncated\":false","\"truncated\":\"false\""))) {
            assertTrue(runCatching { client(Wire(bad)).list() }.isFailure)
        }
    }

    @Test fun pathsDoNotAdmitLegacyIssuanceLookupOrEmail() {
        val allowed=LongUploadRequest(LongUploadMethod.GET,"/admin/guarded-invites",null,null,GUARDED_LESSON_MAX_JSON)
        checkGuardedInviteRequest(allowed)
        for (bad in listOf(allowed.copy(path="/admin/invites"),allowed.copy(path=allowed.path+"?email=me"),
            allowed.copy(path="/admin/guarded-invites/cancel",method=LongUploadMethod.POST,
                contentType="application/json",body="""{"email":"request@example.test"}""".encodeToByteArray()),
            allowed.copy(followRedirects=true))) assertTrue(runCatching { checkGuardedInviteRequest(bad) }.isFailure)
    }
}
