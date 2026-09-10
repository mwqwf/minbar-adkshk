package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import com.ali.menbaradkshk.util.normalizeArabic
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

// كل ما ليس حرفاً عربياً أو لاتينياً أو رقماً فاصلٌ بين الكلمات:
// المدى الأول همزة→غين والثاني فاء→ياء (وبينهما التطويل، وقد حُذف).
private val transcriptWordSplit = Regex("[^\\u0621-\\u063A\\u0641-\\u064Aa-z0-9]+")

// أدوات التعريف الملتصقة — تُقشَّر ليجد من كتب «تيمم» درساً ورد فيه
// «بالتيمم»، ومن كتب «التيمم» درساً ورد فيه «تيمم».
private val transcriptWordPrefixes = listOf("وال", "فال", "بال", "كال", "لل", "ال")

/**
 * 🔤 كلمات البحث في المتون.
 *
 * ⚠️ نسخة حرفيّة من `transcriptIndexKeywords` في ملف الدوال السحابيّة:
 * تطبيع عربيّ واحد، ثم تقطيع على القاعدة نفسها، ثم قشر أداة التعريف ما
 * دام الباقي كلمةً معتبرة. أيّ تغيير هنا بلا نظيره هناك يجعل ما يُسأل
 * عنه مخالفاً لما كُتب في الفهرس، فلا يُطابَق شيء أبداً.
 */
fun transcriptSearchWords(query: String): List<String> = normalizeArabic(query)
    .split(transcriptWordSplit)
    .filter { it.length >= TranscriptRepository.MIN_SEARCH_KEYWORD }
    .map { word ->
        val prefix = transcriptWordPrefixes.firstOrNull { candidate ->
            word.startsWith(candidate) &&
                word.length - candidate.length >= TranscriptRepository.MIN_SEARCH_KEYWORD
        }
        if (prefix == null) word else word.substring(prefix.length)
    }
    .distinct()

/**
 * كلماتٌ تكاد لا تخلو منها صفحةٌ من متون الدروس الشرعيّة — بصورتها المطبَّعة
 * المقشورة كما تدخل الفهرس («النبي» تُفهرس «نبي» مثلاً). المرساةُ بها تعيد
 * نافذةً شبه عشوائيّة تطابق كل شيء، فتُنحَّى ما وُجد سواها.
 */
private val transcriptCommonWords = setOf(
    "الله", "قال", "قالت", "يقول", "كان", "كانت", "رسول", "نبي", "صلي",
    "وسلم", "سلم", "عليه", "عليها", "تعالي", "الذي", "ذين", "التي",
    "هذا", "هذه", "ذلك", "ابن", "شيخ", "حديث", "كتاب", "باب",
)

/**
 * مرساة الاستعلام: الكلمة الوحيدة التي يُسأل الفهرس عنها (Firestore لا
 * يقبل إلا `array-contains` واحداً)، فالأدلّ على المطلوب هي **الأندر**.
 * ولا إحصاء شيوعٍ عندنا بلا كلفة، فنقرّبها بأمرين: تنحية الشائع المعروف
 * أعلاه، ثم أطول ما بقي — فالطول قرينة الندرة. وإن لم يبقَ غير الشائع
 * فأطوله خيرٌ من لا مرساة.
 */
fun transcriptSearchAnchor(words: List<String>): String? =
    (words.filterNot { it in transcriptCommonWords }.ifEmpty { words })
        .maxByOrNull(String::length)

