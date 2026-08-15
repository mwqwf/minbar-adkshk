package com.ali.menbaradkshk.data

import android.content.Context
import com.ali.menbaradkshk.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * 🔔 تذكير التحديث.
 *
 * وثيقة واحدة (`app_config/android`) يضبطها المالك من اللوحة، ويقارنها
 * التطبيق برقم إصداره:
 *  - أقدم من `latestVersionCode` ⇒ تذكير لطيف يمكن صرفه.
 *  - أقدم من `minSupportedVersionCode` ⇒ تذكير أقوى يتكرّر كل تشغيل.
 *
 * **لماذا وثيقة على الخادم لا مكتبة تحديث من Play؟** الوزن والتحكّم:
 * لا تبعيّة جديدة في الحزمة، والنصّ عربيّ نكتبه نحن، وتعمل الآلية أيّاً كان
 * مصدر التثبيت. والكلفة الشبكيّة صفر عمليّاً: قراءة وثيقة واحدة صغيرة **مرّة
 * كل ست ساعات على الأكثر**، تُقرأ من الكاش أوّلاً وتُحفَظ محليّاً فتعمل
 * المقارنة بلا اتصال أصلاً.
 *
 * ولا تحجب الآلية التطبيق أبداً: الدروس المنزَّلة يجب أن تبقى قابلة للاستماع
 * بلا إنترنت ومهما قدُم الإصدار — التذكير دعوة لا بوّابة.
 */
class AppConfigRepository private constructor(context: Context) {

    private val app = context.applicationContext
    /// كسولٌ عمداً: المستودع يُنشَأ مع الـViewModel عند الإقلاع، بينما لا
    /// يُحتاج Firestore إلا عند قراءة شبكيّة فعليّة (مرّة كل ست ساعات على
    /// الأكثر). فبناؤه فوراً كان يوقظ Firestore بلا داعٍ في كل تشغيل —
    /// وكان يجعل المستودع غير قابل للاختبار أصلاً بلا Firebase مهيّأ.
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ما يُعرض للمستخدم — أو `None` حين لا شيء يُذكَر به. */
    sealed interface Status {
        data object None : Status

        /** نسخة أحدث متاحة — تذكير يمكن صرفه. */
        data class Optional(val latest: Int, val message: String, val storeUrl: String) : Status

        /** الإصدار دون الحدّ المدعوم — تذكير يتكرّر كل تشغيل. */
        data class Required(val latest: Int, val message: String, val storeUrl: String) : Status
    }

    /**
     * يقرأ الإعداد (بحدّ أدنى ست ساعات بين قراءتين) ثم يقارن.
     * أي فشل ⇒ آخر قيم محفوظة، وإلا `None` — التذكير لا يُزعج بلا يقين.
     */
    suspend fun status(): Status {
        refreshIfStale()
        val latest = prefs.getInt(KEY_LATEST, 0)
        val minSupported = prefs.getInt(KEY_MIN, 0)
        val message = prefs.getString(KEY_MESSAGE, "").orEmpty()
        val storeUrl = prefs.getString(KEY_STORE, "").orEmpty().ifBlank { PLAY_URL }
        val current = BuildConfig.VERSION_CODE
        return when {
            current < minSupported -> Status.Required(latest, message, storeUrl)
            current < latest -> Status.Optional(latest, message, storeUrl)
            else -> Status.None
        }
    }

    /**
     * هل نعرض شاشة التذكير الآن؟
     *
     * التوازن المقصود: التذكير يغطّي الشاشة كي **يُرى فعلاً** (أغلب الناس لا
     * ينظرون إلى الإشعارات)، لكنّه لا يتكرّر أكثر من مرّة كل ٢٤ ساعة للنسخة
     * الاختياريّة، ولا يعود بعد صرفه لتلك النسخة بعينها. أمّا حين يهبط
     * الإصدار دون الحدّ المدعوم فيظهر كل تشغيل — لأنّ الرسالة عندئذ ليست
     * «تتوفّر نسخة أحدث» بل «دعم نسختك يوشك أن يتوقّف».
     */
    fun shouldPrompt(status: Status): Boolean = when (status) {
        is Status.None -> false
        is Status.Required -> true
        is Status.Optional -> {
            val dismissed = prefs.getInt(KEY_DISMISSED, 0) >= status.latest
            val since = System.currentTimeMillis() - prefs.getLong(KEY_PROMPTED, 0L)
            !dismissed && since >= PROMPT_INTERVAL_MS
        }
    }

