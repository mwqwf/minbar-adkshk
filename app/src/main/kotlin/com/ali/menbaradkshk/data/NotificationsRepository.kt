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
///
/// [hasContributedBefore] مؤشّر محلّي رخيص: من لم يساهم قطّ لا يُفتح له مستمع
/// «مساهماتي» أصلاً (مستمع كامل يسقط عن أغلبية المستخدمين). القيمة الافتراضية
/// تُبقي السلوك القديم كما هو لمن لا يمرّر المؤشّر.
class NotificationsRepository(
    private val submissions: SubmissionRepository,
    private val hasContributedBefore: () -> Boolean = { true },
    /// طابع أوّل تثبيت — يدخل في حدّ القصّ الخادمي أدناه. الافتراضي صفر
    /// يُبقي السلوك القديم (نافذة الثلاثين يوماً وحدها) لمن لا يمرّره.
    private val installedAtMs: () -> Long = { 0L },
) {
    private val db = FirebaseFirestore.getInstance()

    private companion object {
        const val TAG = "NotificationsRepo"

        /// نفس نافذة العرض في الواجهة (MoreScreens): آخر ٣٠ يوماً فقط.
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1_000
    }

    /// حدّ القصّ الزمني — كان يقع محليّاً فقط (الواجهة تُسقط الأقدم بعد
    /// جلبه)، فصار يقع في الاستعلام نفسه فلا تُقرأ وثائق لن تُعرض أبداً.
    /// الفلتر المحلي في الواجهة باقٍ كما هو احتياطاً، والنتيجة المعروضة
    /// مطابقة: الشرط على حقل الترتيب نفسه `createdAtMs` فلا فهرس جديد،
    /// والوثائق الخالية من الحقل كانت خارج `orderBy` أصلاً.
    private fun cutoffMs(): Long =
        maxOf(System.currentTimeMillis() - WINDOW_MS, installedAtMs())

    fun stream(limit: Long = 30): Flow<List<NotificationItem>> = callbackFlow {
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
            .whereGreaterThanOrEqualTo("createdAtMs", cutoffMs())
            .orderBy("createdAtMs", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                // خطأ دائم (مثل رفض القواعد) يُنهي المستمع بصمت، فتبقى الشاشة
                // فارغة بلا سبب ظاهر. تسجيله يجعل التشخيص ممكناً من logcat.
                if (error != null) Log.w(TAG, "تعذّرت قراءة الإشعارات العامة", error)
                // ⚠️ أي استثناء يفلت من ردّ مستمع Firestore يُسقط التطبيق كاملاً
                // (وثيقة بحقل مخالف النوع مثلاً) — فالردّ معزول وسقوطه آمن.
                runCatching {
                    publicItems = snapshot?.documents.orEmpty()
                        .mapNotNull { document ->
                            runCatching { fromDocument("public:${document.id}", document) }.getOrNull()
                        }
                    emit()
                }.onFailure { Log.w(TAG, "وثيقة إشعار عام معطوبة", it) }
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
                .whereGreaterThanOrEqualTo("createdAtMs", cutoffMs())
                .orderBy("createdAtMs", Query.Direction.DESCENDING)
                .limit(limit)
                .addSnapshotListener { snapshot, error ->
                    // كما في المستمع العام: الخطأ الدائم يُسجَّل لا يُبتلع.
                    if (error != null) Log.w(TAG, "تعذّرت قراءة إشعارات المستخدم", error)
                    // نفس العزل: استثناء يفلت من الردّ يُسقط التطبيق كاملاً.
                    runCatching {
                        privateItems = snapshot?.documents.orEmpty()
                            .mapNotNull { document ->
                                runCatching { fromDocument("private:${document.id}", document) }.getOrNull()
                            }
                        emit()
                    }.onFailure { Log.w(TAG, "وثيقة إشعار مستخدم معطوبة", it) }
                }
            // من لم يرسل مساهمة قطّ لا قرارات له أصلاً ⇒ لا مستمع ولا قراءة.
            if (!hasContributedBefore()) {
                emit()
                return@AuthStateListener
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

    private fun fromDocument(id: String, document: DocumentSnapshot): NotificationItem {
        // حمولة الإشعار كما أرسلها الخادم — الوثيقة تحفظها كاملةً في `data`،
        // وفيها وحدها الوجهة الصريحة التي يقرأها مستقبِل FCM.
        @Suppress("UNCHECKED_CAST")
        val payload = document.get("data") as? Map<String, Any?> ?: emptyMap()
        fun payloadString(key: String): String =
            (payload[key] as? String)?.trim().orEmpty()
        return NotificationItem(
        id = id,
        title = document.getString("title").orEmpty(),
        body = document.getString("body").orEmpty(),
        type = document.getString("type").orEmpty(),
        lessonId = payloadString("lessonId").ifBlank { document.getString("lessonId").orEmpty() },
        route = payloadString("route"),
        refId = document.getString("refId")
            ?: document.getString("lessonId")
            ?: "",
        createdAtMs = document.getLong("createdAtMs")
            ?: (document.get("createdAt") as? Timestamp)?.toDate()?.time
            ?: 0L,
        )
    }
}
