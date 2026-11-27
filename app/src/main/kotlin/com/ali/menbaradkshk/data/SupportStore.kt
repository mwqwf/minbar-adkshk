package com.ali.menbaradkshk.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private val app = context.applicationContext
    private val recordings = SupportRecordingFiles(
        File(app.noBackupFilesDir, "support_outbox_voice"),
        File(app.cacheDir, "support_voice"),
    )
    private val prefs = app
        .getSharedPreferences("minbar_support", Context.MODE_PRIVATE)

    // ─── الهويّة والإقرار (يُسألان مرّة واحدة) ───────────────────

    /// «احذف بياناتي»: الاسم والإقرار والصندوق الصادر وعلامات القراءة كلّها —
    /// كان الصندوق ينجو فيُرسل رسالةً معلّقة بعد الحذف فتعود محادثةٌ باسمه.
    fun clearAll() {
        synchronized(outboxLock) {
            // Cloud has already erased this installation. Do not allow an older
            // in-process Worker to read or recreate its rows after a local disk error.
            committedOutbox.blockErasedRows()
            val cleared = prefs.edit().clear().commit()
            _outboxRevision.update { it + 1L }
            recordings.clear()
            check(cleared) { "Cannot clear local support drafts" }
        }
    }

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

    /// 🔒 دورة قراءة-تعديل-كتابة الطابور تُستدعى من خيط الواجهة (إضافة رسالة)
    /// ومن عامل الإرسال الخلفي معاً — بلا قفل كانت الكتابتان تتسابقان فتضيع
    /// رسالة أو تتكرّر (نمط `LocalStore.queueLock` نفسه).
    private val outboxLock = Any()
    private val committedOutbox = CommittedSupportOutbox(::readPersistedOutbox)

    /// 📣 يرتفع مع كل تغيّر في الطابور كي تلتقطه الواجهة فوراً (رسالة جديدة
    /// بلا إنترنت مثلاً) بدل انتظار ردّ مستمع Firestore الذي قد لا يأتي.
    private val _outboxRevision = MutableStateFlow(0L)
    val outboxRevision: StateFlow<Long> = _outboxRevision.asStateFlow()

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
        /// رفضها الخادم رفضاً قاطعاً — تبقى معروضة بزرّ إعادة محاولة بدل
        /// أن تُحذف بصمت، والعامل يتجاوزها حتى يعيدها المستخدم إلى الطابور.
        val failed: Boolean = false,
        val attachmentUnavailable: Boolean = false,
    )

    fun pending(): List<Pending> = synchronized(outboxLock) { committedOutbox.current() }

    private fun readPersistedOutbox(): List<Pending> {
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
                failed = o.optBoolean("failed"),
                attachmentUnavailable = o.optBoolean("attachmentUnavailable"),
            )
        }
    }

    fun addPending(
        kind: String,
        threadId: String,
        isNew: Boolean,
        text: String,
        audioFile: File?,
        deviceInfo: String,
    ): Pending = synchronized(outboxLock) {
        val id = UUID.randomUUID().toString()
        val durable = audioFile?.let { recordings.stage(it, id) }
        val item = Pending(
            id = id,
            kind = kind,
            threadId = threadId,
            isNew = isNew,
            text = text,
            audioFile = durable?.absolutePath.orEmpty(),
            deviceInfo = deviceInfo,
            createdAtMs = System.currentTimeMillis(),
        )
        // On a preference write error keep both copies for local recovery; the
        // worker can see only the previous saved rows, not a partially queued draft.
        save(pending() + item)
        runCatching { audioFile?.let { recordings.removeOwned(it, id) } }
        item
    }

    /** Existing cache-based rows are moved when a worker or the outbox opens. */
    fun migrateLegacyRecordings() = synchronized(outboxLock) {
        val rows = pending()
        val replacements = rows.mapNotNull { row ->
            val source = row.audioFile.takeIf(String::isNotBlank)?.let(::File) ?: return@mapNotNull null
            if (!recordings.ownedScratch(source) || !source.isFile) return@mapNotNull null
            val target = runCatching { recordings.stage(source, row.id) }.getOrNull() ?: return@mapNotNull null
            row.id to row.copy(audioFile = target.absolutePath)
        }.toMap()
        if (replacements.isNotEmpty()) {
            save(rows.map { replacements[it.id] ?: it })
            rows.filter { it.id in replacements }.forEach { row ->
                runCatching { recordings.removeOwned(File(row.audioFile), row.id) }
            }
        }
    }

    fun checkedRecording(item: Pending): File? {
        val file = item.audioFile.takeIf(String::isNotBlank)?.let(::File) ?: return null
        require(recordings.ownedDraft(file, item.id) || recordings.ownedScratch(file)) {
            "Invalid outbox recording"
        }
        return file
    }

    fun removePending(id: String) = removeMatching { it.id == id }

    fun discardFailed(id: String) = removeMatching(eraseFirst = true) {
        it.id == id && (it.failed || it.attachmentUnavailable)
    }

    fun discardThread(threadId: String) = removeMatching { it.threadId == threadId }

    private fun removeMatching(eraseFirst: Boolean = false, match: (Pending) -> Boolean) = synchronized(outboxLock) {
        val existing = pending()
        val removed = existing.filter(match)
        fun erase() = removed.forEach { row ->
            row.audioFile.takeIf(String::isNotBlank)?.let { recordings.removeOwned(File(it), row.id) }
        }
        if (removed.isNotEmpty()) {
            if (eraseFirst) erase() // failed local rows cannot send while being discarded
            save(existing.filterNot(match))
            if (!eraseFirst) erase()
        }
    }

    /// يعلّم الرسالة «فشلت» بدل حذفها — فتبقى ظاهرة بزرّ إعادة المحاولة.
    fun markFailed(id: String, attachmentUnavailable: Boolean = false) = synchronized(outboxLock) {
        save(pending().map { row ->
            if (row.id == id) row.copy(
                failed = true,
                attachmentUnavailable = row.attachmentUnavailable || attachmentUnavailable,
            ) else row
        })
    }

    /// «أعد المحاولة» للفشل العادي فقط؛ رفض المرفق يبقي المسودة محلية.
    fun retryFailed(id: String): Boolean = synchronized(outboxLock) {
        val rows = pending()
        val row = rows.firstOrNull { it.id == id } ?: return@synchronized false
        if (!canRetrySupportDraft(row.failed, row.attachmentUnavailable)) return@synchronized false
        save(rows.map { if (it.id == id) it.copy(failed = false) else it })
        true
    }

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
                    put("failed", item.failed)
                    put("attachmentUnavailable", item.attachmentUnavailable)
                },
            )
        }
        val previous = prefs.getString(KEY_OUTBOX, null)
        committedOutbox.replace(items) {
            val stored = prefs.edit().putString(KEY_OUTBOX, array.toString()).commit()
            if (!stored) prefs.edit().putString(KEY_OUTBOX, previous).apply()
            stored
        }
        _outboxRevision.update { it + 1L }
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
