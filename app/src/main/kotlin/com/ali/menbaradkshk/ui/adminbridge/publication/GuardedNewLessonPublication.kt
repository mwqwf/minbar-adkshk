package com.ali.menbaradkshk.ui.adminbridge.publication

import com.ali.menbaradkshk.ui.adminbridge.upload.AdminLongPreparationReceipt
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraft
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal const val GUARDED_LESSON_MAX_JSON = 24 * 1024
private const val MAX_AUDIO_BYTES = 99_614_720L
private const val MAX_DURATION_MS = 10_800_000L
private const val TICKET_HORIZON_MS = 315_000L
internal const val GUARDED_LESSON_ROOT = "/admin/guarded-new-lessons"
private const val ROOT = GUARDED_LESSON_ROOT
private const val UUID4 = "[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"
private val draftId = Regex(UUID4)
private val sha256 = Regex("[a-f0-9]{64}")
private val ticketPattern = Regex("([0-9]{12,16})\\.($UUID4)\\.[a-f0-9]{64}")
private val confirmBodyPattern = Regex("""\{"confirmation":"([0-9a-f.-]{1,140})"\}""")
private val endpointPattern = Regex("$ROOT/([a-f0-9]{64})/($UUID4)/(review|confirm)")
private val onlyJson = Regex("""application/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?""", RegexOption.IGNORE_CASE)
private val hex = "0123456789abcdef"

internal fun guardedNewLessonOwnerScope(email: String): String =
    digest("minbar-long-v1\u0000$email")

internal fun sealedLessonIdentity(ownerScope: String, id: String): String {
    require(sha256.matches(ownerScope) && draftId.matches(id))
    return "private_" + digest("minbar.private.lesson.v1\u0000$ownerScope\u0000$id").take(32)
}

private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { b -> "${hex[(b.toInt() and 255) ushr 4]}${hex[b.toInt() and 15]}" }

internal fun invalidGuardedPublication(): Nothing = throw IOException("Lesson publication evidence is invalid or no longer available")

internal fun checkGuardedNewLessonRequest(request: LongUploadRequest) {
    val route = endpointPattern.matchEntire(request.path) ?: invalidGuardedPublication()
    if (request.method != LongUploadMethod.POST || request.followRedirects ||
        request.maxResponseBytes != GUARDED_LESSON_MAX_JSON) invalidGuardedPublication()
    checkPublicationPostBody(request, route.groupValues.last())

}

internal fun checkPublicationPostBody(request: LongUploadRequest, mode: String) {
    if (mode == "review") {
        if (request.body != null || request.contentType != null) invalidGuardedPublication()
    } else {
        val bytes = request.body ?: invalidGuardedPublication()
        if (request.contentType != "application/json" || bytes.size !in 1..280) invalidGuardedPublication()
        val ticket = confirmBodyPattern.matchEntire(bytes.decodeToString(throwOnInvalidSequence = true))
            ?.groupValues?.get(1) ?: invalidGuardedPublication()
        if (!ticketPattern.matches(ticket)) invalidGuardedPublication()
    }
}

internal data class GuardedNewLessonDetails(
    val lessonId: String,
    val title: String,
    val speaker: String,
    val description: String,
    val categoryName: String,
    val sectionName: String,
    val decodedDurationMs: Long,
    val audioSha256: String,
    val sizeBytes: Long,
)

internal class GuardedNewLessonReceipt(val lessonId: String)

internal data class GuardedNewLessonLocalIntent(val title: String, val speaker: String, val description: String,
    val categoryName: String, val sectionName: String)
private fun GuardedNewLessonDetails.matches(local: GuardedNewLessonLocalIntent) =
    title == local.title && speaker == local.speaker && description == local.description &&
        categoryName == local.categoryName && sectionName == local.sectionName