    fun markPrompted() {
        prefs.edit().putLong(KEY_PROMPTED, System.currentTimeMillis()).apply()
    }

    fun dismiss(latest: Int) {
        prefs.edit().putInt(KEY_DISMISSED, latest).apply()
    }

    /// هل يُرسَل إشعار التحديث اليوميّ؟ مرّة كل ٢٤ ساعة على الأكثر، ويسكت
    /// تماماً لنسخة صرف المستخدم شاشتَها (صرفُها إجابة صريحة: «ليس الآن»).
    fun shouldNotify(latest: Int): Boolean {
        if (prefs.getInt(KEY_DISMISSED, 0) >= latest) return false
        // إصدار أحدث ممّا أُشعِر به آخر مرّة ⇒ أشعِر فوراً بلا انتظار الخانق.
        if (prefs.getInt(KEY_NOTIFIED_FOR, 0) < latest) return true
        val since = System.currentTimeMillis() - prefs.getLong(KEY_NOTIFIED, 0L)
        return since >= PROMPT_INTERVAL_MS
    }

    fun markNotified(latest: Int) {
        prefs.edit()
            .putLong(KEY_NOTIFIED, System.currentTimeMillis())
            .putInt(KEY_NOTIFIED_FOR, latest)
            .apply()
    }

    // ---- 📣 التذكير عند فتح درس ----
    //
    // الطبقة الثالثة، وهي الأهم عملياً: أغلب مستخدمي التطبيق لا يقرؤون
    // الإشعارات، وكثير منهم لا يقرأ العربية جيّداً ولا يعرف من التقنية شيئاً.
    // فالمكان الوحيد المضمون أن يمرّ به كلّ مستخدم هو **فتح درس**. نعترض
    // هناك برسالة قصيرة جدّاً بزرّ واحد كبير، لا أكثر من **مرّتين في اليوم**،
    // ومع مخرج صريح لمن أراد السكوت.

    /** هل نعترض فتح الدرس الآن بتذكير التحديث؟ */
    fun shouldNudgeOnLesson(status: Status): Boolean {
        val latest = when (status) {
            is Status.Required -> status.latest
            is Status.Optional -> status.latest
            is Status.None -> return false
        }
        // «لا تُذكّرني مجدداً» يسكت هذه النسخة بعينها. صدور نسخة أحدث منها
        // يُعيد التذكير — وإلا لصار خيار الصمت قراراً أبديّاً يُجمّد المستخدم
        // على نسخة ميتة إلى الأبد.
        if (prefs.getInt(KEY_LESSON_MUTED, 0) >= latest) return false
        val now = System.currentTimeMillis()
        val day = now / DAY_MS
        val storedDay = prefs.getLong(KEY_LESSON_DAY, -1L)
        val shownToday = if (storedDay == day) prefs.getInt(KEY_LESSON_COUNT, 0) else 0
        if (shownToday >= MAX_LESSON_NUDGES_PER_DAY) return false
        // فجوة بين التذكيرين في اليوم نفسه: مرّتان متلاصقتان تُقرآن إزعاجاً.
        return now - prefs.getLong(KEY_LESSON_AT, 0L) >= LESSON_NUDGE_GAP_MS
    }

    fun markLessonNudged() {
        val now = System.currentTimeMillis()
        val day = now / DAY_MS
        val storedDay = prefs.getLong(KEY_LESSON_DAY, -1L)
        val shownToday = if (storedDay == day) prefs.getInt(KEY_LESSON_COUNT, 0) else 0
        prefs.edit()
            .putLong(KEY_LESSON_DAY, day)
            .putInt(KEY_LESSON_COUNT, shownToday + 1)
            .putLong(KEY_LESSON_AT, now)
            .apply()
    }

    /** «لا تُذكّرني مجدداً» — صمتٌ لهذه النسخة، يزول حين تصدر نسخة أحدث. */
    fun muteLessonNudge(latest: Int) {
        prefs.edit().putInt(KEY_LESSON_MUTED, latest).apply()
    }

