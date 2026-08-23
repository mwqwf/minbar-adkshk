package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import com.ali.menbaradkshk.util.normalizeArabic
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()

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
                val document = db.collection(TRANSCRIPTS).document(lessonId).get().await()
                if (!document.exists()) {
                    null
                } else {
                    LessonTranscript(
                        lessonId = lessonId,
                        text = document.getString("text").orEmpty(),
                        bookTitle = document.getString("bookTitle").orEmpty(),
                        sourceRef = document.getString("sourceRef").orEmpty(),
                        imageUrls = (document.get("images") as? List<*>).orEmpty()
                            .mapNotNull { item ->
                                (item as? Map<*, *>)?.get("url")?.toString()
                                    ?.takeIf { it.isNotBlank() }
                            },
                        contributorName = document.getString("contributorName").orEmpty(),
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
    suspend fun submit(draft: TranscriptDraft, onProgress: (Int) -> Unit = {}): String {
        require(draft.lessonId.isNotBlank()) { "الدرس غير محدد." }
        require(
            draft.text.trim().length >= 10 || draft.images.isNotEmpty(),
        ) { "أدخل نص المقطع أو أرفق صورة صفحة واحدة على الأقل." }
        // تحقّق من المجموعة كاملة قبل أول رفع، كي لا نرفع صوراً ثم نحذفها
        // لمجرّد أن صورة لاحقة كبيرة أو ليست صورة.
        val validatedImages = draft.images.take(MAX_IMAGES).mapIndexed { index, uri ->
            val size = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { it.length } ?: -1L
            // فصل السببين: حجم مجهول (وصول مُنتزَع/ملف حُذف) ليس «تجاوز الحدّ»،
            // فالرسالة الواحدة كانت تتّهم حجم صورةٍ لم يُقرأ حجمها أصلاً.
            require(size >= 0) { "تعذّرت قراءة الصورة ${index + 1} — أعد اختيارها." }
            require(size in 1..MAX_IMAGE_BYTES) {
                "حجم الصورة ${index + 1} يتجاوز 10 ميجابايت."
            }
            val contentType = appContext.contentResolver.getType(uri) ?: "image/jpeg"
            require(contentType.startsWith("image/")) { "الملف المرفق ليس صورة." }
            uri to contentType
        }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        requireNotNull(user) { "تعذّر إنشاء الهوية الآمنة." }
        if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)

        val id = "tsub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val uploadedPaths = mutableListOf<String>()
        var callableStarted = false
        try {
            validatedImages.forEachIndexed { index, (uri, contentType) ->
                val path = "transcript_submissions/${user.uid}/$id/${index}_page.jpg"
                val task = storage.reference.child(path).putFile(
                    uri,
                    StorageMetadata.Builder().setContentType(contentType).build(),
                )
                // ⚠️ كان التقدّم يُبلَّغ بعد اكتمال كل ملف فقط، فمع صورة واحدة
                // (الحالة الأشيع) لا تظهر نسبة قطّ لأن القيمة الوحيدة 100،
                // والواجهة تعرض النسبة في المدى 1..99 فقط.
                task.addOnProgressListener { snapshot ->
                    if (snapshot.totalByteCount > 0L) {
                        val fileShare =
                            snapshot.bytesTransferred * 100L / snapshot.totalByteCount
                        onProgress(
                            ((index * 100L + fileShare) / validatedImages.size).toInt(),
                        )
                    }
                }
                task.await()
                uploadedPaths.add(path)
                onProgress(((index + 1) * 100) / validatedImages.size.coerceAtLeast(1))
            }
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrDefault("")
            } else {
                ""
            }
            val payload = mapOf(
                "submissionId" to id,
                "lessonId" to draft.lessonId,
                "text" to draft.text.trim(),
                "bookTitle" to draft.bookTitle.trim(),
                "sourceRef" to draft.sourceRef.trim(),
                "note" to draft.note.trim(),
                "submitterName" to draft.submitterName.trim(),
                "imagePaths" to uploadedPaths,
                "fcmToken" to fcmToken,
            )
            callableStarted = true
            val result = runCatching {
                functions.getHttpsCallable("createTranscriptSubmission").call(payload).await()
            }.getOrElse { first ->
                // ⚠️ كانت الإعادة عمياء: الرفض القاطع (حدّ يومي، فاصل أدنى،
                // تحقّق) يُستدعى مرّتين بلا جدوى ويؤخّر وصول سببه للمستخدم.
                if (!isTransientFailure(first)) throw first
                kotlinx.coroutines.delay(1_500)
                functions.getHttpsCallable("createTranscriptSubmission").call(payload).await()
            }
            val returned = (result.data as? Map<*, *>)?.get("id")?.toString().orEmpty()
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            return returned
        } catch (failure: Throwable) {
            // كان التنظيف مشروطاً بـ«لم يبدأ الاستدعاء» بينما العلم يُرفع **قبل**
            // الاستدعاء، فأي فشل بعده (تجاوز حدّ المساهمات اليومي، أو الفاصل
            // الأدنى بين مساهمتين، أو فشل App Check، أو «الدرس غير موجود»، أو
            // رفض تحقّق الصور) يترك الصور يتيمة بلا مهمّة تنظّفها. الآن نحذف في
            // **كل** مسار لم تُنشأ فيه وثيقة، ونمتنع حين يتعذّر التحقّق أصلاً.
            val lookup = if (callableStarted) {
                findMySubmission(id, user.uid)
            } else {
                Result.success<DocumentSnapshot?>(null)
            }
            // وثيقة موجودة: المساهمة نجحت فعلاً وضاع ردّ الخادم فقط.
            if (lookup.getOrNull() != null) return id
            if (lookup.isSuccess) {
                uploadedPaths.forEach { path ->
                    runCatching { storage.reference.child(path).delete().await() }
                }
            }
            throw failure
        }
    }

    /**
     * تبحث عن وثيقة الاقتراح بعد فشلٍ ما. نستعمل استعلاماً مقيَّداً بـuid لا
     * `get` مباشراً على الوثيقة: قواعد الأمان ترفض قراءة وثيقة غير موجودة
     * أصلاً، فيلتبس «لم تُنشأ» بـ«تعذّر السؤال» ويضيع قرار حذف الصور اليتيمة.
     * نجاح ومعه وثيقة = أُنشئت، ونجاح بلا وثيقة = لم تُنشأ، وفشل = لا نعرف.
     */
    private suspend fun findMySubmission(id: String, uid: String): Result<DocumentSnapshot?> =
        runCatching {
            db.collection(COLLECTION)
                .whereEqualTo("uid", uid)
                .whereEqualTo(FieldPath.documentId(), id)
                .limit(1)
                .get(Source.SERVER)
                .await()
                .documents
                .firstOrNull()
        }

    fun mine(): Flow<List<TranscriptSubmissionItem>> = callbackFlow {
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
                    TranscriptSubmissionItem(
                        id = document.id,
                        lessonId = document.getString("lessonId").orEmpty(),
                        lessonTitle = document.getString("lessonTitle").orEmpty(),
                        status = document.getString("status").orEmpty().ifBlank { "pending" },
                        rejectReason = document.getString("rejectReason").orEmpty(),
                        hasImages = (document.get("imagePaths") as? List<*>)
                            .orEmpty().isNotEmpty(),
                        createdAtMs = document.getLong("createdAtMs")
                            ?: (document.get("createdAtTs") as? Timestamp)?.toDate()?.time
                            ?: 0L,
                        decidedAtMs = (document.get("decidedAtTs") as? Timestamp)?.toDate()?.time
                            ?: 0L,
                    )
                }.sortedByDescending(TranscriptSubmissionItem::createdAtMs)
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun deletePending(item: TranscriptSubmissionItem) {
        if (!item.isPending) return
        functions.getHttpsCallable("deleteMyTranscriptSubmission")
            .call(mapOf("submissionId" to item.id)).await()
    }

    /**
     * 🔎 بحث في متون «النص المشروح» — يعيد معرّفات دروسٍ وردت المرساة في
     * متونها.
     *
     * استعلام **واحد** بـ`array-contains` على كلمة المرساة (انظر
     * [transcriptSearchAnchor])، فلا تُجلب مجموعة المتون ولا يُخزَّن نصّ منها
     * في الجهاز لأجل البحث — الوثيقة المفهرسة كلمات لا نصّ.
     *
     * ⚠️ **النتيجة نافذةٌ لا استيعاب**: الاستعلام بلا ترتيب، فيُرجع Firestore
     * أوّل [SEARCH_LIMIT] وثيقة بترتيب المعرّف — عيّنةً من المطابقات لا
     * كلَّها. ولهذا عمداً لا ترشيح محلّيّاً ببقيّة كلمات السؤال: شرطُ AND فوق
     * نافذةٍ اعتباطيّة كان يُسقط دروساً مطابقة فعلاً ويكاد يُفرغ القائمة.
     *
     * الفشل (بلا اتصال مثلاً) يعود بلا نتائج لا باستثناء: هذا القسم زيادةٌ
     * على البحث القائم، ولا يصحّ أن يُسقط شاشة البحث كلّها.
     */
    suspend fun searchIndex(keyword: String): List<String> {
        if (keyword.length < MIN_SEARCH_KEYWORD) return emptyList()
        searchCache[keyword]?.let { return it }
        return withContext(Dispatchers.IO) {
            val documents = runCatching {
                db.collection(SEARCH_INDEX)
                    .whereArrayContains("keywords", keyword)
                    .limit(SEARCH_LIMIT)
                    .get()
                    .await()
                    .documents
            }.getOrNull() ?: return@withContext emptyList<String>()
            val hits = documents.map { document ->
                document.getString("lessonId")?.takeIf(String::isNotBlank) ?: document.id
            }
            // سقف بسيط بدل إخراج الأقدم: جلسة بحثٍ واحدة لا تبلغه غالباً،
            // وبلوغه يعني أن ما قبله لم يعد يُسأل عنه أصلاً.
            if (searchCache.size >= MAX_SEARCH_CACHE) searchCache.clear()
            searchCache[keyword] = hits
            hits
        }
    }

    /** تفريغ كاش درس — الطبقتين معاً (بعد اعتماد اقتراح مثلاً ليظهر فوراً). */
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