internal class GuardedNewLessonPublicationClient(
    private val ownerScope: String,
    private val active: () -> Boolean,
    private val exactLocalPreparation: suspend (AdminUploadDraft) -> GuardedNewLessonLocalIntent?,
    private val transport: LongUploadTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val engine = Any()
    private val lock = Mutex()

    internal class Review internal constructor(
        val details: GuardedNewLessonDetails,
        val expiresAtMs: Long,
        private val draft: AdminUploadDraft,
        private val proof: AdminLongPreparationReceipt,
        private val confirmation: String,
        private val engine: Any,
    ) {
        private val unused = AtomicBoolean(true)
        internal fun available(candidate: Any) = engine === candidate && unused.get()
        internal fun take(candidate: Any) = engine === candidate && unused.compareAndSet(true, false)
        internal fun ownDraft(candidate: Any) = draft.takeIf { engine === candidate } ?: invalidGuardedPublication()
        internal fun ownProof(candidate: Any) = proof.takeIf { engine === candidate } ?: invalidGuardedPublication()
        internal fun ownTicket(candidate: Any) = confirmation.takeIf { engine === candidate } ?: invalidGuardedPublication()
    }

    fun eligible(draft: AdminUploadDraft, proof: AdminLongPreparationReceipt): Boolean =
        active() && sha256.matches(ownerScope) && draftId.matches(draft.id) && draft.id == proof.draftId &&
            draft.byteCount in 1..MAX_AUDIO_BYTES && draft.byteCount == proof.totalBytes &&
            sha256.matches(draft.sha256) && draft.sha256 == proof.wholeSha256

    fun canConfirm(review: Review) = active() && review.available(engine) && now() < review.expiresAtMs

    suspend fun review(draft: AdminUploadDraft, proof: AdminLongPreparationReceipt): Review =
        exclusive {
            val guard = makeGuard(draft, proof)
            guard()
            val local = exactLocalPreparation(draft) ?: invalidGuardedPublication()
            guard()
            val response = exchange(draft.id, "review", null, guard)
            val frame = decodeGuardedNewLessonReview(response,draft.id,now(),sealedLessonIdentity(ownerScope,draft.id))
            val details = frame.details

            if (!details.matches(local)) invalidGuardedPublication()
            guard()
            Review(details, frame.expiresAtMs, draft, proof, frame.ticket, engine)
        }

    /** One explicit confirmation per reviewed immutable tuple; never retry a timed-out publish. */
    suspend fun publish(review: Review): GuardedNewLessonReceipt = exclusive {
        val draft = review.ownDraft(engine)
        val proof = review.ownProof(engine)
        val guard = makeGuard(draft, proof, review.expiresAtMs)
        guard()
        if (!review.take(engine) || exactLocalPreparation(draft)?.let(review.details::matches) != true) invalidGuardedPublication()
        guard()
        val request = """{"confirmation":"${review.ownTicket(engine)}"}""".encodeToByteArray()
        val response = try { parseFlatObject(exchange(draft.id,"confirm",request,guard)) }
        finally { request.fill(0) }
        if (response.keys != setOf("draftId","lessonId","created") || response["draftId"] != draft.id ||
            response["lessonId"] != review.details.lessonId || response["created"] != true) invalidGuardedPublication()
        guard()
        GuardedNewLessonReceipt(review.details.lessonId)
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!lock.tryLock()) invalidGuardedPublication()
        try { return block() } finally { lock.unlock() }
    }

    private suspend fun makeGuard(draft: AdminUploadDraft, proof: AdminLongPreparationReceipt,
                                  expiresAt: Long = Long.MAX_VALUE): () -> Unit {
        val context = currentCoroutineContext()
        return { context.ensureActive(); if (!eligible(draft,proof) || now() >= expiresAt) invalidGuardedPublication() }
    }

    private suspend fun exchange(id: String, mode: String, body: ByteArray?, guard: () -> Unit): ByteArray {
        val request = LongUploadRequest(LongUploadMethod.POST, "$ROOT/$ownerScope/$id/$mode",
            "application/json".takeIf { body != null }, body, maxResponseBytes = GUARDED_LESSON_MAX_JSON)
        checkGuardedNewLessonRequest(request)
        return readGuardedLessonResponse(transport.exchange(request, guard),guard)
    }
}

internal suspend fun readGuardedLessonResponse(response: LongUploadResponse, guard: () -> Unit): ByteArray =
    response.body.use { stream ->
        guard()
        if (response.status != 200 || response.contentType?.let(onlyJson::matches) != true ||
            response.contentLength?.let { it !in 1..GUARDED_LESSON_MAX_JSON.toLong() } == true) invalidGuardedPublication()
        val scratch = ByteArray(1024)
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < GUARDED_LESSON_MAX_JSON) {
            guard()
            val n = stream.read(scratch, 0, minOf(scratch.size, GUARDED_LESSON_MAX_JSON - bytes.size()))
            if (n == -1) break
            if (n == 0) invalidGuardedPublication()
            bytes.write(scratch,0,n)
        }
        if (bytes.size() == GUARDED_LESSON_MAX_JSON && stream.read() != -1) invalidGuardedPublication()
        if (response.contentLength?.let { it != bytes.size().toLong() } == true || bytes.size() == 0) invalidGuardedPublication()
        guard()
        bytes.toByteArray()
    }

internal class DecodedGuardedNewLessonReview(
    val details: GuardedNewLessonDetails, val expiresAtMs: Long, val ticket: String,
)

