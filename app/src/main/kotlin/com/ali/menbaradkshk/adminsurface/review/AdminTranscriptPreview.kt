package com.ali.menbaradkshk.adminsurface.review

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val queuePath = "/admin/transcript-review"
private const val maxItems = 20
private const val maxTextChars = 20_000
private const val hourMillis = 3_600_000L
private const val maxEpochMillis = 8_640_000_000_000_000L
private val opaqueId = Regex("tsub_(?:[0-9a-f]{20}|[0-9]{10,16}_[0-9a-f]{6})")

internal fun isTranscriptPreviewPath(path: String): Boolean =
    path == queuePath || path.startsWith("$queuePath/") && opaqueId.matches(path.removePrefix("$queuePath/"))

internal fun canReadPendingTranscriptReview(whoami: JSONObject): Boolean =
    whoami.opt("canReadPendingTranscriptReview") == true

internal enum class TranscriptPreviewKind(val wire: String, val label: String) {
    Text("text", "نصّ"),
    TextAndPages("text_and_pages", "نصّ مع صفحات"),
}

internal class TranscriptPreviewItem(
    val id: String,
    val kind: TranscriptPreviewKind,
    val textChars: Int,
    val createdHourMs: Long,
)

internal class TranscriptPreviewQueue(
    val items: List<TranscriptPreviewItem>,
    val truncated: Boolean,
)

internal class TranscriptPreview(val text: String)
internal fun interface TranscriptPreviewWire { suspend fun get(path: String): JSONObject }

/** Construct only for an independently verified current ms_ admin lease. No fallback endpoint. */
internal class AdminTranscriptPreviewReader(
    private val canRead: () -> Boolean,
    private val wire: TranscriptPreviewWire,
) {
    fun isAvailable(): Boolean = canRead()

    suspend fun queue(): TranscriptPreviewQueue = decodeTranscriptQueue(checkedGet(queuePath))

    suspend fun preview(item: TranscriptPreviewItem): TranscriptPreview {
        require(item.textChars > 0 && opaqueId.matches(item.id))
        val value = checkedGet("$queuePath/${item.id}")
        requireExact(value, "id", "kind", "text", "createdHourMs")
        require(value.opt("id") == item.id && value.opt("kind") == item.kind.wire)
        require(readHour(value) == item.createdHourMs)
        val text = value.opt("text") as? String ?: error("Invalid transcript")
        require(text.isNotBlank() && text.length <= maxTextChars && text.none(::prohibitedTextChar))
        return TranscriptPreview(text)
    }

    private suspend fun checkedGet(path: String): JSONObject {
        currentCoroutineContext().ensureActive()
        check(canRead())
        val response = wire.get(path)
        currentCoroutineContext().ensureActive()
        check(canRead())
        return response
    }
}

private fun decodeTranscriptQueue(value: JSONObject): TranscriptPreviewQueue {
    requireExact(value, "items", "truncated")
    val entries = value.optJSONArray("items") ?: error("Invalid queue")
    require(entries.length() <= maxItems)
    val truncated = value.opt("truncated") as? Boolean ?: error("Invalid queue")
    val keys = mutableSetOf<String>()
    return TranscriptPreviewQueue(List(entries.length()) { i ->
        val item = entries.optJSONObject(i) ?: error("Invalid item")
        requireExact(item, "id", "kind", "textChars", "createdHourMs")
        val id = item.opt("id") as? String ?: error("Invalid item ID")
        require(opaqueId.matches(id) && keys.add(id))
        val kind = TranscriptPreviewKind.entries.singleOrNull { it.wire == item.opt("kind") }
            ?: error("Invalid item kind")
        val chars = exactLong(item.opt("textChars"))
        require(chars in 1..maxTextChars.toLong())
        TranscriptPreviewItem(id, kind, chars.toInt(), readHour(item))
    }, truncated)
}

private fun requireExact(value: JSONObject, vararg keys: String) {
    require(value.length() == keys.size && keys.all(value::has))
}

private fun exactLong(value: Any?): Long = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Invalid number")
}

private fun readHour(value: JSONObject): Long = exactLong(value.opt("createdHourMs")).also {
    require(it in 0..maxEpochMillis && it % hourMillis == 0L)
}

private fun prohibitedTextChar(c: Char): Boolean =
    c.isISOControl() && c !in "\n\r\t" ||
        c == '\u061c' || c in '\u200e'..'\u200f' || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069'
