package com.ali.menbaradkshk.ui.adminbridge.invites

import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.publication.parseFlatObject
import com.ali.menbaradkshk.ui.adminbridge.publication.readGuardedLessonResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

private const val INVITES = "/admin/guarded-invites"
private const val CANCEL = "${INVITES}/cancel"
private const val LIST_LIMIT = 30
private const val TICKET_HORIZON_MS = 135_000
private const val SAFE_INTEGER = 9_007_199_254_740_991L
private val ticketShape = Regex("([0-9]{12,16})\\.[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}\\.[a-f0-9]{64}")
private val exactBody = Regex("""\{"confirmation":"([0-9a-f.-]{1,140})"\}""")

private fun invalid(): Nothing = throw IOException("Invite inventory or confirmation unavailable")

internal fun checkGuardedInviteRequest(request: LongUploadRequest) {
    if (request.followRedirects || request.maxResponseBytes != GUARDED_LESSON_MAX_JSON) invalid()
    when (request.path) {
        INVITES -> if (request.method != LongUploadMethod.GET || request.contentType != null || request.body != null) invalid()
        CANCEL -> {
            if (request.method != LongUploadMethod.POST || request.contentType != "application/json") invalid()
            val bytes = request.body?.takeIf { it.size in 1..224 } ?: invalid()
            val ticket = exactBody.matchEntire(bytes.decodeToString(throwOnInvalidSequence = true))
                ?.groupValues?.get(1) ?: invalid()
            if (!ticketShape.matches(ticket)) invalid()
        }
        else -> invalid()
    }
}

internal class GuardedInviteInventory(
    private val active: () -> Boolean,
    private val transport: LongUploadTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal class OpenInvite internal constructor(
        val email: String, val role: String, val createdAtMs: Long, val expiresAtMs: Long,
        internal val cancelByMs: Long, private val ticket: String, private val scope: Any,
    ) {
        private val available = AtomicBoolean(true)
        internal fun isUsable(holder: Any) = scope === holder && available.get()
        internal fun take(holder: Any): String {
            if (scope !== holder || !available.compareAndSet(true,false)) invalid()
            return ticket
        }
    }

    internal class Inventory internal constructor(val items: List<OpenInvite>, val truncated: Boolean)
    private val mutex = Mutex()
    private val scope = Any()
    @Volatile private var current: Inventory? = null
    fun available() = active()

    fun canCancel(invite: OpenInvite): Boolean = active() && invite.isUsable(scope) && now() < invite.cancelByMs &&
        current?.items?.any { it === invite } == true

    suspend fun list(): Inventory = locked {
        val guard = makeGuard()
        guard()
        current = null
        val bytes = request(LongUploadMethod.GET,INVITES,null,guard)
        val parsed = parseFlatObject(bytes,allowDiscoveryList=true,arrayLimit=LIST_LIMIT)
        if (parsed.keys != setOf("items","truncated")) invalid()
        val truncated = parsed["truncated"] as? Boolean ?: invalid()
        val rows = parsed["items"] as? List<*> ?: invalid()
        if (rows.size > LIST_LIMIT) invalid()
        val present = mutableSetOf<String>()
        val stamp = now()
        val items = rows.map { raw ->
            @Suppress("UNCHECKED_CAST")
            val row = raw as? Map<String,Any> ?: invalid()
            if (row.keys != setOf("email","role","createdAtMs","expiresAtMs","cancelTicket")) invalid()
            val email = row["email"] as? String ?: invalid()
            if (email.length !in 3..254 || email != email.trim().lowercase(Locale.ROOT) ||
                email.count { it == '@' } != 1 || email.startsWith('@') || email.endsWith('@') ||
                email.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) invalid()
            val role = (row["role"] as? String)?.takeIf { it in setOf("admin","supervisor") } ?: invalid()
            val created = (row["createdAtMs"] as? Long)?.takeIf { it in 1..SAFE_INTEGER } ?: invalid()
            val expires = (row["expiresAtMs"] as? Long)?.takeIf { it > created && it <= SAFE_INTEGER } ?: invalid()
            val ticket = row["cancelTicket"] as? String ?: invalid()
            val close = ticketShape.matchEntire(ticket)?.groupValues?.get(1)?.toLongOrNull() ?: invalid()
            if (close !in (created+1)..expires || close-stamp > TICKET_HORIZON_MS || !present.add(ticket)) invalid()
            OpenInvite(email,role,created,expires,close,ticket,scope)
        }
        guard()
        Inventory(items,truncated).also { current = it }
    }

    /** A displayed row remains valid only on its exact loaded snapshot and owner lease. */
    suspend fun cancel(invite: OpenInvite) = locked {
        val guard = makeGuard(invite)
        guard()
        if (!invite.isUsable(scope)) invalid()
        val ticket = invite.take(scope)
        val body = """{"confirmation":"$ticket"}""".encodeToByteArray()
        // Force a freshly fetched page after any outcome; never retry this confirmed cancellation.
        try {
            val answer = parseFlatObject(request(LongUploadMethod.POST,CANCEL,body,guard))
            if (answer.keys != setOf("cancelled") || answer["cancelled"] != true) invalid()
            guard()
        } finally {
            current = null
            body.fill(0)
        }
    }

    private suspend fun makeGuard(invite: OpenInvite? = null): () -> Unit {
        val context = currentCoroutineContext()
        return {
            context.ensureActive()
            if (!active() || invite != null && (now() >= invite.cancelByMs ||
                    current?.items?.any { it === invite } != true)) invalid()
        }
    }

    private suspend fun request(method: LongUploadMethod,path:String,body:ByteArray?,guard:()->Unit): ByteArray {
        val req = LongUploadRequest(method,path,"application/json".takeIf { body != null },body,GUARDED_LESSON_MAX_JSON)
        checkGuardedInviteRequest(req)
        return readGuardedLessonResponse(transport.exchange(req,guard),guard)
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        if (!mutex.tryLock()) invalid()
        try { return block() } finally { mutex.unlock() }
    }
}
