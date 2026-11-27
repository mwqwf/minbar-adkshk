package com.ali.menbaradkshk.ui.adminbridge.publication

import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
private val byDraftPath = Regex("${GUARDED_LESSON_ROOT}/by-draft/(${uuid.pattern})/(review|confirm)")
private const val LIST = "${GUARDED_LESSON_ROOT}/candidates"

internal fun checkGuardedDiscoveryRequest(request: LongUploadRequest) {
    if (request.followRedirects || request.maxResponseBytes != GUARDED_LESSON_MAX_JSON) invalidGuardedPublication()
    if (request.path == LIST) {
        if (request.method != LongUploadMethod.GET || request.body != null || request.contentType != null)
            invalidGuardedPublication()
    } else {
        val route = byDraftPath.matchEntire(request.path) ?: invalidGuardedPublication()
        if (request.method != LongUploadMethod.POST) invalidGuardedPublication()
        checkPublicationPostBody(request,route.groupValues.last())
    }
}

internal class GuardedNewLessonDiscoveryClient(
    private val active: () -> Boolean,
    private val transport: LongUploadTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal class Candidate internal constructor(internal val id: String, val title: String)
    internal class Candidates internal constructor(val rows: List<Candidate>, val truncated: Boolean)
    private val engine = Any()
    private val mutex = Mutex()
    @Volatile private var current: Candidates? = null
    internal class Review internal constructor(val details: GuardedNewLessonDetails, val expiresAtMs: Long,
        internal val candidate: Candidate, private val ticket: String, private val engine: Any) {
        private val unused = AtomicBoolean(true)
        internal fun available(owner: Any) = engine === owner && unused.get()
        internal fun use(owner: Any): String {
            if (owner !== engine || !unused.compareAndSet(true,false)) invalidGuardedPublication()
            return ticket
        }
    }

    fun available() = active()
    fun canConfirm(review: Review) = active() && review.available(engine) && now() < review.expiresAtMs &&
        current?.rows?.any { it === review.candidate } == true

    suspend fun list(): Candidates = locked {
        val guard = guard()
        guard()
        current = null
        val response = call(LIST,LongUploadMethod.GET,null,guard)
        val parsed = parseFlatObject(response, allowDiscoveryList = true)
        if (parsed.keys != setOf("state","items","truncated") || parsed["state"] != "encoded_review_required")
            invalidGuardedPublication()
        val truncated = parsed["truncated"] as? Boolean ?: invalidGuardedPublication()
        val rows = (parsed["items"] as? List<*>)?.takeIf { it.size <= 20 }?.map { raw ->
            @Suppress("UNCHECKED_CAST") // strict JSON scanner yields only string-keyed maps and bounded fields here.
            val row = raw as? Map<String,Any> ?: invalidGuardedPublication()
            if (row.keys != setOf("draftId","title")) invalidGuardedPublication()
            val id = (row["draftId"] as? String)?.takeIf(uuid::matches) ?: invalidGuardedPublication()
            Candidate(id,row.field("title",400,true))
        } ?: invalidGuardedPublication()
        if (rows.map { it.id }.toSet().size != rows.size) invalidGuardedPublication()
        guard()
        Candidates(rows,truncated).also { current = it }
    }

    suspend fun review(candidate: Candidate): Review = locked {
        val guard = guard(candidate)
        guard()
        val frame = decodeGuardedNewLessonReview(call(path(candidate,"review"),LongUploadMethod.POST,null,guard),
            candidate.id,now())
        if (frame.details.title != candidate.title) invalidGuardedPublication()
        guard()
        Review(frame.details,frame.expiresAtMs,candidate,frame.ticket,engine)
    }

    suspend fun publish(review: Review): GuardedNewLessonReceipt = locked {
        val guard = guard(review.candidate,review.expiresAtMs)
        guard()
        val confirmation = review.use(engine)
        val body="""{"confirmation":"$confirmation"}""".encodeToByteArray()
        val response = try { parseFlatObject(call(path(review.candidate,"confirm"),LongUploadMethod.POST,body,guard)) }
            finally { body.fill(0) }
        if (response.keys != setOf("draftId","lessonId","created") || response["draftId"] != review.candidate.id ||
            response["lessonId"] != review.details.lessonId || response["created"] != true) invalidGuardedPublication()
        guard()
        current = null
        GuardedNewLessonReceipt(review.details.lessonId)
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        if (!mutex.tryLock()) invalidGuardedPublication()
        try { return block() } finally { mutex.unlock() }
    }

    private suspend fun guard(candidate: Candidate? = null, expiry: Long = Long.MAX_VALUE): () -> Unit {
        val scope = currentCoroutineContext()
        return {
            scope.ensureActive()
            if (!active() || now() >= expiry ||
                candidate != null && current?.rows?.any { it === candidate } != true) invalidGuardedPublication()
        }
    }

    private fun path(c:Candidate,action:String) = "${GUARDED_LESSON_ROOT}/by-draft/${c.id}/$action"

    private suspend fun call(path: String,method: LongUploadMethod,body: ByteArray?,guard: () -> Unit): ByteArray {
        val request = LongUploadRequest(method,path,"application/json".takeIf { body != null },body,GUARDED_LESSON_MAX_JSON)
        checkGuardedDiscoveryRequest(request)
        return readGuardedLessonResponse(transport.exchange(request,guard),guard)
    }
}
