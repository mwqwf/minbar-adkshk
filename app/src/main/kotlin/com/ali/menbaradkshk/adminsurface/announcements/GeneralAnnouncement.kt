package com.ali.menbaradkshk.adminsurface.announcements

import java.text.Normalizer

internal const val GENERAL_NOTICE_REVIEW = "/admin/guarded-general-notices/review"
internal const val GENERAL_NOTICE_CONFIRM = "/admin/guarded-general-notices/confirm"
internal const val GENERAL_NOTICE_TITLE_MAX = 120
internal const val GENERAL_NOTICE_BODY_MAX = 640
internal const val GENERAL_NOTICE_WIRE_MAX = 4096
private const val MAX_EPOCH = 9_007_199_254_740_991L
private const val MAX_REVIEW_AGE = 120_000L
private const val operationShape = "[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"
private val operationIdFormat = Regex(operationShape)
private val confirmationFormat = Regex("([0-9]{12,16})\\.($operationShape)\\.[a-f0-9]{64}")
private val noticeIdFormat = Regex("gnotice_[a-f0-9]{64}")

internal data class GeneralNoticeIntent(val operationId: String, val title: String, val body: String) {
    init {
        require(operationIdFormat.matches(operationId))
        require(title == cleanGeneralNoticeText(title, GENERAL_NOTICE_TITLE_MAX, false))
        require(body == cleanGeneralNoticeText(body, GENERAL_NOTICE_BODY_MAX, true))
    }

    fun wire(confirmation: String? = null): ByteArray {
        require(confirmation == null || confirmationFormat.matches(confirmation))
        return buildString {
            append("{\"operationId\":"); appendNoticeText(operationId)
            append(",\"title\":"); appendNoticeText(title)
            append(",\"body\":"); appendNoticeText(body)
            if (confirmation != null) {
                append(",\"confirmation\":"); appendNoticeText(confirmation)
            }
            append('}')
        }.encodeToByteArray().also { require(it.size <= GENERAL_NOTICE_WIRE_MAX) }
    }
}

private fun StringBuilder.appendNoticeText(value: String) {
    append('"')
    for (c in value) when (c) {
        '\n' -> append("\\n")
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        else -> append(c)
    }
    append('"')
}

// Match the Worker’s ECMAScript trim set, not Java's broader control-character trimming.
private fun jsTrim(c: Char) = c in '\u0009'..'\u000d' ||
    c in listOf('\u0020', '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff') ||
    c in '\u2000'..'\u200a'

internal fun cleanGeneralNoticeText(input: String, limit: Int, multiline: Boolean): String? {
    val text = Normalizer.normalize(input.trim(::jsTrim), Normalizer.Form.NFC)
    if (text.length !in 1..limit) return null
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (multiline && cp == '\n'.code) continue
        if (Character.getType(cp) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(),
                Character.SURROGATE.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())) return null
    }
    return text
}

internal data class GeneralNoticeReview(
    val intent: GeneralNoticeIntent,
    val expiresAtMs: Long,
    private val capsule: String,
) {
    fun confirmationWire() = intent.wire(capsule)
    override fun toString(): String = "GeneralNoticeReview(redacted)"
}

internal data class GeneralNoticeReceipt(val noticeId: String, val publishedAtMs: Long)

private fun Map<String, Any?>.isExact(vararg fields: String) = keys == fields.toSet()
private fun Map<String, Any?>.time(field: String): Long = when (val value = get(field)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Invalid notice timestamp")
}.also { require(it in 1..MAX_EPOCH) }

internal class GeneralNoticeClient(
    private val active: () -> Boolean,
    private val post: suspend (String, ByteArray) -> Map<String, Any?>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun available() = active()

    suspend fun review(intent: GeneralNoticeIntent): GeneralNoticeReview {
        check(active())
        val response = post(GENERAL_NOTICE_REVIEW, intent.wire())
        check(active())
        require(response.isExact("operationId", "title", "body", "audience", "delivery", "expiresAtMs", "confirmation"))
        require(response["operationId"] == intent.operationId && response["title"] == intent.title &&
            response["body"] == intent.body && response["audience"] == "public" &&
            response["delivery"] == "polling")
        val expiresAt = response.time("expiresAtMs")
        // Clock-skew grace only for display of a server-reviewed draft; the server enforces actual expiry.
        require(expiresAt - now() in 1..MAX_REVIEW_AGE + 30_000)
        val confirmation = response["confirmation"] as? String ?: error("No notice confirmation")
        require(confirmationFormat.matchEntire(confirmation)?.groupValues?.get(1)?.toLongOrNull() == expiresAt)
        return GeneralNoticeReview(intent, expiresAt, confirmation)
    }

    suspend fun confirm(review: GeneralNoticeReview): GeneralNoticeReceipt {
        check(active() && review.expiresAtMs > now())
        val response = post(GENERAL_NOTICE_CONFIRM, review.confirmationWire())
        check(active())
        require(response.isExact("operationId", "noticeId", "publishedAtMs", "published", "audience", "delivery"))
        require(response["operationId"] == review.intent.operationId && response["published"] == true &&
            response["audience"] == "public" && response["delivery"] == "polling")
        val id = response["noticeId"] as? String ?: error("No notice id")
        require(noticeIdFormat.matches(id))
        val stamp = response.time("publishedAtMs")
        require(stamp < review.expiresAtMs)
        return GeneralNoticeReceipt(id, stamp)
    }
}
