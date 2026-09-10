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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
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
    val failed: Boolean = false,
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
        while (true) {
            emit(runCatching { fetchThreads() }.getOrDefault(emptyList()))
            delay(THREADS_POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun messages(threadId: String): Flow<List<SupportMessage>> {
        fun queued(): List<SupportMessage> = store.pending()
            .filter { it.threadId == threadId }
            .map {
                SupportMessage(
                    id = it.id,
                    fromOwner = false,
                    text = it.text,
                    audioPath = it.audioFile,
                    createdAtMs = it.createdAtMs,
                    pending = !it.failed,
                    failed = it.failed,
                )
            }
        val sent: Flow<List<SupportMessage>> = flow {
            if (threadId.isBlank()) {
                emit(emptyList())
                return@flow
            }
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
                        )
                    }
                }.getOrDefault(emptyList())
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

    fun enqueue(
        kind: String,
        threadId: String = newThreadId(),
        isNew: Boolean = true,
        text: String = "",
        audioFile: File? = null,
        includeDeviceInfo: Boolean = false,
    ) {
        store.addPending(
            kind = kind,
            threadId = threadId,
            isNew = isNew,
            text = text.trim().take(MAX_TEXT),
            audioFile = audioFile?.absolutePath.orEmpty(),
            deviceInfo = if (includeDeviceInfo) deviceInfo() else "",
        )
        schedule(appContext)
    }

    fun newThreadId(): String = "st_${System.currentTimeMillis()}_" +
        java.util.UUID.randomUUID().toString().take(6)

    fun deviceInfo(): String =
        "نسخة التطبيق ${BuildConfig.VERSION_NAME} · أندرويد ${Build.VERSION.RELEASE} · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"

    suspend fun deleteThread(threadId: String) {
        MinbarApi.deleteUser(appContext, "/v1/me/support/threads/$threadId")
    }

    fun markSeen(thread: SupportThread) = store.markSeen(thread.id, thread.lastMessageAtMs)

    fun retryFailed(messageId: String) {
        store.retryFailed(messageId)
        schedule(appContext)
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
        val audioKey = item.audioFile.takeIf { it.isNotBlank() }?.let { local ->
            val file = File(local)
            require(file.exists() && file.length() > 0L) { "المرفق مفقود." }
            MinbarApi.uploadUser(appContext, "support", "${item.id}.m4a", Uri.fromFile(file), "audio/mp4")
                .optString("key")
        }
        val fcmToken = runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        }.getOrDefault("")
        val payload = JSONObject().put("threadId", item.threadId)
        item.text.takeIf(String::isNotBlank)?.let { payload.put("text", it) }
        audioKey?.let { payload.put("audioKey", it) }
        if (fcmToken.isNotBlank()) payload.put("fcmToken", fcmToken)
        if (item.isNew) {
            payload.put("kind", item.kind).put("displayName", store.displayName())
            item.deviceInfo.takeIf(String::isNotBlank)?.let { payload.put("deviceInfo", it) }
            MinbarApi.postUser(appContext, "/v1/support/threads", payload)
        } else {
            MinbarApi.postUser(appContext, "/v1/support/threads/${item.threadId}/messages", payload)
        }
        runCatching { item.audioFile.takeIf { it.isNotBlank() }?.let { File(it).delete() } }
    }

    companion object {
        const val MAX_TEXT = 1_000
        const val NEW_THREAD_COOLDOWN_MS = 24L * 60 * 60 * 1000
        private const val WORK_NAME = "support_outbox"
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
    override suspend fun doWork(): Result {
        val store = SupportStore.get(applicationContext)
        val repository = SupportRepository.get(applicationContext)
        for (item in store.pending()) {
            val fresh = store.pending().firstOrNull { it.id == item.id } ?: continue
            if (fresh.failed) continue
            val outcome = runCatching { repository.deliver(fresh) }
            when {
                outcome.isSuccess -> store.removePending(fresh.id)
                isTransientFailure(outcome.exceptionOrNull()!!) -> return Result.retry()
                else -> store.markFailed(fresh.id)
            }
        }
        return Result.success()
    }
}