/** مرفق «النص المشروح» الاختياري داخل مساهمة درس صوتي («شارك درساً»). */
data class TranscriptExtras(
    val text: String = "",
    val bookTitle: String = "",
    val sourceRef: String = "",
    val images: List<Uri> = emptyList(),
) {
    val isEmpty: Boolean get() = text.trim().length < 10 && images.isEmpty()
}

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

    // كاش جلسة بسيط: يمنع إعادة الجلب عند كل إعادة تركيب/عودة لنفس الدرس.
    // وهو الطبقة الأولى فوق كاش القرص أدناه لا بديلاً عنه.
    private val cache = ConcurrentHashMap<String, Pair<Long, LessonTranscript?>>()

    // 💾 كاش قرصي مستقلّ بالمستودع: الدرس المنزَّل كان يعمل بلا نت ونصّه لا،
    // لأن الكاش كان في الذاكرة فقط ويضيع بموت العملية — فيظهر «جارٍ التحميل»
    // ثم دعوة المساهمة كأن الدرس بلا نص أصلاً.
    //
    // ⚠️ **ملفّ لكل درس** لا `SharedPreferences` واحد.
    //
    // كان الكاش كلّه في تفضيلاتٍ واحدة: مئتا مدخل × عشرين ألف حرف ≈ أربعة
    // ميغابايت. و`SharedPreferences` تُحمّل ملفّها **كاملاً في الذاكرة** وتبقيه
    // مقيماً طول عمر العمليّة، وكلّ `apply()` يُعيد كتابة الملفّ كلّه على
    // القرص، و`pruneDisk` كان ينسخ `all` (الأربعة ميغابايت) مع كل كتابة. أي
    // أربعة ميغابايت مهدورة دائماً على أجهزةٍ ذاكرتها ضيّقة، وكتابةُ ملفٍّ
    // كامل لأجل مدخلٍ واحد.
    //
    // والملفّات تحت `cacheDir` لا `filesDir`: هذا كاشٌ يصحّ للنظام أن يمحوه
    // عند ضيق المساحة — وهو ما يوافق قاعدة «لا يضرّ جهاز المستخدم».
    private val diskDir = File(appContext.cacheDir, CACHE_DIR)

    init {
        // ترحيل الكاش القديم = حذفه: محتواه يُعاد جلبه عند أوّل فتح، ولا
        // يستحقّ نصٌّ مؤقَّت شيفرةَ ترحيلٍ تبقى إلى الأبد.
        runCatching {
            File(File(appContext.applicationInfo.dataDir, "shared_prefs"), "$CACHE_FILE.xml")
                .delete()
        }
    }

    // نتائج فهرس البحث لكلمة واحدة، في الذاكرة فقط: البحث زائرٌ عابر ولا
    // يستحق قرصاً، لكن حذف حرفٍ وإعادته لا يصحّ أن يُعيد الاستعلام.
    private val searchCache = ConcurrentHashMap<String, List<String>>()

    /** النص المعتمد للدرس أو null. force=true بعد إرسال اقتراح مقبول مثلاً. */
    suspend fun fetch(lessonId: String, force: Boolean = false): LessonTranscript? {
        if (lessonId.isBlank()) return null
        // ⚠️ تُستدعى من LaunchedEffect أي على مُرسِل الواجهة: قراءة القرص
        // وتحليل JSON لنصٍّ قد يبلغ 20 ألف حرف كانا يقعان على الخيط الرئيسي.
        return withContext(Dispatchers.IO) {
            if (!force) {
                cache[lessonId]?.let { memory ->
                    if (isFresh(memory)) return@withContext memory.second
                }
                readDisk(lessonId)?.let { disk ->
                    if (isFresh(disk)) {
                        cache[lessonId] = disk
                        return@withContext disk.second
                    }
                }
            }
            val transcript = try {
                // النص من `minbar-api` (`/v1/transcripts/{id}`): 404 = لا نصّ لهذا الدرس.
                val document = MinbarApi.transcript(lessonId)
                if (document == null) {
                    null
                } else {
                    val images = document.optJSONArray("images")
                    LessonTranscript(
                        lessonId = lessonId,
                        text = document.optString("text"),
                        bookTitle = document.optString("bookTitle"),
                        sourceRef = document.optString("sourceRef"),
                        imageUrls = (0 until (images?.length() ?: 0))
                            .mapNotNull { index ->
                                val item = images?.opt(index)
                                (if (item is org.json.JSONObject) item.optString("url") else item?.toString())
                                    ?.takeIf { it.isNotBlank() }
                            },
                        contributorName = document.optString("contributorName"),
                    )
                }
            } catch (failure: Throwable) {
                // بلا اتصال: آخر نسخة محفوظة — ولو انتهت صلاحيتها — خيرٌ من لا شيء.
                // وإن لم تكن هناك نسخة أصلاً لا نبتلع الفشل، كي تميّز الواجهة بين
                // «لا نص لهذا الدرس» و«لم يُجلب بعد».
                val stale = cache[lessonId] ?: readDisk(lessonId)?.also { cache[lessonId] = it }
                if (stale != null) return@withContext stale.second
                throw failure
            }
            val now = System.currentTimeMillis()
            cache[lessonId] = now to transcript
            writeDisk(lessonId, transcript, now)
            transcript
        }
    }

    /**
     * صلاحية المدخل: أسبوع للنص الموجود، ويوم واحد للنتيجة الفارغة كي يظهر
     * نصٌّ اعتُمد حديثاً في وقت معقول. (تخزين النتيجة الفارغة مقصود: الدرس
     * الذي لا نص له لا يُعاد استعلامه عند كل فتح للمشغّل.)
     */
    private fun isFresh(entry: Pair<Long, LessonTranscript?>): Boolean {
        val age = System.currentTimeMillis() - entry.first
        if (age < 0L) return false
        return age < if (entry.second == null) EMPTY_TTL_MS else TEXT_TTL_MS
    }

    /// اسم ملفّ آمن ومستقرّ للمعرّف (المعرّفات قد تحمل ما لا يصلح في اسم ملفّ).
    private fun entryFile(lessonId: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(lessonId.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return File(diskDir, "$digest.json")
    }

    /** قراءة مدخل القرص كما هو (بلا فحص صلاحية) أو null إن غاب أو تلف. */
    private fun readDisk(lessonId: String): Pair<Long, LessonTranscript?>? {
        val file = entryFile(lessonId)
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // الختم داخل الملفّ نفسه: مدخلٌ واحد = ملفٌّ واحد، فلا مفتاحان يفترقان.
        val savedAtMs = json.optLong("savedAtMs", 0L)
        if (savedAtMs <= 0L) return null
        if (!json.optBoolean("found", false)) return savedAtMs to null
        val images = json.optJSONArray("images")
        val urls = (0 until (images?.length() ?: 0)).mapNotNull { index ->
            images?.optString(index)?.takeIf(String::isNotBlank)
        }
        return savedAtMs to LessonTranscript(
            lessonId = lessonId,
            text = json.optString("text"),
            bookTitle = json.optString("bookTitle"),
            sourceRef = json.optString("sourceRef"),
            imageUrls = urls,
            contributorName = json.optString("contributorName"),
        )
    }

    private fun writeDisk(lessonId: String, transcript: LessonTranscript?, savedAtMs: Long) {
        val json = JSONObject()
        json.put("found", transcript != null)
        json.put("savedAtMs", savedAtMs)
        if (transcript != null) {
            json.put("text", transcript.text)
            json.put("bookTitle", transcript.bookTitle)
            json.put("sourceRef", transcript.sourceRef)
            json.put("contributorName", transcript.contributorName)
            json.put("images", JSONArray(transcript.imageUrls))
        }
        runCatching {
            diskDir.mkdirs()
            val file = entryFile(lessonId)
            file.writeText(json.toString())
            file.setLastModified(savedAtMs)
        }
        pruneDisk()
    }

    /**
     * سقف [MAX_DISK_ENTRIES] مدخلاً: يُسقط الأقدم أولاً.
     *
     * الترتيب بتاريخ تعديل الملفّ لا بقراءة محتوياته: كان التقليم يفكّ الكاش
     * كلّه في الذاكرة مع **كل** كتابة لمجرّد معرفة الأقدم.
     */
    private fun pruneDisk() {
        runCatching {
            val files = diskDir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_DISK_ENTRIES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_DISK_ENTRIES)
                .forEach { it.delete() }
        }
    }

    /**
     * إرسال اقتراح: يرفع الصور (إن وُجدت) إلى مجلد المساهمة ثم يستدعي
     * createTranscriptSubmission. يعيد معرّف المساهمة.
     */
    suspend fun submit(draft: TranscriptDraft, onProgress: (Int) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            require(draft.lessonId.isNotBlank()) { "الدرس غير محدد." }
            require(
                draft.text.trim().length >= 10 || draft.images.isNotEmpty(),
            ) { "أدخل نص المقطع أو أرفق صورة صفحة واحدة على الأقل." }
            val validatedImages = draft.images.take(MAX_IMAGES).mapIndexed { index, uri ->
                val size = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                    ?.use { it.length } ?: -1L
                require(size >= 0) { "تعذّرت قراءة الصورة ${index + 1} — أعد اختيارها." }
                require(size in 1..MAX_IMAGE_BYTES) { "حجم الصورة ${index + 1} يتجاوز 10 ميجابايت." }
                val contentType = appContext.contentResolver.getType(uri) ?: "image/jpeg"
                require(contentType.startsWith("image/")) { "الملف المرفق ليس صورة." }
                uri to contentType
            }
            if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)
            val id = "tsub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val keys = mutableListOf<String>()
            validatedImages.forEachIndexed { index, (uri, contentType) ->
                keys += MinbarApi.uploadUser(appContext, "transcripts", "${index}_page.jpg", uri, contentType) { share ->
                    onProgress(((index * 100L + share) / validatedImages.size).toInt())
                }.optString("key")
            }
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrDefault("")
            } else {
                ""
            }
            val payload = JSONObject()
                .put("id", id)
                .put("lessonId", draft.lessonId)
                .put("text", draft.text.trim())
                .put("bookTitle", draft.bookTitle.trim())
                .put("sourceRef", draft.sourceRef.trim())
                .put("note", draft.note.trim())
                .put("submitterName", draft.submitterName.trim())
                .put("imageKeys", JSONArray(keys))
                .put("fcmToken", fcmToken)
            val result = runCatching { MinbarApi.postUser(appContext, "/v1/transcript-submissions", payload) }
                .getOrElse { first ->
                    if (!isTransientFailure(first)) throw first
                    kotlinx.coroutines.delay(1_500)
                    MinbarApi.postUser(appContext, "/v1/transcript-submissions", payload)
                }
            val returned = result.optString("id")
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            returned
        }

    private suspend fun fetchMine(): List<TranscriptSubmissionItem> {
        val items = MinbarApi.getUser(appContext, "/v1/me/transcript-submissions").optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { o ->
            TranscriptSubmissionItem(
                id = o.optString("id"),
                lessonId = o.optString("lessonId"),
                lessonTitle = o.optString("lessonTitle"),
                status = o.optString("status").ifBlank { "pending" },
                rejectReason = o.optString("rejectReason"),
                hasImages = o.optBoolean("hasImages", false),
                createdAtMs = o.optLong("createdAtMs"),
                decidedAtMs = o.optLong("decidedAtMs"),
            )
        }.sortedByDescending(TranscriptSubmissionItem::createdAtMs)
    }

    private val mineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mineShared: Flow<List<TranscriptSubmissionItem>> by lazy {
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(runCatching { fetchMine() }.getOrDefault(emptyList()))
                kotlinx.coroutines.delay(30_000L)
            }
        }
            .catch { emit(emptyList()) }
            .shareIn(mineScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    fun mine(): Flow<List<TranscriptSubmissionItem>> = mineShared

    suspend fun deletePending(item: TranscriptSubmissionItem) {
        if (!item.isPending) return
        MinbarApi.deleteUser(appContext, "/v1/me/transcript-submissions/${item.id}")
    }

    /** بحث في النصوص المشروحة على الخادم: معرّفات الدروس التي يرد فيها [keyword]. */
    suspend fun searchIndex(keyword: String): List<String> {
        if (keyword.length < MIN_SEARCH_KEYWORD) return emptyList()
        searchCache[keyword]?.let { return it }
        return withContext(Dispatchers.IO) {
            val hits = runCatching {
                val items = MinbarApi.searchTranscripts(keyword, SEARCH_LIMIT.toInt())
                (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("lessonId") }
                    .filter { it.isNotBlank() }
            }.getOrNull() ?: return@withContext emptyList<String>()
            if (searchCache.size >= MAX_SEARCH_CACHE) searchCache.clear()
            searchCache[keyword] = hits
            hits
        }
    }

    fun invalidate(lessonId: String) {
        cache.remove(lessonId)
        runCatching { entryFile(lessonId).delete() }
    }

    companion object {
        const val MAX_IMAGES = 4
        const val MAX_IMAGE_BYTES = 10L * 1_024L * 1_024L
        const val MAX_TEXT_CHARS = 20_000

        /** أقصر كلمة تدخل الفهرس ويُسأل بها — بنفس حدّ الخادم في بنائه. */
        const val MIN_SEARCH_KEYWORD = 3
        private const val SEARCH_INDEX = "transcript_index"

        // 25 لا 20: ما ظهر في نتائج العناوين يُحذف من قسم المتون، والعرض 20.
        private const val SEARCH_LIMIT = 25L
        private const val MAX_SEARCH_CACHE = 24
        private const val TEXT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
        private const val EMPTY_TTL_MS = 24L * 60 * 60 * 1000L
        private const val MAX_DISK_ENTRIES = 200
        /// مجلَّد الكاش الجديد (ملفّ لكل درس) تحت `cacheDir`.
        private const val CACHE_DIR = "transcripts"

        /// اسم تفضيلات الكاش القديم — يُحذف مرّةً في [init] لا غير.
        private const val CACHE_FILE = "minbar_transcript_cache"
        private const val COLLECTION = "transcript_submissions"
        private const val TRANSCRIPTS = "lesson_transcripts"
        @Volatile private var instance: TranscriptRepository? = null
        fun get(context: Context): TranscriptRepository = instance ?: synchronized(this) {
            instance ?: TranscriptRepository(context).also { instance = it }
        }
    }
}
