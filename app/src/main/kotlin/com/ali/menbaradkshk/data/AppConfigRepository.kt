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
    private val db = FirebaseFirestore.getInstance()
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

    /** هل صُرف تذكير هذه النسخة تحديداً؟ (نسخة أحدث تُعيد إظهاره.) */
    fun isDismissed(latest: Int): Boolean = prefs.getInt(KEY_DISMISSED, 0) >= latest

    fun dismiss(latest: Int) {
        prefs.edit().putInt(KEY_DISMISSED, latest).apply()
    }

    private suspend fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED, 0L) < CHECK_INTERVAL_MS) return
        val doc = runCatching {
            db.collection("app_config").document("android").get(Source.CACHE).await()
                .takeIf { it.exists() }
                ?: db.collection("app_config").document("android").get().await()
        }.getOrNull() ?: return
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
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var instance: AppConfigRepository? = null

        fun get(context: Context): AppConfigRepository =
            instance ?: synchronized(this) {
                instance ?: AppConfigRepository(context).also { instance = it }
            }
    }
}
