package com.ali.menbaradkshk.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 💬 حالة «راسِل المطوّر» المحفوظة على الجهاز.
 *
 * **لماذا ملفّ تفضيلات مستقلّ؟** الميزة مضافة حديثاً ولا تشارك أحداً بياناتها،
 * وخلطها بـ`LocalStore` (وهو ملفّ ضخم تقرؤه كل شاشة عند الإقلاع) يزيد وزن
 * الإقلاع بلا فائدة، ويجعل نسخة المستخدم الاحتياطيّة تحمل رسائله كذلك.
 *
 * وفيه أيضاً **طابور الإرسال**: الرسالة تُكتب هنا أوّلاً ثم تُرفع في الخلفية،
 * فلا تضيع بإغلاق التطبيق ولا بانقطاع الشبكة — وهو أهمّ ما في الميزة لجمهور
 * إنترنته ضعيف.
 */
class SupportStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("minbar_support", Context.MODE_PRIVATE)

    // ─── الهويّة والإقرار (يُسألان مرّة واحدة) ───────────────────

    /** الاسم الذي اختاره المستخدم، أو «مستخدم» إن تركه فارغاً. */
    fun displayName(): String = prefs.getString(KEY_NAME, "").orEmpty()
        .trim().ifBlank { DEFAULT_NAME }

    fun hasChosenName(): Boolean = prefs.contains(KEY_NAME)

    fun setDisplayName(name: String) {
        prefs.edit().putString(KEY_NAME, name.trim().take(40)).apply()
    }

    /** هل وافق على أنّ رسالته ليست مجهولة؟ لا يُرسَل شيء قبلها. */
    fun consented(): Boolean = prefs.getBoolean(KEY_CONSENT, false)

    fun setConsented() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
    }

    // ─── ما قُرئ من ردود المطوّر ─────────────────────────────────

    /// الخادم يرفع `userUnread`، لكنّه لا يُنزله إلا بفعلٍ من الخادم نفسه.
    /// نُسجّل محلياً آخر لحظة رآها المستخدم كي تختفي النقطة فور فتحه المحادثة
    /// ولو تأخّر الخادم — فلا يرى شارةً على شيء قرأه بعينه.
    fun lastSeenMs(threadId: String): Long = prefs.getLong("$KEY_SEEN$threadId", 0L)

    fun markSeen(threadId: String, atMs: Long) {
        if (atMs <= lastSeenMs(threadId)) return
        prefs.edit().putLong("$KEY_SEEN$threadId", atMs).apply()
    }

    // ─── الطابور ────────────────────────────────────────────────

    /** رسالة تنتظر الإرسال. */
    data class Pending(
        val id: String,
        val kind: String,
        /// معرّف المحادثة — **يولّده العميل** ليُرفع المرفق تحته قبل استدعاء
        /// الخادم (نفس نهج `createSubmission`): المسار `support/{uid}/{threadId}/…`
        /// تتحقّق منه قواعد التخزين فعلياً، فلا مجال لمجلّد مؤقّت.
        val threadId: String,
        /// أهي أوّل رسالة (تُنشئ المحادثة) أم ردّ داخل محادثة قائمة؟
        val isNew: Boolean,
        val text: String,
        val audioFile: String,
        val deviceInfo: String,
        val createdAtMs: Long,
    )

    fun pending(): List<Pending> {
        val raw = prefs.getString(KEY_OUTBOX, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            Pending(
                id = o.optString("id"),
                kind = o.optString("kind"),
                threadId = o.optString("threadId"),
                isNew = o.optBoolean("isNew"),
                text = o.optString("text"),
                audioFile = o.optString("audioFile"),
                deviceInfo = o.optString("deviceInfo"),
                createdAtMs = o.optLong("createdAtMs"),
            )
        }
    }

    fun addPending(
        kind: String,
        threadId: String,
        isNew: Boolean,
        text: String,
        audioFile: String,
        deviceInfo: String,
    ): Pending {
        val item = Pending(
            id = UUID.randomUUID().toString().take(10),
            kind = kind,
            threadId = threadId,
            isNew = isNew,
            text = text,
            audioFile = audioFile,
            deviceInfo = deviceInfo,
            createdAtMs = System.currentTimeMillis(),
        )
        save(pending() + item)
        return item
    }

    fun removePending(id: String) = save(pending().filterNot { it.id == id })

    private fun save(items: List<Pending>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("kind", item.kind)
                    put("threadId", item.threadId)
                    put("isNew", item.isNew)
                    put("text", item.text)
                    put("audioFile", item.audioFile)
                    put("deviceInfo", item.deviceInfo)
                    put("createdAtMs", item.createdAtMs)
                },
            )
        }
        prefs.edit().putString(KEY_OUTBOX, array.toString()).apply()
    }

    companion object {
        const val DEFAULT_NAME = "مستخدم"
        private const val KEY_NAME = "display_name"
        private const val KEY_CONSENT = "consented"
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_SEEN = "seen_"

        @Volatile private var instance: SupportStore? = null

        fun get(context: Context): SupportStore = instance ?: synchronized(this) {
            instance ?: SupportStore(context).also { instance = it }
        }
    }
}