internal fun decodeGuardedNewLessonReview(bytes: ByteArray, id: String, now: Long,
                                         expectedLesson: String? = null): DecodedGuardedNewLessonReview {
    val m = parseFlatObject(bytes)
    if (m.keys != setOf("draftId","lessonId","title","speaker","description","categoryName","sectionName",
            "decodedDurationMs","audioSha256","sizeBytes","confirmation") || m["draftId"] != id) invalidGuardedPublication()
    val lesson = (m["lessonId"] as? String)?.takeIf { it.matches(Regex("private_[a-f0-9]{32}")) }
        ?: invalidGuardedPublication()
    if (expectedLesson != null && expectedLesson != lesson) invalidGuardedPublication()
    val ticket = m["confirmation"] as? String ?: invalidGuardedPublication()
    val expires = ticketPattern.matchEntire(ticket)?.groupValues?.get(1)?.toLongOrNull() ?: invalidGuardedPublication()
    if (expires <= now || expires - now > TICKET_HORIZON_MS) invalidGuardedPublication()
    val details = GuardedNewLessonDetails(
        lesson, m.field("title", 400, true), m.field("speaker", 160),
        m.field("description", 2000, multiline = true), m.field("categoryName",400,true), m.field("sectionName",400,true),
        (m["decodedDurationMs"] as? Long)?.takeIf { it in 1..MAX_DURATION_MS } ?: invalidGuardedPublication(),
        (m["audioSha256"] as? String)?.takeIf(sha256::matches) ?: invalidGuardedPublication(),
        (m["sizeBytes"] as? Long)?.takeIf { it in 47..MAX_AUDIO_BYTES } ?: invalidGuardedPublication(),
    )
    return DecodedGuardedNewLessonReview(details,expires,ticket)
}

internal fun Map<String, Any>.field(k: String, max: Int, required: Boolean = false, multiline: Boolean = false): String {
    val x = this[k] as? String ?: invalidGuardedPublication()
    val y = if (multiline) x.replace('\n',' ').replace('\r',' ').replace('\t',' ') else x
    if (x.length > max || required && x.isEmpty() || y != y.trim() || y.any {
            it == '<' || it == '>' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }) invalidGuardedPublication()
    return x
}

/** Closed, duplicate-rejecting flat JSON profile. The pinned routes emit only strings, integer
 * values and the Boolean receipt; null, arrays, extra fields and malformed Unicode are rejected. */
internal fun parseFlatObject(bytes: ByteArray, allowDiscoveryList: Boolean = false, arrayLimit: Int = 20): Map<String, Any> {
    require(arrayLimit in 1..30)
    if (bytes.isEmpty() || bytes.size > GUARDED_LESSON_MAX_JSON) invalidGuardedPublication()
    val src = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { invalidGuardedPublication() }
    var cursor = 0
    fun peek() = src.getOrNull(cursor)
    fun ws() { while (peek() in listOf(' ', '\t','\r','\n')) cursor++ }
    fun char(wanted: Char) { ws(); if (peek() != wanted) invalidGuardedPublication(); cursor++ }
    fun string(): String {
        char('"')
        val value = StringBuilder()
        while (true) {
            val c = peek() ?: invalidGuardedPublication()
            cursor++
            when {
                c == '"' -> break
                c.code < 32 -> invalidGuardedPublication()
                c != '\\' -> value.append(c)
                else -> {
                    val escape = peek() ?: invalidGuardedPublication()
                    cursor++
                    value.append(when (escape) {
                        '\\','"','/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            val group = src.substring(cursor, minOf(src.length, cursor + 4))
                            if (group.length != 4 || group.any { it !in "0123456789ABCDEFabcdef" }) invalidGuardedPublication()
                            cursor += 4
                            group.toInt(16).toChar()
                        }
                        else -> invalidGuardedPublication()
                    })
                }
            }
        }
        val s = value.toString()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c.isHighSurrogate()) { if (s.getOrNull(i++)?.isLowSurrogate() != true) invalidGuardedPublication() }
            else if (c.isLowSurrogate()) invalidGuardedPublication()
        }
        return s
    }
    fun scalar(): Any {
        ws()
        if (peek() == '"') return string()
        if (src.startsWith("true", cursor)) { cursor += 4; return true }
        if (src.startsWith("false", cursor)) { cursor += 5; return false }
        val start = cursor
        while (peek()?.let { it in '0'..'9' } == true) cursor++
        val raw = src.substring(start,cursor)
        if (raw.isEmpty() || raw.length > 16 || raw.length > 1 && raw[0] == '0') invalidGuardedPublication()
        return raw.toLongOrNull() ?: invalidGuardedPublication()
    }
    // Only the explicitly separate discovery envelope may contain one bounded array
    // of flat objects. Reviews and receipts retain the closed flat grammar.
    fun mapRows(outer: Boolean, nestedList: () -> List<Map<String, Any>>): Map<String, Any> {
        char('{')
        val result = linkedMapOf<String, Any>()
        do {
            val key = string()
            if (result.size >= 11 || result.containsKey(key)) invalidGuardedPublication()
            char(':')
            result[key] = if (outer && allowDiscoveryList && key == "items") nestedList() else scalar()
            ws()
            if (peek() != ',') break
            cursor++
        } while (true)
        char('}')
        return result
    }
    val result = mapRows(outer = true) {
        char('[')
        val list = mutableListOf<Map<String, Any>>()
        ws()
        if (peek() != ']') {
            do {
                if (list.size >= arrayLimit) invalidGuardedPublication()
                list += mapRows(outer = false) { invalidGuardedPublication() }
                ws()
                if (peek() != ',') break
                cursor++
            } while (true)
        }
        char(']')
        list
    }
    ws()
    if (cursor != src.length) invalidGuardedPublication()
    return result
}
