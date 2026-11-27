package com.ali.menbaradkshk.adminsurface.review

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val root = "/admin/transcript-publication-candidates"
private const val maxTicketTtlMs = 360_000L
private val candidateId = Regex("tsub_(?:[0-9a-f]{20}|[0-9]{10,16}_[0-9a-f]{6})")
private val lessonId = Regex("[A-Za-z0-9_-]{1,100}")
private val sha256 = Regex("[0-9a-f]{64}")
private val ticketFormat = Regex("tp1\\.[0-9]{13}\\.[0-9a-f]{32}\\.[0-9a-f]{64}")
internal fun isTranscriptPublicationPath(method: String, path: String): Boolean {
    if (method == "GET" && path == root) return true
    if (!path.startsWith("$root/")) return false
    val parts = path.removePrefix("$root/").split('/')
    if (!candidateId.matches(parts.first())) return false
    return when (method) {
        "GET" -> parts.size == 1 || parts.size == 2 && parts[1] == "publication-ticket"
        "POST" -> parts.size == 2 && parts[1] == "publish"
        else -> false
    }
}

internal data class TranscriptPublicationItem(
    val id: String, val lesson: String, val title: String, val textChars: Int, val acceptedAtMs: Long,
)
internal class TranscriptPublicationQueue(val items: List<TranscriptPublicationItem>, val truncated: Boolean)
internal class TranscriptPublicationDetail(val item: TranscriptPublicationItem, val text: String, val digest: String)
internal class PreparedTranscriptPublication internal constructor(
    val visible: TranscriptPublicationDetail, internal val ticket: String, internal val revision: String,
    internal val expiry: Long,
)

internal fun interface TranscriptPublicationWire {
    suspend fun request(method: String, path: String, body: JSONObject?): JSONObject
}

/** Separate owner publication action. Never adopt the acceptance response as publication proof. */
internal class AdminTranscriptPublication(
    private val permitted: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val wire: TranscriptPublicationWire,
) {
    private val armed = AtomicReference<PreparedTranscriptPublication?>(null)
    private val generation = AtomicLong()
    fun available(): Boolean = permitted()
    fun discard() { generation.incrementAndGet(); armed.set(null) }

    suspend fun queue(): TranscriptPublicationQueue {
        val reply = checked("GET", root).strict("items", "truncated")
        val items = requireNotNull(reply.optJSONArray("items"))
        require(items.length() <= 20)
        val seen = mutableSetOf<String>()
        return TranscriptPublicationQueue(List(items.length()) { i ->
            val obj = requireNotNull(items.optJSONObject(i)).strict("id", "lessonId", "lessonTitle", "textChars", "acceptedAtMs")
            val chars = obj.number("textChars")
            require(chars in 1..20_000)
            TranscriptPublicationItem(
                id = obj.field("id", candidateId).also { require(seen.add(it)) },
                lesson = obj.field("lessonId", lessonId), title = obj.passive("lessonTitle", 200),
                textChars = chars.toInt(), acceptedAtMs = obj.timestamp("acceptedAtMs"),
            )
        }, reply.opt("truncated") as? Boolean ?: error("Invalid publication queue"))
    }

    suspend fun detail(item: TranscriptPublicationItem): TranscriptPublicationDetail {
        val reply = checked("GET", "$root/${item.id}")
            .strict("id", "lessonId", "lessonTitle", "text", "textSha256", "acceptedAtMs")
        require(reply.opt("id") == item.id && reply.opt("lessonId") == item.lesson &&
            reply.passive("lessonTitle", 200) == item.title && reply.timestamp("acceptedAtMs") == item.acceptedAtMs)
        val text = reply.passive("text", 20_000)
        val digest = reply.field("textSha256", sha256)
        require(transcriptDigest(text) == digest)
        return TranscriptPublicationDetail(item, text, digest)
    }

    suspend fun prepare(visible: TranscriptPublicationDetail): PreparedTranscriptPublication {
        discard()
        val stamp = generation.get()
        check(detail(visible.item).digest == visible.digest)
        val item = visible.item
        val reply = checked("GET", "$root/${item.id}/publication-ticket")
            .strict("id", "lessonId", "lessonTitle", "ticket", "revision", "textSha256", "expiresAtMs")
        require(reply.opt("id") == item.id && reply.opt("lessonId") == item.lesson &&
            reply.passive("lessonTitle", 200) == item.title && reply.opt("textSha256") == visible.digest)
        val action = PreparedTranscriptPublication(visible, reply.field("ticket", ticketFormat),
            reply.field("revision", sha256), reply.timestamp("expiresAtMs"))
        check(validExpiry(action.expiry) && generation.get() == stamp)
        armed.set(action)
        if (generation.get() != stamp) { armed.compareAndSet(action, null); error("Publication was dismissed") }
        return action
    }

    suspend fun publish(action: PreparedTranscriptPublication) {
        val stamp = generation.get()
        check(armed.compareAndSet(action, null) && validExpiry(action.expiry))
        val item = action.visible.item
        check(detail(item).digest == action.visible.digest)
        check(generation.get() == stamp && validExpiry(action.expiry))
        val body = JSONObject().put("ticket", action.ticket).put("expectedRevision", action.revision)
            .put("expectedTextSha256", action.visible.digest)
        val reply = checked("POST", "$root/${item.id}/publish", body).strict("ok", "id", "lessonId", "status")
        require(reply.opt("ok") == true && reply.opt("id") == item.id && reply.opt("lessonId") == item.lesson &&
            reply.opt("status") == "published")
    }

    private suspend fun checked(method: String, path: String, body: JSONObject? = null): JSONObject {
        currentCoroutineContext().ensureActive()
        check(available())
        require(isTranscriptPublicationPath(method, path))
        val response = wire.request(method, path, body)
        currentCoroutineContext().ensureActive()
        check(available())
        return response
    }

    private fun validExpiry(expiry: Long): Boolean {
        val now = nowMs()
        return now in 0..(Long.MAX_VALUE - maxTicketTtlMs) && expiry > now && expiry <= now + maxTicketTtlMs
    }
}

private fun JSONObject.strict(vararg keys: String): JSONObject = also {
    require(length() == keys.size && keys.all(::has))
}
private fun JSONObject.number(key: String): Long = when (val value = opt(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Invalid publication number")
}
private fun JSONObject.timestamp(key: String): Long = number(key).also { require(it in 1..8_640_000_000_000_000) }
private fun JSONObject.field(key: String, match: Regex): String =
    (opt(key) as? String)?.takeIf(match::matches) ?: error("Invalid publication identifier")
private fun JSONObject.passive(key: String, max: Int): String {
    val value = opt(key) as? String ?: error("Invalid publication text")
    require(value.isNotBlank() && value.length <= max && value.none {
        (it.isISOControl() && it !in "\n\r\t") || it == '\u061c' || it in '\u200e'..'\u200f' ||
            it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
    })
    return value
}
