package com.ali.menbaradkshk.adminsurface.support

import org.json.JSONArray
import org.json.JSONObject

internal const val SUPPORT_INBOX_PATH = "/admin/guarded-support-inbox"
private val threadIdentifier = Regex("st_(?:[a-f0-9]{20}|[0-9]{10,16}_[a-f0-9]{6})")
private val supportKinds = setOf("suggestion", "bug", "lesson_help", "idea", "supervision", "other")
internal fun isGuardedSupportPath(path: String): Boolean = path == SUPPORT_INBOX_PATH ||
    path.startsWith("$SUPPORT_INBOX_PATH/") && threadIdentifier.matches(path.removePrefix("$SUPPORT_INBOX_PATH/"))

internal data class SupportInboxThread(
    val id: String,
    val kind: String,
    val closed: Boolean,
    val unread: Boolean,
    val messageCount: Long,
    val lastMessageAtMs: Long,
)
internal data class SupportInboxMessage(
    val fromOwner: Boolean,
    val text: String,
    val createdAtMs: Long,
    val hasAudio: Boolean,
    val hasImages: Boolean,
)
internal data class SupportInboxPage<out T>(val items: List<T>, val truncated: Boolean)

internal class GuardedSupportInboxAccess(
    private val allowed: () -> Boolean,
    private val get: suspend (String) -> JSONObject,
) {
    fun available() = allowed()

    suspend fun threads(): SupportInboxPage<SupportInboxThread> {
        check(allowed())
        val result = get(SUPPORT_INBOX_PATH)
        check(allowed())
        return decodeSupportThreads(result)
    }

    suspend fun messages(threadId: String): SupportInboxPage<SupportInboxMessage> {
        check(allowed())
        require(threadIdentifier.matches(threadId))
        val result = get("$SUPPORT_INBOX_PATH/$threadId")
        check(allowed())
        return decodeSupportMessages(result, threadId)
    }
}

private fun JSONObject.exactKeys(vararg keys: String) = require(this.keys().asSequence().toSet() == keys.toSet())
private fun JSONObject.exactBool(key: String) = opt(key) as? Boolean ?: error("Invalid support response")
private fun JSONObject.natural(key: String) = when (val n = opt(key)) {
    is Int -> n.toLong()
    is Long -> n
    else -> error("Invalid support response")
}.also { require(it >= 0) }
private fun JSONObject.rows(max: Int): List<JSONObject> {
    val values = opt("items") as? JSONArray ?: error("Invalid support response")
    require(values.length() <= max)
    return (0 until values.length()).map { values.optJSONObject(it) ?: error("Invalid support response") }
}

internal fun decodeSupportThreads(value: JSONObject): SupportInboxPage<SupportInboxThread> {
    value.exactKeys("items", "truncated")
    val items = value.rows(50).map {
        it.exactKeys("id", "kind", "closed", "unread", "messageCount", "lastMessageAtMs")
        val id = it.opt("id") as? String ?: error("Invalid support response")
        val kind = it.opt("kind") as? String ?: error("Invalid support response")
        require(threadIdentifier.matches(id) && kind in supportKinds)
        SupportInboxThread(id, kind, it.exactBool("closed"), it.exactBool("unread"),
            it.natural("messageCount"), it.natural("lastMessageAtMs"))
    }
    require(items.map { it.id }.toSet().size == items.size)
    return SupportInboxPage(items, value.exactBool("truncated"))
}

internal fun decodeSupportMessages(value: JSONObject, threadId: String): SupportInboxPage<SupportInboxMessage> {
    require(threadIdentifier.matches(threadId))
    value.exactKeys("threadId", "items", "truncated")
    require(value.opt("threadId") == threadId)
    val items = value.rows(100).map {
        it.exactKeys("fromOwner", "text", "createdAtMs", "hasAudio", "hasImages")
        val text = it.opt("text") as? String ?: error("Invalid support response")
        require(text.length <= 2000 && text.none { char ->
            char.isISOControl() && char != '\n' && char != '\r' && char != '\t' ||
                Character.getType(char) == Character.FORMAT.toInt()
        })
        SupportInboxMessage(it.exactBool("fromOwner"), text, it.natural("createdAtMs"),
            it.exactBool("hasAudio"), it.exactBool("hasImages"))
    }
    return SupportInboxPage(items, value.exactBool("truncated"))
}
