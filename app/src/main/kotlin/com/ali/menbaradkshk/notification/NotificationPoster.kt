package com.ali.menbaradkshk.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.R
import com.ali.menbaradkshk.data.TranscriptRepository
import com.ali.menbaradkshk.util.StoreRedirectActivity

/**
 * 🔔 ناشر الإشعارات المحلّية — المصدر الوحيد لبناء إشعارٍ وعرضه، ولتفسير
 * «حمولة ← وجهة».
 *
 * كان هذا المنطق موزَّعاً بين مستقبِل FCM (أُزيل مع Firebase كلّها في 2.7.0)
 * وناشرٍ خاصّ بالعمّال الدوريّين. الآن مصدرٌ واحد يستعمله العمّال
 * (التذكيرات، فحص التحديث، استطلاع الإشعارات) و`MainActivity` (نقر إشعارٍ
 * يحمل حمولته في extras).
 */
object NotificationPoster {

    /// معرّف إشعار التحديث — واحد في التطبيق كلّه: يتقاسمه `UpdateCheckWorker`
    /// واستطلاع الإشعارات كي يحلّ أحدهما محلّ الآخر لا أن يتكدّسا.
    const val UPDATE_NOTIFICATION_ID = 950

    /// إزاحة رموز الطلب لأزرار الإشعارات — بعيدة عن معرّفات الإشعارات كلّها.
    private const val PLAY_REQUEST_OFFSET = 90_000

    private const val LESSON_LINK_PREFIX = "https://minbar-adkassahk.vercel.app/lesson/"

