package com.ali.menbaradkshk.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/// مصدر الإشعارات المشترك — منقول من NotificationsFeed الأصلي:
/// مجموعة notifications العامة + user_notifications الخاصة +
/// قرارات «مساهماتي» المحسومة كإشعارات اصطناعية. الإخفاء المحلي يتم في الواجهة.
class NotificationsRepository(private val submissions: SubmissionRepository) {
    private val db = FirebaseFirestore.getInstance()

    private companion object {
        const val TAG = "NotificationsRepo"
    }

    fun stream(limit: Long = 50): Flow<List<NotificationItem>> = callbackFlow {
        var publicItems = listOf<NotificationItem>()
        var privateItems = listOf<NotificationItem>()
        var submissionItems = listOf<NotificationItem>()
        var privateRegistration: ListenerRegistration? = null
        var submissionsJob: Job? = null
        val scope = CoroutineScope(coroutineContext + Job())

        fun emit() {
            val items = (publicItems + privateItems + submissionItems)
                .sortedByDescending(NotificationItem::createdAtMs)
                .take(limit.toInt())
            trySend(items)
        }

        val publicRegistration = db.collection("notifications")
            .orderBy("createdAtMs", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                // خطأ دائم (مثل رفض القواعد) يُنهي المستمع بصمت، فتبقى الشاشة
                // فارغة بلا سبب ظاهر. تسجيله يجعل التشخيص ممكناً من logcat.
                if (error != null) Log.w(TAG, "تعذّرت قراءة الإشعارات العامة", error)
                publicItems = snapshot?.documents.orEmpty().map { fromDocument("public:${it.id}", it) }
                emit()
            }

        val auth = FirebaseAuth.getInstance()
        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            privateRegistration?.remove()
            submissionsJob?.cancel()
            privateItems = emptyList()
            submissionItems = emptyList()
            val user = firebaseAuth.currentUser
            if (user == null) {
                emit()
                return@AuthStateListener
            }
            privateRegistration = db.collection("user_notifications")
                .document(user.uid)
                .collection("items")
                .orderBy("createdAtMs", Query.Direction.DESCENDING)
                .limit(limit)
                .addSnapshotListener { snapshot, _ ->
                    privateItems = snapshot?.documents.orEmpty()
                        .map { fromDocument("private:${it.id}", it) }
                    emit()
                }
            submissionsJob = scope.launch {
                runCatching {
                    submissions.mine().collect { list ->
                        submissionItems = list
                            .filter { it.status != "pending" }
                            .mapNotNull(::decisionItem)
                        emit()
                    }
                }
            }
        }
        auth.addAuthStateListener(authListener)

        awaitClose {
            publicRegistration.remove()
            privateRegistration?.remove()
            submissionsJob?.cancel()
            auth.removeAuthStateListener(authListener)
            scope.cancel()
        }
    }

    /// يحوّل مساهمة محسومة إلى عنصر إشعار بنفس صياغات الأصل.
    private fun decisionItem(s: LessonSubmission): NotificationItem? {
        val (title, body) = when (s.status) {
            "approved" -> "نُشرت مساهمتك 🎉" to
                "وافق المشرفون على «${s.title}» ونُشرت كما هي. شكراً لمساهمتك!"
            "approved_edited" -> "نُشرت مساهمتك بعد تعديل 🎉" to
                "نُشرت «${s.title}» بعد تحسينها من المشرفين. شكراً لمساهمتك!"
            "rejected" -> "اعتذار عن نشر مساهمتك" to
                if (s.rejectReason.isEmpty()) "لم يوافق المشرفون على «${s.title}»."
                else "لم تُنشر «${s.title}»: ${s.rejectReason}"
            else -> return null
        }
        return NotificationItem(
            id = "subdec_${s.id}",
            title = title,
            body = body,
            type = "submission",
            refId = s.id,
            createdAtMs = s.decidedAtMs,
        )
    }

    private fun fromDocument(id: String, document: DocumentSnapshot) = NotificationItem(
        id = id,
        title = document.getString("title").orEmpty(),
        body = document.getString("body").orEmpty(),
        type = document.getString("type").orEmpty(),
        refId = document.getString("refId")
            ?: document.getString("lessonId")
            ?: "",
        createdAtMs = document.getLong("createdAtMs")
            ?: (document.get("createdAt") as? Timestamp)?.toDate()?.time
            ?: 0L,
    )
}
