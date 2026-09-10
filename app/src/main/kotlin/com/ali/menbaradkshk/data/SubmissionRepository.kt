package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** الفشل العابر (شبكة/مهلة/5xx) يُعاد بعده الإرسال؛ ما عداه خطأ دائم. */
internal fun isTransientFailure(failure: Throwable): Boolean {
    val api = failure as? MinbarApi.ApiException ?: failure.cause as? MinbarApi.ApiException
    if (api != null) return api.code >= 500 || api.code == 429 || api.code == 408
    return generateSequence(failure) { it.cause }.take(5).any { it is java.io.IOException }
}

data class SubmissionDraft(
    val audioUri: Uri,
    val fileName: String,
    val title: String,
    val category: Category? = null,
    val subcategory: Subcategory? = null,
    val submitterName: String,
    val note: String,
    val rightsConfirmed: Boolean,
    val contentPolicyAccepted: Boolean,
    val transcript: TranscriptExtras = TranscriptExtras(),
)

/**
 * 🎁 «شارك درساً» — على `minbar-api` (قرار 2026-09-10): الملفّ يُرفع إلى R2 عبر
 * الـWorker، والسجلّ في D1 موسوماً بمعرّف الجهاز. «مساهماتي» استطلاعٌ خفيف
 * كل نصف دقيقة ما دامت الشاشة مفتوحة (بديل مستمع Firestore الحيّ).
 */
class SubmissionRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)

    suspend fun submit(
        draft: SubmissionDraft,
        onProgress: (Int) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        require(draft.title.isNotBlank()) { "أدخل عنوان الدرس." }
        val size = appContext.contentResolver.openAssetFileDescriptor(draft.audioUri, "r")
            ?.use { it.length }
            ?: -1L
        require(size >= 0) { "تعذّر قراءة الملف المحدّد — أعد اختياره." }
        require(size > 0L) { "الملف فارغ — أعد اختياره." }
        require(size <= MAX_FILE_BYTES) { "حجم الملف يتجاوز 100 ميجابايت." }
        val validatedTranscriptImages = draft.transcript.images
            .take(TranscriptRepository.MAX_IMAGES)
            .mapIndexed { index, imageUri ->
                val imageSize = appContext.contentResolver
                    .openAssetFileDescriptor(imageUri, "r")?.use { it.length } ?: -1L
                require(imageSize >= 0) { "تعذّرت قراءة صورة النص ${index + 1} — أعد اختيارها." }
                require(imageSize in 1..TranscriptRepository.MAX_IMAGE_BYTES) {
                    "حجم صورة النص ${index + 1} يتجاوز 10 ميجابايت."
                }
                val imageType = appContext.contentResolver.getType(imageUri) ?: "image/jpeg"
                require(imageType.startsWith("image/")) { "مرفق النص ليس صورة." }
                imageUri to imageType
            }
        if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)
        val id = "sub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val safeName = draft.fileName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)

        // 1) الصوت (نسبة التقدّم كلّها له — الصور صغيرة بعده).
        val uploaded = MinbarApi.uploadUser(
            appContext, "submissions", safeName, draft.audioUri, mimeFor(safeName), onProgress,
        )
        val audioKey = uploaded.optString("key")
        val imageKeys = mutableListOf<String>()
        try {
            validatedTranscriptImages.forEachIndexed { index, (imageUri, imageType) ->
                imageKeys += MinbarApi.uploadUser(
                    appContext, "transcripts", "lesson_${index}_page.jpg", imageUri, imageType,
                ).optString("key")
            }
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await() }
                    .getOrDefault("")
            } else {
                ""
            }
            val payload = JSONObject()
                .put("id", id)
                .put("submitterName", draft.submitterName.trim())
                .put("title", draft.title.trim())
                .put("categoryId", draft.category?.id.orEmpty())
                .put("categoryName", draft.category?.name.orEmpty())
                .put("subcategoryId", draft.subcategory?.id.orEmpty())
                .put("subcategoryName", draft.subcategory?.name.orEmpty())
                .put("note", draft.note.trim())
                .put("audioKey", audioKey)
                .put("fileName", safeName)
                .put("fileSize", size)
                .put("fcmToken", fcmToken)
                .put("rightsConfirmed", draft.rightsConfirmed)
                .put("contentPolicyAccepted", draft.contentPolicyAccepted)
                .put("contentPolicyVersion", CONTENT_POLICY_VERSION)
                .put("transcriptText", draft.transcript.text.trim())
                .put("transcriptBookTitle", draft.transcript.bookTitle.trim())
                .put("transcriptSourceRef", draft.transcript.sourceRef.trim())
                .put("transcriptImageKeys", JSONArray(imageKeys))
            val result = runCatching { MinbarApi.postUser(appContext, "/v1/submissions", payload) }
                .getOrElse { first ->
                    if (!isTransientFailure(first)) throw first
                    delay(1_500)
                    MinbarApi.postUser(appContext, "/v1/submissions", payload)
                }
            val returned = result.optString("id")
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            returned
        } catch (failure: Throwable) {
            // إن كانت المساهمة قد سُجّلت فعلاً فالإرسال ناجح رغم الاستثناء.
            val exists = runCatching { fetchMine().any { it.id == id } }.getOrDefault(false)
            if (exists) return@withContext id
            throw failure
        }
    }

    private suspend fun fetchMine(): List<LessonSubmission> {
        val items = MinbarApi.getUser(appContext, "/v1/me/submissions").optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { o ->
            LessonSubmission(
                id = o.optString("id"),
                title = o.optString("title"),
                categoryName = o.optString("categoryName"),
                subcategoryName = o.optString("subcategoryName"),
                status = o.optString("status").ifBlank { "pending" },
                rejectReason = o.optString("rejectReason"),
                storagePath = o.optString("storagePath"),
                createdAtMs = o.optLong("createdAtMs"),
                decidedAtMs = o.optLong("decidedAtMs"),
            )
        }.sortedByDescending(LessonSubmission::createdAtMs)
    }

    private val mineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mineShared: Flow<List<LessonSubmission>> by lazy {
        flow {
            while (true) {
                emit(runCatching { fetchMine() }.getOrDefault(emptyList()))
                delay(MINE_POLL_MS)
            }
        }
            .catch { emit(emptyList()) }
            .shareIn(mineScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    fun mine(): Flow<List<LessonSubmission>> = mineShared

    suspend fun updateMyTitle(
        submission: LessonSubmission,
        title: String,
        note: String? = null,
    ) {
        if (submission.status != "pending") return
        val trimmed = title.trim()
        require(trimmed.isNotBlank()) { "أدخل عنوان الدرس." }
        MinbarApi.putUser(
            appContext, "/v1/me/submissions/${submission.id}",
            JSONObject().put("title", trimmed).put("note", note?.trim().orEmpty()),
        )
    }

    suspend fun deletePending(submission: LessonSubmission) {
        if (submission.status != "pending") return
        MinbarApi.deleteUser(appContext, "/v1/me/submissions/${submission.id}")
    }

    /** حذف كل ما يخصّ هذا الجهاز على الخادم (مساهمات ورسائل ومرفقات). */
    suspend fun deleteCloudIdentityData() {
        MinbarApi.deleteUser(appContext, "/v1/me")
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "amr" -> "audio/amr"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    companion object {
        const val MAX_FILE_BYTES = 100L * 1_024L * 1_024L
        const val CONTENT_POLICY_VERSION = "2026-07-16"
        private const val MINE_POLL_MS = 30_000L

        @Volatile private var instance: SubmissionRepository? = null
        fun get(context: Context): SubmissionRepository = instance ?: synchronized(this) {
            instance ?: SubmissionRepository(context).also { instance = it }
        }
    }
}
