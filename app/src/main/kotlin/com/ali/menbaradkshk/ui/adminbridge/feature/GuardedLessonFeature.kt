package com.ali.menbaradkshk.ui.adminbridge.feature

import com.ali.menbaradkshk.ui.adminbridge.publication.GUARDED_LESSON_MAX_JSON
import com.ali.menbaradkshk.ui.adminbridge.publication.parseFlatObject
import com.ali.menbaradkshk.ui.adminbridge.publication.readGuardedLessonResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val GUARDED_LESSON_FEATURE_ANDROID_ON = false
// Enable only after the paired Worker accepts the extended command.
private const val GUARDED_LESSON_FEATURE_TIMED_ANDROID_ON = false
private const val MAX_FEATURE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
private const val ROOT = "/admin/guarded-lesson-feature/"
private const val SAFE = 9_007_199_254_740_990L
private val idShape = Regex("[A-Za-z0-9_.-]{1,128}")
private fun validId(s: String) = idShape.matches(s) && s != "." && s != ".."
private fun invalid(): Nothing = throw IOException("Guarded lesson feature unavailable")
private fun cleanTitle(s: String): Boolean {
    if (s.length !in 1..400) return false
    var cursor = 0
    while (cursor < s.length) {
        val cp = Character.codePointAt(s, cursor)
        if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() ||
            cp in 0xD800..0xDFFF) return false
        cursor += Character.charCount(cp)
    }
    return true
}
private data class State(
    val title: String, val categoryId: String, val subcategoryId: String, val updatedAtMs: Long,
    val featured: Boolean, val featuredUntilMs: Long,
)

private fun parse(m: Map<String, Any>, prefix: String): State {
    fun field(key: String) = if (prefix.isEmpty()) key else prefix + key.replaceFirstChar(Char::uppercaseChar)
    val title = (m[field("title")] as? String)?.takeIf(::cleanTitle) ?: invalid()
    val cat = (m[field("categoryId")] as? String)?.takeIf(::validId) ?: invalid()
    val sub = (m[field("subcategoryId")] as? String)?.takeIf(::validId) ?: invalid()
    val time = (m[field("updatedAtMs")] as? Long)?.takeIf { it in 0..SAFE } ?: invalid()
    val featured = m[field("featured")] as? Boolean ?: invalid()
    val until = (m[field("featuredUntilMs")] as? Long)?.takeIf { it in 0..SAFE } ?: invalid()
    return State(title, cat, sub, time, featured, until)
}
private val responseKeySet = setOf("id", "title", "categoryId", "subcategoryId", "updatedAtMs", "featured", "featuredUntilMs")
private val commandKeySet = setOf("expectedTitle", "expectedCategoryId", "expectedSubcategoryId",
    "expectedUpdatedAtMs", "expectedFeatured", "expectedFeaturedUntilMs", "featured")
private fun decode(bytes: ByteArray, lessonId: String): State {
    val m = parseFlatObject(bytes)
    if (m.keys != responseKeySet || m["id"] != lessonId) invalid()
    return parse(m, "")
}
// The title is already validated for controls; only JSON structural characters need escaping.
private fun jsonQuote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal fun checkGuardedLessonFeatureRequest(req: LongUploadRequest) {
    if (!req.path.startsWith(ROOT) || !validId(req.path.removePrefix(ROOT)) || req.followRedirects ||
        req.maxResponseBytes != GUARDED_LESSON_MAX_JSON) invalid()
    when (req.method) {
        LongUploadMethod.GET -> if (req.body != null || req.contentType != null) invalid()
        LongUploadMethod.POST -> {
            if (req.contentType != "application/json") invalid()
            val bytes = req.body?.takeIf { it.size in 1..4096 } ?: invalid()
            val payload = parseFlatObject(bytes)
            val timed = payload.containsKey("featuredUntilMs")
            if (payload.keys != (if (timed) commandKeySet + "featuredUntilMs" else commandKeySet)) invalid()
            val desired = payload["featured"] as? Boolean ?: invalid()
            val old = parse(payload, "expected")
            if (timed) {
                val until = payload["featuredUntilMs"] as? Long ?: invalid()
                if (!desired || until !in 0..SAFE || (old.featured && old.featuredUntilMs == until) ||
                    (!old.featured && until == 0L)) invalid()
            } else if (old.featured == desired) invalid()
        }
        else -> invalid()
    }
}

