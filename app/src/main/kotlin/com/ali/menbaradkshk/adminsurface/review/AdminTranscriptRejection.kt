package com.ali.menbaradkshk.adminsurface.review

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val root = "/admin/transcript-review"
private const val maxTicketTtlMs = 600_000L
private val sha256 = Regex("[0-9a-f]{64}")
private val ticketFormat = Regex("[A-Za-z0-9_.-]{1,256}")

internal fun canRejectPendingTranscriptReview(whoami: JSONObject): Boolean =
    whoami.opt("canRejectPendingTranscriptReview") == true

internal fun isTranscriptRejectionPath(method: String, path: String): Boolean {
    val suffix = when (method) {
        "GET" -> "/rejection-ticket"
        "POST" -> "/reject"
        else -> return false
    }
    return path.endsWith(suffix) && path.removeSuffix(suffix).startsWith("$root/") &&
        isTranscriptPreviewPath(path.removeSuffix(suffix))
}

internal enum class TranscriptRejectionReason(val code: String, val label: String) {
    Unspecified("unspecified", "دون تحديد سبب"),
    Inaccurate("inaccurate", "النص غير دقيق"),
    Incomplete("incomplete", "النص غير مكتمل"),
    Duplicate("duplicate", "مساهمة مكرّرة"),
}

internal class PreparedTranscriptRejection internal constructor(
    internal val item: TranscriptPreviewItem,
    internal val ticket: String,
    internal val revision: String,
    internal val textDigest: String,
    internal val expiresAtMs: Long,
)

internal fun interface TranscriptRejectionWire {
    suspend fun request(method: String, path: String, body: JSONObject?): JSONObject
}

/** Double-confirmed, in-memory rejection scoped to one explicitly displayed pending item and lease. */
internal class AdminTranscriptRejection(
    private val reader: AdminTranscriptPreviewReader,
    private val canReject: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val wire: TranscriptRejectionWire,
) {
    private val armed = AtomicReference<PreparedTranscriptRejection?>(null)

    fun isAvailable(): Boolean = canReject() && reader.isAvailable()

    fun discard() { armed.set(null) }

    suspend fun prepare(item: TranscriptPreviewItem, visible: TranscriptPreview): PreparedTranscriptRejection {
        discard()
        assertCurrent()
        require(item.kind == TranscriptPreviewKind.Text)
        val base = "$root/${item.id}"
        require(isTranscriptPreviewPath(base))
        val digest = transcriptDigest(visible.text)
        check(transcriptDigest(reader.preview(item).text) == digest)
        val reply = checked("GET", "$base/rejection-ticket", null)
        require(reply.matches("id", "ticket", "revision", "textSha256", "expiresAtMs"))
        require(reply.opt("id") == item.id && reply.opt("textSha256") == digest)
        val ticket = (reply.opt("ticket") as? String)?.takeIf(ticketFormat::matches) ?: error("Invalid ticket")
        val revision = (reply.opt("revision") as? String)?.takeIf(sha256::matches) ?: error("Invalid revision")
        val expiry = when (val v = reply.opt("expiresAtMs")) {
            is Long -> v
            is Int -> v.toLong()
            else -> error("Invalid ticket expiration")
        }
        check(validExpiry(expiry))
        return PreparedTranscriptRejection(item, ticket, revision, digest, expiry).also(armed::set)
    }

    suspend fun reject(approved: PreparedTranscriptRejection, reason: TranscriptRejectionReason) {
        assertCurrent()
        check(armed.compareAndSet(approved, null) && validExpiry(approved.expiresAtMs))
        check(transcriptDigest(reader.preview(approved.item).text) == approved.textDigest)
        check(validExpiry(approved.expiresAtMs))
        val body = JSONObject()
            .put("ticket", approved.ticket)
            .put("expectedRevision", approved.revision)
            .put("expectedTextSha256", approved.textDigest)
            .put("reasonCode", reason.code)
        val reply = checked("POST", "$root/${approved.item.id}/reject", body)
        require(reply.matches("ok", "id", "status") &&
            reply.opt("ok") == true && reply.opt("id") == approved.item.id && reply.opt("status") == "rejected")
    }

    private fun validExpiry(expiry: Long): Boolean {
        val now = nowMs()
        return now in 0..(Long.MAX_VALUE - maxTicketTtlMs) && expiry > now && expiry <= now + maxTicketTtlMs
    }

    private suspend fun checked(method: String, path: String, body: JSONObject?): JSONObject {
        assertCurrent()
        require(isTranscriptRejectionPath(method, path))
        return wire.request(method, path, body).also { assertCurrent() }
    }

    private suspend fun assertCurrent() {
        currentCoroutineContext().ensureActive()
        check(isAvailable())
    }
}

private fun JSONObject.matches(vararg keys: String): Boolean =
    length() == keys.size && keys.all(::has)

internal fun transcriptDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
