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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

/** نوع المحادثة كما يعرفه الخادم. */
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
    /// `new` أو `user_replied` أو `answered` أو `closed`.
    val status: String,
    val lastMessageAtMs: Long,
    val createdAtMs: Long,
    val lastMessagePreview: String,
    val userUnread: Boolean,
    /// ⭐ الحقل الذي يُفتح به حقل الكتابة: الخادم يرفض رسالةً ثانية قبل ردّ
    /// المالك، فلا يُعرض للمستخدم ما سيُرفض عليه.
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
    /** رسالة لم تُرفع بعد — تنتظر عودة الإنترنت. */
    val pending: Boolean = false,
)

/**
 * 📮 قناة التواصل مع مالك المشروع وحده.
 *
 * **قاعدة الميزة كلّها:** المستخدم لا ينتظر الشبكة أبداً. كل ما يكتبه أو
 * يسجّله يُحفظ على الجهاز فوراً ويظهر له في المحادثة، ثم يرفعه عامل خلفيّ
 * حين تتوفّر شبكة — فلا يرى رسالة فشل، ولا يفقد تسجيلاً قضى فيه دقيقتين
 * لأنّ الإنترنت انقطع في منتصف الرفع أو أغلق التطبيق.
 */
class SupportRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = SupportStore.get(context)
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()

    // ─── قراءة ──────────────────────────────────────────────────

    /** محادثاتي مرتّبة بالأحدث. تعود فارغة قبل إنشاء الهوية المجهولة. */
    fun myThreads(): Flow<List<SupportThread>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        // بلا `orderBy` في الاستعلام: يحتاج فهرساً مركّباً مع `whereEqualTo`،
        // وعدد محادثات المستخدم الواحد صغير فالترتيب محلياً أرخص وأضمن.
        val registration = db.collection(COLLECTION)
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents.orEmpty().map { document ->
                        SupportThread(
                            id = document.id,
                            kind = document.getString("kind").orEmpty(),
                            status = document.getString("status").orEmpty(),
                            lastMessageAtMs = document.getLong("lastMessageAtMs")
                                ?: document.getLong("createdAtMs") ?: 0L,
                            createdAtMs = document.getLong("createdAtMs") ?: 0L,
                            lastMessagePreview = document.getString("lastMessagePreview").orEmpty(),
                            userUnread = document.getBoolean("userUnread") ?: false,
                            ownerReplied = document.getBoolean("ownerReplied") ?: false,
                            closed = document.getBoolean("closed") ?: false,
                            blocked = document.getBoolean("blocked") ?: false,
                            messageCount = (document.getLong("messageCount") ?: 0L).toInt(),
                        )
                    }.sortedByDescending(SupportThread::lastMessageAtMs),
                )
            }
        awaitClose { registration.remove() }
    }

    /**
     * رسائل محادثة واحدة: المرفوعة من الخادم، ويُلحق بها ما ينتظر الإرسال من
     * الطابور — كي يرى المستخدم رسالته في مكانها فور ضغطه «أرسل».
     */
    fun messages(threadId: String): Flow<List<SupportMessage>> = callbackFlow {
        fun queued(): List<SupportMessage> = store.pending()
            .filter { it.threadId == threadId }
            .map {
                SupportMessage(
                    id = it.id,
                    fromOwner = false,
                    text = it.text,
                    audioPath = it.audioFile,
                    createdAtMs = it.createdAtMs,
                    pending = true,
                )
            }
        if (threadId.isBlank()) {
            trySend(queued())
            awaitClose { }
            return@callbackFlow
        }
        val registration = db.collection(COLLECTION).document(threadId)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(queued())
                    return@addSnapshotListener
                }
                val sent = snapshot?.documents.orEmpty().map { document ->
                    SupportMessage(
                        id = document.id,
                        fromOwner = document.getBoolean("fromOwner") ?: false,
                        text = document.getString("text").orEmpty(),
                        audioPath = document.getString("audioPath").orEmpty(),
                        createdAtMs = document.getLong("createdAtMs") ?: 0L,
                    )
                }
                trySend((sent + queued()).sortedBy(SupportMessage::createdAtMs))
            }
        awaitClose { registration.remove() }
    }

    /** رابط تشغيل المرفق الصوتي من التخزين (أو الملف المحلّي إن كان معلّقاً). */
    suspend fun attachmentUri(path: String): Uri {
        if (path.startsWith("/")) return Uri.fromFile(File(path))
        return storage.reference.child(path).downloadUrl.await()
    }

    // ─── كتابة ──────────────────────────────────────────────────

    /**
     * يضع الرسالة في الطابور ويجدول رفعها. يعود فوراً — بلا انتظار شبكة.
     * [isNew] صحيحة ⇒ رسالة تُنشئ محادثة جديدة بمعرّف مولَّد هنا، وخاطئة ⇒
     * ردّ داخل محادثة قائمة يُمرَّر معرّفها.
     */
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
            // ⛔ حين يُطفئ المستخدم مفتاح معلومات الجهاز لا نرسل شيئاً عنه
            // إطلاقاً — لا صيغة مختصرة ولا حقلاً فارغاً باسمه.
            deviceInfo = if (includeDeviceInfo) deviceInfo() else "",
        )
        schedule(appContext)
    }

    /// معرّف المحادثة يُولَّد على الجهاز لا على الخادم: المرفقات تُرفع إلى
    /// `support/{uid}/{threadId}/…` **قبل** استدعاء الدالّة (نفس نهج
    /// `createSubmission`)، فلا بدّ من معرفة المعرّف قبل الرفع.
    fun newThreadId(): String = "st_${System.currentTimeMillis()}_" +
        java.util.UUID.randomUUID().toString().take(6)

    /** ما يُعرض للمستخدم مكشوفاً قبل إرساله مع بلاغ العطل. */
    fun deviceInfo(): String =
        "نسخة التطبيق ${BuildConfig.VERSION_NAME} · أندرويد ${Build.VERSION.RELEASE} · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"

    suspend fun deleteThread(threadId: String) {
        functions.getHttpsCallable("deleteMySupportThread")
            .call(mapOf("threadId" to threadId)).await()
    }

    fun markSeen(thread: SupportThread) = store.markSeen(thread.id, thread.lastMessageAtMs)

    /**
     * المحادثة التي تمنع فتح محادثة جديدة الآن (إن وُجدت): إمّا أنشئت خلال
     * ٢٤ ساعة، وإمّا ما تزال تنتظر ردّ المالك. الواجهة تفتحها للمستخدم بدل
     * أن تعرض له رفضاً من الخادم لا يملك له حيلة.
     */
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

    // ─── الرفع الفعلي (يستدعيه العامل الخلفي وحده) ───────────────

    internal suspend fun deliver(item: SupportStore.Pending) {
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        requireNotNull(user) { "تعذّر إنشاء الهوية الآمنة." }
        // ⛔ المسار إلزاميّ: قواعد التخزين تتحقّق من `support/{uid}/{threadId}/…`
        // فعلياً، فأيّ مجلّد آخر يُرفض ولا تصل الرسالة أبداً.
        val folder = "support/${user.uid}/${item.threadId}"
        val audioPath = item.audioFile.takeIf { it.isNotBlank() }?.let { local ->
            upload(File(local), "$folder/${item.id}.m4a", "audio/mp4")
        }
        // 🔔 بلا `fcmToken` لا يصل إشعار ردّ المالك إطلاقاً — فنُرسله مع كل
        // رسالة لا مع الإنشاء وحده: الرمز يتغيّر بإعادة التثبيت واستعادة
        // النسخة الاحتياطيّة، فتحديثه مجّاناً مع كل رسالة أضمن من رمز ميّت.
        val fcmToken = runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        }.getOrDefault("")
        val payload = buildMap<String, Any> {
            put("threadId", item.threadId)
            item.text.takeIf(String::isNotBlank)?.let { put("text", it) }
            audioPath?.let { put("audioPath", it) }
            if (fcmToken.isNotBlank()) put("fcmToken", fcmToken)
            if (item.isNew) {
                put("kind", item.kind)
                put("displayName", store.displayName())
                // إطفاء المفتاح = لا يُرسل الحقل إطلاقاً، لا فارغاً ولا مختصراً.
                item.deviceInfo.takeIf(String::isNotBlank)?.let { put("deviceInfo", it) }
            }
        }
        val callable = if (item.isNew) "createSupportThread" else "sendSupportMessage"
        functions.getHttpsCallable(callable).call(payload).await()
        // المرفقات المحلّية أدّت غرضها — لا نُبقيها في الكاش تأكل مساحة الجهاز.
        runCatching { item.audioFile.takeIf { it.isNotBlank() }?.let { File(it).delete() } }
    }

    private suspend fun upload(file: File, path: String, type: String): String {
        require(file.exists() && file.length() > 0L) { "المرفق مفقود." }
        storage.reference.child(path).putFile(
            Uri.fromFile(file),
            StorageMetadata.Builder().setContentType(type).build(),
        ).await()
        return path
    }

    companion object {
        const val MAX_TEXT = 1_000

        // ⛔ لا مرفقات صور في هذه القناة (قرار 2026-08-25): الصوت والكتابة
        // يكفيان، وحذفُ الصور من العميل هو ما أتاح إسقاط إذن READ_MEDIA_IMAGES
        // نهائياً. عقد الخادم يقبل `imagePaths` اختيارياً فلا يُكسر بعدم إرسالها.

        /// خيط واحد كل ٢٤ ساعة (حدّ الخادم) — الواجهة تمنع المحاولة أصلاً
        /// بدل أن تعرض رفضاً لا حيلة للمستخدم فيه.
        const val NEW_THREAD_COOLDOWN_MS = 24L * 60 * 60 * 1000
        private const val COLLECTION = "support_threads"
        private const val WORK_NAME = "support_outbox"

        @Volatile private var instance: SupportRepository? = null

        fun get(context: Context): SupportRepository = instance ?: synchronized(this) {
            instance ?: SupportRepository(context).also { instance = it }
        }

        /// `APPEND_OR_REPLACE` لا `KEEP`: مع KEEP تُسقَط الرسالة الثانية بصمت
        /// ما دام عملٌ سابق قائماً (أو مؤجَّلاً بمهلة تراجعيّة بعد انقطاع)،
        /// فتبقى في الطابور بلا موعد — وهي العلّة نفسها التي أصابت التنزيلات.
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

/**
 * 📤 عامل الإرسال: يفرغ الطابور رسالةً رسالةً بالترتيب.
 *
 * الفشل العابر (انقطاع/مهلة) ⇒ `retry` فتبقى الرسالة وتُرسَل عند عودة الشبكة.
 * والرفض القاطع من الخادم (رسالة قبل ردّ المالك مثلاً) ⇒ تُسقَط الرسالة:
 * إعادتها إلى الأبد تستهلك بطارية وبيانات المستخدم بلا أمل في النجاح.
 */
class SupportSendWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SupportStore.get(applicationContext)
        val repository = SupportRepository.get(applicationContext)
        for (item in store.pending()) {
            // الطابور يُقرأ من القرص في كل دورة، فقد يكون هذا العنصر التحق
            // بمحادثة أُنشئت في الدورة نفسها — نأخذ نسخته الحديثة.
            val fresh = store.pending().firstOrNull { it.id == item.id } ?: continue
            val outcome = runCatching { repository.deliver(fresh) }
            when {
                outcome.isSuccess -> store.removePending(fresh.id)
                isTransientFailure(outcome.exceptionOrNull()!!) -> return Result.retry()
                else -> store.removePending(fresh.id)
            }
        }
        return Result.success()
    }
}