    /// فحص فوريّ يتجاوز خانق الست ساعات — للفحص الدوريّ اليوميّ وللعودة
    /// إلى التطبيق بعد غياب: التذكير الذي يتأخّر ست ساعات عن الإصدار
    /// الجديد يفوّت أوّل يوم كامل من عمره.
    suspend fun statusForcingRefresh(): Status {
        prefs.edit().putLong(KEY_CHECKED, 0L).apply()
        return status()
    }

    private suspend fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED, 0L) < CHECK_INTERVAL_MS) return
        val reference = db.collection("app_config").document("android")
        // ⚠️ الخادم أوّلاً دائماً: تقديم الكاش كان يجمّد القيم على أوّل قراءة
        // إلى الأبد (الوثيقة تصير مخزَّنة فلا يُسأل الخادم بعدها قطّ، فلا يصل
        // تذكير أيّ إصدار لاحق). الكاش هنا خطّة بديلة عند فشل الشبكة فقط.
        val doc = runCatching { reference.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { reference.get(Source.CACHE).await() }
                .getOrNull()
                ?.takeIf { it.exists() }
            ?: run {
                // ختم قصير عند فشل الشبكة: بلاه كان كل ON_RESUME يعيد طرق
                // الخادم فوراً في حالات الانقطاع أو 5xx.
                prefs.edit()
                    .putLong(KEY_CHECKED, now - CHECK_INTERVAL_MS + FAILURE_RETRY_MS)
                    .apply()
                return
            }
        if (!doc.exists()) {
            // لا وثيقة إعداد ⇒ لا تذكير. نُثبّت الختم كي لا نسأل كل مرّة.
            prefs.edit().putLong(KEY_CHECKED, now).apply()
            return
        }
        val latest = (doc.getLong("latestVersionCode") ?: 0L).toInt()
        val minSupported = (doc.getLong("minSupportedVersionCode") ?: 0L).toInt()
        prefs.edit()
            .putInt(KEY_LATEST, latest)
            .putInt(KEY_MIN, minSupported)
            .putString(KEY_MESSAGE, doc.getString("message").orEmpty())
            .putString(KEY_STORE, doc.getString("storeUrl").orEmpty())
            .putLong(KEY_CHECKED, now)
            .apply()
    }

    companion object {
        /** معرّف حزمة نسخة المتجر — ثابت لا يتبع لاحقة `.dev` في التطوير. */
        const val STORE_PACKAGE = "com.ali.menbaradkshk"

        const val PLAY_URL =
            "https://play.google.com/store/apps/details?id=$STORE_PACKAGE"

        private const val PREFS = "minbar_app_config"
        private const val KEY_LATEST = "latest_version_code"
        private const val KEY_MIN = "min_supported_version_code"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STORE = "store_url"
        private const val KEY_CHECKED = "checked_at_ms"
        private const val KEY_DISMISSED = "dismissed_for"
        private const val KEY_PROMPTED = "prompted_at_ms"
        private const val KEY_NOTIFIED = "notified_at_ms"
        private const val KEY_NOTIFIED_FOR = "notified_for"
        private const val KEY_LESSON_DAY = "lesson_nudge_day"
        private const val KEY_LESSON_COUNT = "lesson_nudge_count"
        private const val KEY_LESSON_AT = "lesson_nudge_at_ms"
        private const val KEY_LESSON_MUTED = "lesson_nudge_muted_for"

        /// مرّتان في اليوم كحدّ أقصى — طلب صريح، والتوازن مقصود: كافٍ ليُرى،
        /// قليل بما لا يُنفّر من التطبيق نفسه.
        private const val MAX_LESSON_NUDGES_PER_DAY = 2
        private const val LESSON_NUDGE_GAP_MS = 4 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        /// لا تتكرّر شاشة التذكير الاختياريّة قبل يوم كامل.
        private const val PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /// مهلة إعادة المحاولة بعد فشل شبكة في جلب وثيقة التحديث.
        private const val FAILURE_RETRY_MS = 15 * 60 * 1000L

        @Volatile
        private var instance: AppConfigRepository? = null

        fun get(context: Context): AppConfigRepository =
            instance ?: synchronized(this) {
                instance ?: AppConfigRepository(context).also { instance = it }
            }
    }
}
