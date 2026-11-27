package com.ali.menbaradkshk.ui.adminbridge.order

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

internal const val GUARDED_LESSON_ORDER_ANDROID_ON = false
private const val ROOT = "/admin/guarded-lesson-order/"
private const val Limit = 20
private val key = Regex("[a-zA-Z0-9_.-]{1,128}")
private val digest = Regex("[a-f0-9]{64}")
private fun id(x: Any?) = (x as? String)?.takeIf { key.matches(it) && it != "." && it != ".." }
private fun sig(x: Any?) = (x as? String)?.takeIf(digest::matches)
private fun invalid(): Nothing = throw IOException("Guarded lesson order unavailable")

private fun safeTitle(s: String): Boolean {
    if (s.length !in 1..400) return false
    var cursor = 0
    while (cursor < s.length) {
        val cp = Character.codePointAt(s,cursor)
        if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp in 0xD800..0xDFFF)
            return false
        cursor += Character.charCount(cp)
    }
    return true
}

internal enum class GuardedOrderDirection(val wire: String, val offset: Int) { Up("up",-1), Down("down",1) }

internal fun checkGuardedLessonOrderRequest(request: LongUploadRequest) {
    val path = request.path.takeIf { it.startsWith(ROOT) }?.removePrefix(ROOT)?.split('/')
    if (path?.size != 2 || path.any { id(it) == null } || request.followRedirects ||
        request.maxResponseBytes != GUARDED_LESSON_MAX_JSON) invalid()
    when (request.method) {
        LongUploadMethod.GET -> if (request.body != null || request.contentType != null) invalid()
        LongUploadMethod.POST -> {
            if (request.contentType != "application/json") invalid()
            val bytes = request.body?.takeIf { it.size in 1..512 } ?: invalid()
            val m = parseFlatObject(bytes)
            if (m.keys != setOf("revision","lessonId","direction") || sig(m["revision"]) == null || id(m["lessonId"]) == null ||
                GuardedOrderDirection.entries.none { it.wire == m["direction"] }) invalid()
        }
        else -> invalid()
    }
}

internal class GuardedLessonOrderClient(
    private val active: () -> Boolean,
    private val transport: LongUploadTransport,
) {
    internal data class Sibling(val id: String, val title: String)
    internal class Review internal constructor(
        val categoryId: String,
        val subcategoryId: String,
        val target: Sibling,
        val before: Sibling?,
        val after: Sibling?,
        private val revision: String,
        internal val position: Int,
    ) {
        private val spent = AtomicBoolean(false)
        internal fun body(direction: GuardedOrderDirection): ByteArray {
            if ((if (direction == GuardedOrderDirection.Up) before else after) == null ||
                !spent.compareAndSet(false,true)) invalid()
            return """{"revision":"$revision","lessonId":"${target.id}","direction":"${direction.wire}"}""".encodeToByteArray()
        }
    }

    private val mutex = Mutex()
    @Volatile private var frame: Review? = null
    fun available() = active()
    fun discard() { frame = null }

    suspend fun review(categoryId: String, subcategoryId: String, lessonId: String): Review = mutex.withLock {
        if (id(categoryId) == null || id(subcategoryId) == null || id(lessonId) == null) invalid()
        discard()
        val guard = guard()
        val m = parseFlatObject(exchange(LongUploadMethod.GET,categoryId,subcategoryId,null,guard),allowDiscoveryList=true,arrayLimit=Limit)
        if (m.keys != setOf("categoryId","subcategoryId","revision","items") ||
            m["categoryId"] != categoryId || m["subcategoryId"] != subcategoryId) invalid()
        val revision = sig(m["revision"]) ?: invalid()
        val raw = (m["items"] as? List<*>)?.takeIf { it.size in 2..Limit } ?: invalid()
        val siblings = raw.map {
            val row = it as? Map<*,*> ?: invalid()
            if (row.keys != setOf("id","title")) invalid()
            Sibling(id(row["id"]) ?: invalid(), (row["title"] as? String)?.takeIf(::safeTitle) ?: invalid())
        }
        if (siblings.map(Sibling::id).toSet().size != siblings.size) invalid()
        val position = siblings.indexOfFirst { it.id == lessonId }
        if (position < 0) invalid()
        guard()
        Review(categoryId,subcategoryId,siblings[position],siblings.getOrNull(position-1),
            siblings.getOrNull(position+1),revision,position+1).also { frame=it }
    }

    suspend fun confirm(item: Review, direction: GuardedOrderDirection): Int = mutex.withLock {
        val guard = guard(item)
        guard()
        val body = item.body(direction)
        try {
            val m = parseFlatObject(exchange(LongUploadMethod.POST,item.categoryId,item.subcategoryId,body,guard))
            val pos = item.position + direction.offset
            if (m.keys != setOf("categoryId","subcategoryId","lessonId","direction","version","position") ||
                m["categoryId"] != item.categoryId || m["subcategoryId"] != item.subcategoryId ||
                m["lessonId"] != item.target.id || m["direction"] != direction.wire || m["position"] != pos.toLong() ||
                (m["version"] as? Long)?.let { it in 1..9_007_199_254_740_990L } != true) invalid()
            guard()
            pos
        } finally {
            discard()
            body.fill(0)
        }
    }
    private suspend fun guard(item: Review? = null): () -> Unit {
        val ctx = currentCoroutineContext()
        return { ctx.ensureActive(); if (!active() || item != null && frame !== item) invalid() }
    }
    private suspend fun exchange(method: LongUploadMethod, category: String, section: String, body: ByteArray?,
                                 guard: () -> Unit): ByteArray {
        guard()
        val req = LongUploadRequest(method,ROOT+category+"/"+section,"application/json".takeIf { body != null },
            body,GUARDED_LESSON_MAX_JSON)
        checkGuardedLessonOrderRequest(req)
        return readGuardedLessonResponse(transport.exchange(req,guard),guard)
    }
}