internal class GuardedLessonFeatureClient(
    private val active: () -> Boolean,
    private val transport: LongUploadTransport,
    private val timedEnabled: Boolean = GUARDED_LESSON_FEATURE_TIMED_ANDROID_ON,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    internal class Review internal constructor(
        val id: String,
        val title: String,
        val categoryId: String,
        val subcategoryId: String,
        val revision: Long,
        val featured: Boolean,
        val featuredUntilMs: Long,
    ) {
        private val confirmed = AtomicBoolean(false)
        internal fun consume() = confirmed.compareAndSet(false,true)
    }

    private val lock = Mutex()
    @Volatile private var latest: Review? = null

    fun available() = active()
    fun canSetDeadline() = timedEnabled && active()
    fun discard() { latest = null }

    suspend fun review(id: String): Review = lock.withLock {
        if (!validId(id)) invalid()
        latest = null
        val guard = guard()
        guard()
        val seen = decode(exchange(LongUploadMethod.GET, id, null, guard), id)
        guard()
        Review(id, seen.title, seen.categoryId, seen.subcategoryId, seen.updatedAtMs, seen.featured, seen.featuredUntilMs)
            .also { latest = it }
    }

    suspend fun confirm(review: Review): Boolean = confirm(review, null)

    /** Absolute deadline from the explicitly reviewed choice; zero keeps an already-timed lesson featured. */
    suspend fun confirmDeadline(review: Review, deadlineMs: Long): Boolean = confirm(review, deadlineMs)

    private suspend fun confirm(review: Review, deadlineMs: Long?): Boolean = lock.withLock {
        val guard = guard(review)
        guard()
        if (deadlineMs != null) {
            val now = nowMs()
            if (!canSetDeadline() || deadlineMs !in 0..SAFE ||
                (deadlineMs == 0L && (!review.featured || review.featuredUntilMs == 0L)) ||
                (deadlineMs > 0L && (now !in 1..SAFE || deadlineMs <= now || deadlineMs - now > MAX_FEATURE_WINDOW_MS)) ||
                (review.featured && review.featuredUntilMs == deadlineMs)) invalid()
        }
        if (!review.consume()) invalid()
        val desired = deadlineMs != null || !review.featured
        val body = ("{\"expectedTitle\":" + jsonQuote(review.title) +
            ",\"expectedCategoryId\":" + jsonQuote(review.categoryId) +
            ",\"expectedSubcategoryId\":" + jsonQuote(review.subcategoryId) +
            ",\"expectedUpdatedAtMs\":${review.revision},\"expectedFeatured\":${review.featured}" +
            ",\"expectedFeaturedUntilMs\":${review.featuredUntilMs},\"featured\":$desired" +
            (deadlineMs?.let { ",\"featuredUntilMs\":$it" } ?: "") + "}").encodeToByteArray()
        try {
            val result = decode(exchange(LongUploadMethod.POST, review.id, body, guard), review.id)
            guard()
            if (result.title != review.title || result.categoryId != review.categoryId ||
                result.subcategoryId != review.subcategoryId || result.updatedAtMs <= review.revision ||
                result.featured != desired || result.featuredUntilMs != (deadlineMs ?: 0L)) invalid()
            desired
        } finally {
            discard()
            body.fill(0)
        }
    }

    private suspend fun guard(review: Review? = null): () -> Unit {
        val context = currentCoroutineContext()
        return { context.ensureActive(); if (!active() || review != null && latest !== review) invalid() }
    }
    private suspend fun exchange(method: LongUploadMethod,id: String,body: ByteArray?,guard: () -> Unit): ByteArray {
        guard()
        val request = LongUploadRequest(method, ROOT + id, "application/json".takeIf { body != null }, body, GUARDED_LESSON_MAX_JSON)
        checkGuardedLessonFeatureRequest(request)
        return readGuardedLessonResponse(transport.exchange(request, guard), guard)
    }
}
