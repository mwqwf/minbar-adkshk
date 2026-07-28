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

data class SubmissionDraft(
    val audioUri: Uri,
    val fileName: String,
    val title: String,
    val category: Category,
    val subcategory: Subcategory,
    val submitterName: String,
    val note: String,
    val rightsConfirmed: Boolean,
    val contentPolicyAccepted: Boolean,
)

class SubmissionRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()

    suspend fun submit(
        draft: SubmissionDraft,
        onProgress: (Int) -> Unit = {},
    ): String {
        require(draft.rightsConfirmed && draft.contentPolicyAccepted) {
            "يجب تأكيد الحقوق وسياسة المحتوى."
        }
        require(draft.title.isNotBlank()) { "أدخل عنوان الدرس." }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        requireNotNull(user) { "تعذّر إنشاء الهوية الآمنة." }

        val size = appContext.contentResolver.openAssetFileDescriptor(draft.audioUri, "r")
            ?.use { it.length }
            ?: -1L
        require(size in 0..MAX_FILE_BYTES) { "حجم الملف يتجاوز 100 ميجابايت." }
        if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)

        val id = "sub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val safeName = draft.fileName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
        val storagePath = "submissions/${user.uid}/$id/$safeName"
        val reference = storage.reference.child(storagePath)
        val task = reference.putFile(
            draft.audioUri,
            StorageMetadata.Builder().setContentType(mimeFor(safeName)).build(),
        )
        task.addOnProgressListener { snapshot ->
            if (snapshot.totalByteCount > 0L) {
                onProgress(((snapshot.bytesTransferred * 100L) / snapshot.totalByteCount).toInt())
            }
        }

        var uploaded = false
        var callableStarted = false
        try {
            task.await()
            uploaded = true
            val audioUrl = reference.downloadUrl.await().toString()
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrDefault("")
            } else {
                ""
            }
            val payload = mapOf(
                "id" to id,
                "uid" to user.uid,
                "submitterName" to draft.submitterName.trim(),
                "title" to draft.title.trim(),
                "categoryId" to draft.category.id,
                "categoryName" to draft.category.name,
                "subcategoryId" to draft.subcategory.id,
                "subcategoryName" to draft.subcategory.name,
                "note" to draft.note.trim(),
                "audioUrl" to audioUrl,
                "storagePath" to storagePath,
                "fileName" to safeName,
                "fileSize" to size,
                "fcmToken" to fcmToken,
                "rightsConfirmed" to true,
                "contentPolicyVersion" to CONTENT_POLICY_VERSION,
                "termsAcceptedAt" to java.time.Instant.now().toString(),
            )
            callableStarted = true
            val result = runCatching {
                functions.getHttpsCallable("createSubmission").call(payload).await()
            }.getOrElse {
                functions.getHttpsCallable("createSubmission").call(payload).await()
            }
            val returned = (result.data as? Map<*, *>)?.get("id")?.toString().orEmpty()
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            return returned
        } catch (failure: Throwable) {
            if (callableStarted) {
                val exists = runCatching {
                    val document = db.collection(COLLECTION).document(id).get().await()
                    document.exists() && document.getString("storagePath") == storagePath
                }.getOrDefault(false)
                if (exists) return id
            } else if (uploaded) {
                runCatching { reference.delete().await() }
            }
            throw failure
        }
    }

    fun mine(): Flow<List<LessonSubmission>> = callbackFlow {
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
                    LessonSubmission(
                        id = document.id,
                        title = document.getString("title").orEmpty(),
                        categoryName = document.getString("categoryName").orEmpty(),
                        subcategoryName = document.getString("subcategoryName").orEmpty(),
                        status = document.getString("status").orEmpty().ifBlank { "pending" },
                        rejectReason = document.getString("rejectReason").orEmpty(),
                        storagePath = document.getString("storagePath").orEmpty(),
                        // الخادم يكتب createdAt نصاً ISO مع createdAtTs/createdAtMs — نقرأ المتاح.
                        createdAtMs = document.getLong("createdAtMs")
                            ?: (document.get("createdAtTs") as? Timestamp)?.toDate()?.time
                            ?: document.getString("createdAt")
                                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                            ?: 0L,
                        decidedAtMs = (document.get("decidedAtTs") as? Timestamp)?.toDate()?.time
                            ?: document.getString("decidedAt")
                                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                            ?: 0L,
                    )
                }.sortedByDescending(LessonSubmission::createdAtMs)
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun deletePending(submission: LessonSubmission) {
        if (submission.status != "pending") return
        functions.getHttpsCallable("deleteMySubmission")
            .call(mapOf("submissionId" to submission.id)).await()
    }

    suspend fun deleteCloudIdentityData() {
        if (auth.currentUser == null) return
        functions.getHttpsCallable("deleteMyData").call().await()
        auth.signOut()
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
        private const val COLLECTION = "lesson_submissions"
        @Volatile private var instance: SubmissionRepository? = null
        fun get(context: Context): SubmissionRepository = instance ?: synchronized(this) {
            instance ?: SubmissionRepository(context).also { instance = it }
        }
    }
}
