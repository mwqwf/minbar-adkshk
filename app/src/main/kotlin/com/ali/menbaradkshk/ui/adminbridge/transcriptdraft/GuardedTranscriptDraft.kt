package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import java.text.Normalizer
import java.io.IOException

internal const val TRANSCRIPT_DRAFT_ROOT = "/admin/guarded-transcript-drafts"
internal const val TRANSCRIPT_DRAFT_MAX_TEXT = 12_000
internal const val TRANSCRIPT_DRAFT_MAX_BYTES = 48 * 1024
internal const val TRANSCRIPT_DRAFT_MAX_RESPONSE_BYTES = TRANSCRIPT_DRAFT_MAX_BYTES + 512
private const val MAX_DRAFT_REVISION = 9_007_199_254_740_990L
private val lessonShape = Regex("[A-Za-z0-9_-]{1,100}")
private val opShape = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
private val unsafeLiteral = Regex("[<>\\[\\]{}`*#]")
private val unsafeEntity = Regex("&[a-z][a-z0-9]{1,20};", RegexOption.IGNORE_CASE)
private val unsafeLinkOrPath = Regex(
    "https?://|ftp://|file://|content://|mailto:|tel:|javascript:|vbscript:|data:|intent:|market:|" +
        "minbar:|www\\.|user/|images/|serving/",
    RegexOption.IGNORE_CASE,
)
private val unsafeEmail = Regex("[\\p{L}\\p{N}.!#$%&'+/=?^_|}~-]{1,128}@[\\p{L}\\p{N}.-]{1,191}\\.[\\p{L}]{2,63}")

private fun draftEdge(c: Char): Boolean = c in '\u0009'..'\u000d' || c in '\u2000'..'\u200a' ||
    c in listOf('\u0020', '\u00a0', '\u1680', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff')

internal fun validTranscriptDraftLesson(lessonId: String, revision: Long) =
    lessonShape.matches(lessonId) && revision in 1..MAX_DRAFT_REVISION

internal fun knownAvailableTranscriptLesson(audioStatus: Any?, publishAtMs: Any?, nowMs: Long): Boolean {
    val publish = when (publishAtMs) {
        is Int -> publishAtMs.toLong()
        is Long -> publishAtMs
        else -> return false
    }
    return audioStatus == "ready" && (publish == 0L || publish in 1..nowMs)
}

/** Conservative client preflight. The server remains authoritative; never silently transform the text. */
internal fun isPassiveDraftText(text: String): Boolean {
    if (text.length !in 1..TRANSCRIPT_DRAFT_MAX_TEXT || text.trim(::draftEdge) != text ||
        !Normalizer.isNormalized(text, Normalizer.Form.NFC) || unsafeLiteral.containsMatchIn(text) ||
        unsafeEntity.containsMatchIn(text) || unsafeLinkOrPath.containsMatchIn(text) || unsafeEmail.containsMatchIn(text)) return false
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (cp == '\n'.code) continue
        when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> return false
        }
    }
    return true
}

internal data class GuardedTranscriptDraftIntent(
    val lessonId: String,
    val operationId: String,
    val lessonUpdatedAtMs: Long,
    val text: String,
) {
    init {
        require(validTranscriptDraftLesson(lessonId, lessonUpdatedAtMs))
        require(opShape.matches(operationId) && isPassiveDraftText(text))
    }

    val path: String get() = "$TRANSCRIPT_DRAFT_ROOT/$lessonId/$operationId"

    fun wire(): ByteArray = buildString {
        append("{\"text\":\"")
        for (c in text) when (c) {
            '\n' -> append("\\n")
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(c)
        }
        append("\",\"lessonUpdatedAtMs\":")
        append(lessonUpdatedAtMs)
        append('}')
    }.encodeToByteArray().also { require(it.size <= TRANSCRIPT_DRAFT_MAX_BYTES) }

    override fun toString(): String = "GuardedTranscriptDraftIntent(redacted)"
}

internal class TranscriptDraftConflict : IOException("Transcript draft operation conflicts")
internal class TranscriptDraftNotFound : IOException("Transcript draft receipt not found")

/** Local recovery permission can only be earned by two scoped, typed observations. */
internal data class TranscriptDraftProgress(
    private val sawConflict: Boolean = false,
    private val sawLaterNotFound: Boolean = false,
) {
    val mayArchiveLocally: Boolean get() = sawConflict && sawLaterNotFound
    fun unverified() = TranscriptDraftProgress(sawConflict, false)
    fun conflict() = TranscriptDraftProgress(true, false)
    fun missing() = TranscriptDraftProgress(sawConflict, sawConflict)
}

internal fun matchesLatestTranscriptDraft(
    latest: GuardedTranscriptDraftIntent?,
    lessonId: String,
    revision: Long,
    text: String,
) = latest?.lessonId == lessonId && latest.lessonUpdatedAtMs == revision && latest.text == text

internal data class GuardedTranscriptDraftReceipt(val savedAtMs: Long)

private fun Map<String, Any?>.positiveTime(key: String) = when (val v = get(key)) {
    is Int -> v.toLong()
    is Long -> v
    else -> error("Invalid draft time")
}.also { require(it in 1..MAX_DRAFT_REVISION) }

internal fun isGuardedTranscriptDraftMutationPath(path: String) = path == TRANSCRIPT_DRAFT_ROOT ||
    path.startsWith("$TRANSCRIPT_DRAFT_ROOT/") || path.startsWith("$TRANSCRIPT_DRAFT_ROOT?")

internal class GuardedTranscriptDraftClient(
    private val active: () -> Boolean,
    private val send: suspend (method: String, path: String, body: ByteArray?) -> Map<String, Any?>,
) {
    fun available() = active()

    suspend fun save(confirmed: GuardedTranscriptDraftIntent): GuardedTranscriptDraftReceipt =
        exchange("POST", confirmed, confirmed.wire())

    suspend fun recover(original: GuardedTranscriptDraftIntent): GuardedTranscriptDraftReceipt =
        exchange("GET", original, null)

    private suspend fun exchange(method: String, intent: GuardedTranscriptDraftIntent, body: ByteArray?): GuardedTranscriptDraftReceipt {
        check(active())
        val response = send(method, intent.path, body)
        check(active())
        require(response.keys == setOf("operationId", "lessonId", "lessonUpdatedAtMs", "text", "savedAtMs"))
        require(response["operationId"] == intent.operationId && response["lessonId"] == intent.lessonId &&
            response.positiveTime("lessonUpdatedAtMs") == intent.lessonUpdatedAtMs && response["text"] == intent.text)
        return GuardedTranscriptDraftReceipt(response.positiveTime("savedAtMs"))
    }
}
