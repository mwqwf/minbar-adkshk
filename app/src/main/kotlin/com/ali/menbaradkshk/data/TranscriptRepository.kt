package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** النص المشروح المعتمد لدرس (المتن/المقطع الذي تشرحه الصوتية). */
data class LessonTranscript(
    val lessonId: String,
    val text: String,
    val bookTitle: String,
    val sourceRef: String,
    val imageUrls: List<String>,
    val contributorName: String,
)

/** مسودة اقتراح نص مشروح من المستمع. */
data class TranscriptDraft(
    val lessonId: String,
    val text: String,
    val bookTitle: String,
    val sourceRef: String,
    val note: String,
    val submitterName: String,
    val images: List<Uri>,
)

/** عنصر «مساهماتي» لاقتراح نص (نظير LessonSubmission للدروس الصوتية). */
data class TranscriptSubmissionItem(
    val id: String,
    val lessonId: String,
    val lessonTitle: String,
    val status: String,
    val rejectReason: String,
    val hasImages: Boolean,
    val createdAtMs: Long,
    val decidedAtMs: Long,
) {
    val isPending: Boolean get() = status == "pending"
}

/**
 * 📖 «النص المشروح»: جلب النص المعتمد للدرس عند فتح المشغّل فقط (وثيقة
 * واحدة، فلا يُثقل مزامنة الدروس)، وإرسال اقتراحات المستمعين (نص و/أو
 * صور صفحات الكتاب) إلى transcript_submissions بنفس دورة «شارك درساً».
 */
class TranscriptRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()

    // كاش جلسة بسيط: يمنع إعادة الجلب عند كل إعادة تركيب/عودة لنفس الدرس.
    private val cache = ConcurrentHashMap<String, Pair<Long, LessonTranscript?>>()

    /** النص المعتمد للدرس أو null. force=true بعد إرسال اقتراح مقبول مثلاً. */
    suspend fun fetch(lessonId: String, force: Boolean = false): LessonTranscript? {
        if (lessonId.isBlank()) return null
        val cached = cache[lessonId]
        if (!force && cached != null &&
            System.currentTimeMillis() - cached.first < CACHE_TTL_MS
        ) {
            return cached.second
        }
        val document = db.collection(TRANSCRIPTS).document(lessonId).get().await()
        val transcript = if (!document.exists()) {
            null
        } else {
            LessonTranscript(
                lessonId = lessonId,
                text = document.getString("text").orEmpty(),
                bookTitle = document.getString("bookTitle").orEmpty(),
                sourceRef = document.getString("sourceRef").orEmpty(),
                imageUrls = (document.get("images") as? List<*>).orEmpty()
                    .mapNotNull { item ->
                        (item as? Map<*, *>)?.get("url")?.toString()
                            ?.takeIf { it.isNotBlank() }
                    },
                contributorName = document.getString("contributorName").orEmpty(),
            )
        }
        cache[lessonId] = System.currentTimeMillis() to transcript
        return transcript
    }

    /**
     * إرسال اقتراح: يرفع الصور (إن وُجدت) إلى مجلد المساهمة ثم يستدعي
     * createTranscriptSubmission. يعيد معرّف المساهمة.
     */
    suspend fun submit(draft: TranscriptDraft, onProgress: (Int) -> Unit = {}): String {
        require(draft.lessonId.isNotBlank()) { "الدرس غير محدد." }
        require(
            draft.text.trim().length >= 10 || draft.images.isNotEmpty(),
        ) { "أدخل نص المقطع أو أرفق صورة صفحة واحدة على الأقل." }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        requireNotNull(user) { "تعذّر إنشاء الهوية الآمنة." }
        if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)

        val id = "tsub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val uploadedPaths = mutableListOf<String>()
        var callableStarted = false
        try {
            draft.images.take(MAX_IMAGES).forEachIndexed { index, uri ->
                val size = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?.use { it.length } ?: -1L
                require(size in 1..MAX_IMAGE_BYTES) {
                    "حجم الصورة ${index + 1} يتجاوز 10 ميجابايت."
                }
                val contentType = appContext.contentResolver.getType(uri) ?: "image/jpeg"
                require(contentType.startsWith("image/")) { "الملف المرفق ليس صورة." }
                val path = "transcript_submissions/${user.uid}/$id/${index}_page.jpg"
                storage.reference.child(path).putFile(
                    uri,
                    StorageMetadata.Builder().setContentType(contentType).build(),
                ).await()
                uploadedPaths.add(path)
                onProgress(((index + 1) * 100) / draft.images.size.coerceAtLeast(1))
            }
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrDefault("")
            } else {
                ""
            }
            val payload = mapOf(
                "submissionId" to id,
                "lessonId" to draft.lessonId,
                "text" to draft.text.trim(),
                "bookTitle" to draft.bookTitle.trim(),
                "sourceRef" to draft.sourceRef.trim(),
                "note" to draft.note.trim(),
                "submitterName" to draft.submitterName.trim(),
                "imagePaths" to uploadedPaths,
                "fcmToken" to fcmToken,
            )
            callableStarted = true
            val result = runCatching {
                functions.getHttpsCallable("createTranscriptSubmission").call(payload).await()
            }.getOrElse {
                functions.getHttpsCallable("createTranscriptSubmission").call(payload).await()
            }
            val returned = (result.data as? Map<*, *>)?.get("id")?.toString().orEmpty()
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            return returned
        } catch (failure: Throwable) {
            if (callableStarted) {
                val exists = runCatching {
                    db.collection(COLLECTION).document(id).get().await().exists()
                }.getOrDefault(false)
                if (exists) return id
            } else if (uploadedPaths.isNotEmpty()) {
                uploadedPaths.forEach { path ->
                    runCatching { storage.reference.child(path).delete().await() }
                }
            }
            throw failure
        }
    }

    fun mine(): Flow<List<TranscriptSubmissionItem>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = db.collection(COLLECTION)
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents.orEmpty().map { document ->
                    TranscriptSubmissionItem(
                        id = document.id,
                        lessonId = document.getString("lessonId").orEmpty(),
                        lessonTitle = document.getString("lessonTitle").orEmpty(),
                        status = document.getString("status").orEmpty().ifBlank { "pending" },
                        rejectReason = document.getString("rejectReason").orEmpty(),
                        hasImages = (document.get("imagePaths") as? List<*>)
                            .orEmpty().isNotEmpty(),
                        createdAtMs = document.getLong("createdAtMs")
                            ?: (document.get("createdAtTs") as? Timestamp)?.toDate()?.time
                            ?: 0L,
                        decidedAtMs = (document.get("decidedAtTs") as? Timestamp)?.toDate()?.time
                            ?: 0L,
                    )
                }.sortedByDescending(TranscriptSubmissionItem::createdAtMs)
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun deletePending(item: TranscriptSubmissionItem) {
        if (!item.isPending) return
        functions.getHttpsCallable("deleteMyTranscriptSubmission")
            .call(mapOf("submissionId" to item.id)).await()
    }

    /** تفريغ كاش درس (بعد اعتماد اقتراح مثلاً ليظهر النص فوراً). */
    fun invalidate(lessonId: String) {
        cache.remove(lessonId)
    }

    companion object {
        const val MAX_IMAGES = 4
        const val MAX_IMAGE_BYTES = 10L * 1_024L * 1_024L
        const val MAX_TEXT_CHARS = 20_000
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val COLLECTION = "transcript_submissions"
        private const val TRANSCRIPTS = "lesson_transcripts"
        @Volatile private var instance: TranscriptRepository? = null
        fun get(context: Context): TranscriptRepository = instance ?: synchronized(this) {
            instance ?: TranscriptRepository(context).also { instance = it }
        }
    }
}