    fun show(
        context: Context,
        id: Int,
        title: String,
        body: String,
        destination: String,
        channel: String,
        /// حين تكون الوجهة المتجر: نقفز إليه مباشرة عبر الوسيط الصامت بدل
        /// فتح التطبيق. الرابط لا يظهر للمستخدم في الحالتين.
        toStore: Boolean = false,
        /// ⏵ وجهة زرّ «استمع الآن»: رابط درسٍ يبدأ تشغيله فور فتحه.
        /// فارغة = بلا زرّ.
        playDestination: String = "",
        /// إشعارات الخادم (مساهماتك، ردود المطوّر، جديد الأقسام) ذات أولوية
        /// أعلى من التذكيرات اليوميّة — كما كانت في مسار FCM.
        highPriority: Boolean = false,
    ) {
        val intent = if (toStore) {
            StoreRedirectActivity.intent(context, destination)
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                if (destination.isNotBlank()) data = android.net.Uri.parse(destination)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_minbar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (highPriority) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        // ⏵ «استمع الآن» — ضغطةٌ واحدة لا اثنتان: النقر على الإشعار كان
        // يفتح التطبيق على صفحة الدرس ثم يبحث المستخدم عن زرّ التشغيل.
        //
        // ولماذا يفتح التطبيق ولا يشغّل من الخلفية مباشرة؟ لأن بدء خدمة
        // الوسائط والتطبيق في الخلفية ممنوع منذ أندرويد 8 (ويرمي استثناءً
        // في 12+) — وهي العلّة نفسها الموثَّقة في ودجت «الآن يُشغَّل».
        // فنسلك المسار القائم: رابط الدرس مع لحظة بدايةٍ صريحة، وشاشة
        // المشغّل تبدأ التشغيل من تلقائها حين تصلها اللحظة. النتيجة
        // للمستخدم واحدة: ضغطة واحدة ثم صوت.
        if (playDestination.isNotBlank()) {
            val playIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(playDestination)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            builder.addAction(
                0,
                "▶ استمع الآن",
                PendingIntent.getActivity(
                    context,
                    // رمز طلبٍ مستقلّ عن نقرة الإشعار نفسها، وإلّا داس
                    // أحدهما الآخر (نفس السياق ونفس الرمز = نفس المُعلَّق).
                    id + PLAY_REQUEST_OFFSET,
                    playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val notification = builder.build()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * هل هذه حمولة «صدر إصدار جديد»؟ وجهتها المتجر لا التطبيق.
     *
     * نقبل `type=update` (ما يكتبه الخادم في الخلاصة) وكذلك أي حمولة يدويّة
     * تحمل `route=store`.
     */
    fun isUpdate(data: Map<String, String>): Boolean =
        data["type"]?.trim() == "update" || data["route"]?.trim() == "store"

    /**
     * ⚠️ بشرى «اعتُمد نصّك» تُفرغ كاش النصّ المشروح لذلك الدرس.
     *
     * الكاش يخزّن النتيجة **الفارغة** أربعاً وعشرين ساعة (في الذاكرة
     * والقرص معاً)، فكان نقر الإشعار يفتح الدرس فيقرأ فراغَ الأمس ولا
     * يظهر النص إلا في اليوم التالي. يُستدعى من مسارَي الإشعار كليهما:
     * عامل الاستطلاع (لحظة الوصول) و`MainActivity` (لحظة النقر).
     */
    fun invalidateTranscriptCache(context: Context, data: Map<String, String>) {
        if (data["type"]?.trim() != "transcript") return
        val lessonId = (data["lessonId"] ?: data["lesson_id"])?.trim().orEmpty()
        if (lessonId.isEmpty()) return
        runCatching { TranscriptRepository.get(context).invalidate(lessonId) }
    }

    /**
     * «الحمولة ← وجهة» — مصدر الحقيقة الوحيد للتوجيه، يستعمله عامل
     * الاستطلاع و`MainActivity` معاً.
     *
     * التوجيه بحسب `type` حصراً: قراءة `id` بلا فحص النوع كانت تفتح
     * `lesson/<معرّف مساهمة>` = شاشة ميتة.
     * - `lesson` ⇒ الدرس
     * - `subcategory` / `category` ⇒ القسم الفرعي/الرئيسي
     * - `book` ⇒ لا وجهة (الكتب أُزيلت عمداً من التطبيق)
     * - `manual` ⇒ لا وجهة (فتح التطبيق فقط)
     * - `submission` / `transcript` ⇒ «مساهماتي»، إلا أن تحمل `lessonId`
     *   (فرع الاعتماد يرسله) فتُفتح صفحة الدرس المنشور.
     */
    fun destinationFor(data: Map<String, String>): String? {
        fun value(vararg keys: String): String? = keys.asSequence()
            .map { data[it] }
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val lessonId = value("lessonId", "lesson_id")
        val refId = value("id", "refId")

        // الخادم يرسل `route` صريحاً لإشعارات المساهمات، وهو الحَكَم:
        // حمولة **رفض** النص المشروح تحمل lessonId (لتعريف الدرس) لكن
        // وجهتها «مساهماتي» لا صفحة الدرس — فالاستنتاج من lessonId وحده
        // يعيد الخطأ الذي أُصلح.
        when (value("route")) {
            "my-submissions" -> return "minbar://my-submissions"
            "lesson" -> lessonId?.let { return LESSON_LINK_PREFIX + it }
            "downloads" -> return "minbar://downloads"
        }

        return when (data["type"]?.trim().orEmpty()) {
            "submission", "transcript" ->
                lessonId?.let { LESSON_LINK_PREFIX + it } ?: "minbar://my-submissions"
            "lesson" -> (lessonId ?: refId)?.let { LESSON_LINK_PREFIX + it }
            "subcategory" -> value("subcategoryId", "subId", "id")
                ?.let { "minbar://subcategory/$it" }
            "category" -> value("categoryId", "id")
                ?.let { "minbar://category/$it" }
            // الكتب أُزيلت من التطبيق، والإشعار اليدوي بلا هدف أصلاً.
            "book", "manual" -> null
            // نوع غير معروف: نفتح درساً فقط إن صرّحت الحمولة بمعرّف درس،
            // ولا نجازف بتفسير `id` عاماً (قد يكون معرّف مساهمة أو كتاب).
            else -> lessonId?.let { LESSON_LINK_PREFIX + it }
        }
    }
}
