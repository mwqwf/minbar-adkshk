package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.menbaradkshk.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object SupportKind {
    const val SUGGESTION = "suggestion"
    const val BUG = "bug"
    const val LESSON_HELP = "lesson_help"
    const val IDEA = "idea"
    const val SUPERVISION = "supervision"
}

data class SupportThread(
    val id: String,
    val kind: String,
    val status: String,
    val lastMessageAtMs: Long,
    val createdAtMs: Long,
    val lastMessagePreview: String,
    val userUnread: Boolean,
    val ownerReplied: Boolean,
    val closed: Boolean,
    val blocked: Boolean,
    val messageCount: Int,
)

data class SupportMessage(
    val id: String,
    val fromOwner: Boolean,
    val text: String,
    val audioPath: String,
    val createdAtMs: Long,
    val pending: Boolean = false,
    // مفاتيح صور ردّ المطوّر في R2 (اللوحة تُرسل ردوداً بالصورة وحدها).
    val imagePaths: List<String> = emptyList(),
    val failed: Boolean = false,
    val attachmentUnavailable: Boolean = false,
)

/**
 * 💬 «راسِل المطوّر» — على `minbar-api` (قرار 2026-09-10): المحادثات موسومة
 * بمعرّف الجهاز، والمرفقات في R2 عبر الـWorker. القراءة استطلاعٌ خفيف (كل
 * نصف دقيقة للقائمة وكل ربع دقيقة للمحادثة المفتوحة) بدل مستمعي Firestore.
 * صندوق الصادر المحلّي [SupportStore] كما هو: الإرسال عبر WorkManager.
 */
class SupportRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = SupportStore.get(context)

    private fun threadOf(o: JSONObject): SupportThread = SupportThread(
        id = o.optString("id"),
        kind = o.optString("kind"),
        status = o.optString("status"),
        lastMessageAtMs = o.optLong("lastMessageAtMs").takeIf { it != 0L } ?: o.optLong("createdAtMs"),
        createdAtMs = o.optLong("createdAtMs"),
        lastMessagePreview = o.optString("lastMessagePreview"),
        userUnread = o.optBoolean("userUnread", false),
        ownerReplied = o.optBoolean("ownerReplied", false),
        closed = o.optBoolean("closed", false),
        blocked = o.optBoolean("blocked", false),
        messageCount = o.optInt("messageCount", 0),
    )

    private suspend fun fetchThreads(): List<SupportThread> {
        val items = MinbarApi.getUser(appContext, "/v1/me/support/threads").optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map(::threadOf)
            .sortedByDescending(SupportThread::lastMessageAtMs)
    }

    fun myThreads(): Flow<List<SupportThread>> = flow {
        var last = emptyList<SupportThread>()
        while (true) {
            // ⚠️ انقطاعٌ عابر لا يُفرغ القائمة: تبقى آخر نسخة ناجحة حتى الاستطلاع التالي.
            last = runCatching { fetchThreads() }.getOrDefault(last)
            emit(last)
            delay(THREADS_POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun newMessageOutbox(): Flow<List<SupportMessage>> = store.outboxRevision.map {
        outboxOperation.withLock { runCatching { store.migrateLegacyRecordings() } }
        store.pending().filter { it.isNew && canDeliverSupportOutboxKind(it.kind) }
            .sortedByDescending { it.createdAtMs }
            .map { localMessage(it) }
    }.flowOn(Dispatchers.IO)

    private fun localMessage(item: SupportStore.Pending) = SupportMessage(
        id = item.id,
        fromOwner = false,
        text = item.text,
        audioPath = item.audioFile,
        createdAtMs = item.createdAtMs,
        pending = canDispatchSupportDraft(item.failed, item.attachmentUnavailable),
        failed = item.failed || item.attachmentUnavailable,
        attachmentUnavailable = item.attachmentUnavailable,
    )

    fun messages(threadId: String): Flow<List<SupportMessage>> {
        fun queued(): List<SupportMessage> = store.pending().filter { it.threadId == threadId }.map { localMessage(it) }
        val sent: Flow<List<SupportMessage>> = flow {
            if (threadId.isBlank()) {
                emit(emptyList())
                return@flow
            }
            // ⚠️ كان فشلُ استطلاعٍ واحد يُصدر قائمةً فارغة فتختفي المحادثة كلّها
            // من الشاشة كل خمس عشرة ثانية على شبكةٍ متقطّعة؛ الآن تبقى آخر نسخة ناجحة.
            var last = emptyList<SupportMessage>()
            while (true) {
                val list = runCatching {
                    val items = MinbarApi.getUser(appContext, "/v1/support/threads/$threadId/messages")
                        .optJSONArray("items") ?: JSONArray()
                    (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { o ->
                        SupportMessage(
                            id = o.optString("id"),
                            fromOwner = o.optBoolean("fromOwner", false),
                            text = o.optString("text"),
                            audioPath = o.optString("audioPath"),
                            createdAtMs = o.optLong("createdAtMs"),
                            imagePaths = o.optJSONArray("imagePaths")?.let { paths ->
                                (0 until paths.length()).map { paths.optString(it) }
                                    .filter(String::isNotBlank)
                            }.orEmpty(),
                        )
                    }
                }.getOrDefault(last)
                last = list
                emit(list)
                delay(MESSAGES_POLL_MS)
            }
        }.flowOn(Dispatchers.IO)
        return combine(sent, store.outboxRevision) { uploaded, _ ->
            (uploaded + queued()).sortedBy(SupportMessage::createdAtMs)
        }
    }

    /** مرفق محلّي (مسار مطلق) أو مفتاح في R2 يُقرأ عبر minbar-api. */
    suspend fun attachmentUri(path: String): Uri {
        if (path.startsWith("/")) return Uri.fromFile(File(path))
        return Uri.parse(MinbarApi.mediaUrl(path))
    }

    suspend fun enqueue(
        kind: String,
        threadId: String = newThreadId(),
        isNew: Boolean = true,
        text: String = "",
        audioFile: File? = null,
        includeDeviceInfo: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        outboxOperation.withLock {
            check(store.consented()) // an erased installation must give consent again
            val body = text.trim().take(MAX_TEXT)
            require(body.isNotBlank() || audioFile != null)
            require(canDeliverSupportOutboxKind(kind))
            store.addPending(
                kind = kind,
                threadId = threadId,
                isNew = isNew,
                text = body,
                audioFile = audioFile,
                deviceInfo = if (includeDeviceInfo) deviceInfo() else "",
            )
            // This only schedules a queued draft; a scheduling exception never justifies
            // re-enqueueing another message with a fresh local identity.
            runCatching { schedule(appContext) }
        }
    }

    fun newThreadId(): String = "st_${System.currentTimeMillis()}_" +
        java.util.UUID.randomUUID().toString().take(6)

    fun deviceInfo(): String =
        "نسخة التطبيق ${BuildConfig.VERSION_NAME} · أندرويد ${Build.VERSION.RELEASE} · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"

    suspend fun deleteThread(threadId: String) = withContext(Dispatchers.IO) {
        outboxOperation.withLock {
            MinbarApi.deleteUser(appContext, "/v1/me/support/threads/$threadId")
            store.discardThread(threadId)
        }
    }

    suspend fun deleteAccountSupportData() = withContext(Dispatchers.IO) {
        outboxOperation.withLock {
            // Join any in-flight outbox attempt before erasing the old installation.
            // An already-dequeued worker cannot recreate its thread after this point.
            withContext(NonCancellable) {
                WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
                SubmissionRepository.get(appContext).deleteCloudIdentityData()
                store.clearAll()
                AppConfigRepository.get(appContext).resetInstallId()
            }
        }
    }

    fun markSeen(thread: SupportThread) = store.markSeen(thread.id, thread.lastMessageAtMs)

    suspend fun retryFailed(messageId: String) = withContext(Dispatchers.IO) {
        outboxOperation.withLock {
            if (store.retryFailed(messageId)) schedule(appContext)
        }
    }

    suspend fun discardFailed(messageId: String) = withContext(Dispatchers.IO) {
        outboxOperation.withLock { store.discardFailed(messageId) }
    }

    fun blockingThread(threads: List<SupportThread>): SupportThread? {
        val now = System.currentTimeMillis()
        return threads.firstOrNull { thread ->
            !thread.closed && (
                !thread.ownerReplied ||
                    now - thread.createdAtMs < NEW_THREAD_COOLDOWN_MS
                )
        }
    }

    fun isUnread(thread: SupportThread): Boolean =
        thread.userUnread && thread.lastMessageAtMs > store.lastSeenMs(thread.id)

    internal suspend fun deliver(item: SupportStore.Pending) {
        require(canDeliverSupportOutboxKind(item.kind))
        val deviceId = MinbarApi.deviceId(appContext)
        verifySupportMessageDelivery(
            audio = store.checkedRecording(item),
            deviceId = deviceId,
            filename = "${item.id}.m4a",
            newThreadId = item.threadId.takeIf { item.isNew },
            upload = { file, filename ->
                MinbarApi.uploadUser(appContext, "support", filename, Uri.fromFile(file), "audio/mp4").let {
                    SupportUploadReceipt(it.opt("ok"), it.opt("key"), it.opt("sha256"), it.opt("sizeBytes"))
                }
            },
            post = { audioKey ->
                val payload = JSONObject().put("threadId", item.threadId).put("fcmToken", "install:$deviceId")
                item.text.takeIf(String::isNotBlank)?.let { payload.put("text", it) }
                audioKey?.let { payload.put("audioKey", it) }
                val response = if (item.isNew) {
                    payload.put("kind", item.kind).put("displayName", store.displayName())
                    item.deviceInfo.takeIf(String::isNotBlank)?.let { payload.put("deviceInfo", it) }
                    MinbarApi.postUser(appContext, "/v1/support/threads", payload)
                } else {
                    MinbarApi.postUser(appContext, "/v1/support/threads/${item.threadId}/messages", payload)
                }
                SupportPostReceipt(response.opt("ok"), response.opt("id"))
            },
        )
    }

    companion object {
        const val MAX_TEXT = 1_000
        const val NEW_THREAD_COOLDOWN_MS = 24L * 60 * 60 * 1000
        private const val WORK_NAME = "support_outbox"
        internal val outboxOperation = Mutex()
        private const val THREADS_POLL_MS = 30_000L
        private const val MESSAGES_POLL_MS = 15_000L

        @Volatile private var instance: SupportRepository? = null
        fun get(context: Context): SupportRepository = instance ?: synchronized(this) {
            instance ?: SupportRepository(context).also { instance = it }
        }

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SupportSendWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

class SupportSendWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SupportRepository.outboxOperation.withLock { sendQueued() }
    }

    private suspend fun sendQueued(): Result {
        val store = SupportStore.get(applicationContext)
        val repository = SupportRepository.get(applicationContext)
        runCatching { store.migrateLegacyRecordings() }
        for (item in store.pending()) {
            val fresh = store.pending().firstOrNull { it.id == item.id } ?: continue
            // Historical lesson-related outbox items remain intact, but cannot upload or retry.
            if (!canDispatchSupportDraft(fresh.failed, fresh.attachmentUnavailable) ||
                !canDeliverSupportOutboxKind(fresh.kind)
            ) continue
            val failure = runCatching { repository.deliver(fresh) }.exceptionOrNull()
            when {
                failure == null -> store.removePending(fresh.id)
                failure is kotlinx.coroutines.CancellationException -> throw failure
                isTransientFailure(failure) -> return Result.retry()
                else -> {
                    val api = failure as? MinbarApi.ApiException
                    store.markFailed(
                        fresh.id,
                        attachmentUnavailable = isLegacySupportAttachmentCutoff(
                            fresh.audioFile, api?.code, api?.serverCode,
                        ),
                    )
                }
            }
        }
        return Result.success()
    }
}

internal fun canDeliverSupportOutboxKind(kind: String): Boolean =
    !kind.trim().equals(SupportKind.LESSON_HELP, ignoreCase = true)
