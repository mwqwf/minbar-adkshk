package com.ali.menbaradkshk.adminsurface.review

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val root = "/admin/transcript-review"
private const val maxTicketTtlMs = 600_000L
private val sha256 = Regex("[0-9a-f]{64}")
private val ticketFormat = Regex("[A-Za-z0-9_.-]{1,256}")

internal fun canAcceptPendingTranscriptReview(whoami: JSONObject): Boolean =
    whoami.opt("canAcceptPendingTranscriptReview") == true

internal fun isTranscriptAcceptancePath(method: String, path: String): Boolean {
    val suffix = when (method) {
        "GET" -> "/acceptance-ticket"
        "POST" -> "/accept"
        else -> return false
    }
    return path.endsWith(suffix) && path.removeSuffix(suffix).startsWith("$root/") &&
        isTranscriptPreviewPath(path.removeSuffix(suffix))
}

internal class PreparedTranscriptAcceptance internal constructor(
    internal val item: TranscriptPreviewItem,
    internal val ticket: String,
    internal val revision: String,
    internal val textDigest: String,
    internal val expiresAtMs: Long,
)

internal fun interface TranscriptAcceptanceWire {
    suspend fun request(method: String, path: String, body: JSONObject?): JSONObject
}

/** Separate one-shot authority for approving the exact currently displayed text, never images or edits. */
internal class AdminTranscriptAcceptance(
    private val reader: AdminTranscriptPreviewReader,
    private val canAccept: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val wire: TranscriptAcceptanceWire,
) {
    private val armed = AtomicReference<PreparedTranscriptAcceptance?>(null)

    fun isAvailable(): Boolean = canAccept() && reader.isAvailable()
    fun discard() { armed.set(null) }

    suspend fun prepare(item: TranscriptPreviewItem, visible: TranscriptPreview): PreparedTranscriptAcceptance {
        discard()
        assertCurrent()
        require(item.kind == TranscriptPreviewKind.Text)
        val base = "$root/${item.id}"
        require(isTranscriptPreviewPath(base))
        val digest = transcriptDigest(visible.text)
        check(transcriptDigest(reader.preview(item).text) == digest)
        val reply = checked("GET", "$base/acceptance-ticket", null)
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
        return PreparedTranscriptAcceptance(item, ticket, revision, digest, expiry).also(armed::set)
    }

    suspend fun accept(approved: PreparedTranscriptAcceptance) {
        assertCurrent()
        check(armed.compareAndSet(approved, null) && validExpiry(approved.expiresAtMs))
        check(transcriptDigest(reader.preview(approved.item).text) == approved.textDigest)
        check(validExpiry(approved.expiresAtMs))
        val body = JSONObject()
            .put("ticket", approved.ticket)
            .put("expectedRevision", approved.revision)
            .put("expectedTextSha256", approved.textDigest)
        val reply = checked("POST", "$root/${approved.item.id}/accept", body)
        require(reply.matches("ok", "id", "status") &&
            reply.opt("ok") == true && reply.opt("id") == approved.item.id && reply.opt("status") == "approved")
    }

    private fun validExpiry(expiry: Long): Boolean {
        val now = nowMs()
        return now in 0..(Long.MAX_VALUE - maxTicketTtlMs) && expiry > now && expiry <= now + maxTicketTtlMs
    }

    private suspend fun checked(method: String, path: String, body: JSONObject?): JSONObject {
        assertCurrent()
        require(isTranscriptAcceptancePath(method, path))
        return wire.request(method, path, body).also { assertCurrent() }
    }

    private suspend fun assertCurrent() {
        currentCoroutineContext().ensureActive()
        check(isAvailable())
    }
}

private fun JSONObject.matches(vararg keys: String): Boolean =
    length() == keys.size && keys.all(::has)
