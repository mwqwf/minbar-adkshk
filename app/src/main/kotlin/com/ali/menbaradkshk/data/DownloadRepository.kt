package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadProgress(
    val lessonId: String,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

/// حالة طابور التحميل الخلفي (تُحدَّث من عامل WorkManager وتُعرض في الواجهة).
data class DownloadQueueState(
    val label: String,
    val done: Int,
    val total: Int,
    val currentTitle: String = "",
    val waitingForNetwork: Boolean = false,
    /// أوقفه المستخدم — لا يُستأنف إلا بضغطة «استئناف».
    val paused: Boolean = false,
    /// نسبة الملفّ الجاري (0..100)، و‑1 حين لا يُعلن الخادم حجماً.
    /// بدونها كان الشريط مبنيّاً على `done/total` وحدها، فيبقى عند الصفر
    /// طوال تحميل درس واحد ويبدو كأنّه متجمّد.
    val filePercent: Int = -1,
    val fileDownloadedBytes: Long = 0L,
    val fileTotalBytes: Long = 0L,
) {
    /// تقدّم الطابور كاملاً: الدروس المكتملة + كسر الدرس الجاري.
    val overallFraction: Float
        get() {
            if (total <= 0) return 0f
            val fileFraction = if (filePercent in 0..100) filePercent / 100f else 0f
            return ((done + fileFraction) / total).coerceIn(0f, 1f)
        }
}

/// خطأ شبكة قابل لإعادة المحاولة (يستأنف WorkManager تلقائياً عند عودة الاتصال).
class RetryableDownloadException(cause: Throwable) : Exception(cause)

/// أوقف المستخدم التحميل مؤقّتاً — الملف الجزئي **يبقى** فيُستأنف من موضعه.
class DownloadPausedException : Exception("أُوقف التحميل مؤقّتاً.")

/// ألغى المستخدم تحميل هذا الدرس — الملف الجزئي يُحذف ويخرج من الطابور.
class DownloadCancelledException(val lessonId: String) : Exception("أُلغي التحميل.")

class DownloadRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    val queueState = MutableStateFlow<DownloadQueueState?>(null)

    /// قفل لكل درس: مسارا التحميل (وظيفة UIDT وعمل WorkManager) قد يتشابكان
    /// على المعرّف نفسه، فيفتح كلاهما `FileOutputStream` على الملف الجزئي
    /// `<safeId>.<ext>.part` نفسه بإزاحتين مستقلّتين فيتلف الملف — خاصّةً مع
    /// منطق الاستئناف بـRange.
    private val lessonLocks = mutableMapOf<String, Mutex>()

    /// ⚠️ عدّاد المنتظِرين لكل درس. بدونه كانت الخريطة تنمو بلا حدّ: مدخلٌ
    /// لكل درس نُزّل ولا يُحذف منها شيء قطّ، ومن نزّل قسماً فيه مئات الدروس
    /// يبقيها كلّها في ذاكرة عمليّةٍ حيّةٍ طول عمرها (المستودع مفرد). والحذف
    /// بالعدّاد لا بمجرّد انتهاء العمل: لو حُذف القفل ومنتظِرٌ آخر قائمٌ عليه
    /// لأخذ التالي **قفلاً جديداً** فدخل المسارُ نفسه مرّتين على الملفّ
    /// الجزئي نفسه — وهو بعينه ما وُضع القفل لمنعه.
    private val lockWaiters = mutableMapOf<String, Int>()

    private fun acquireLock(lessonId: String): Mutex = synchronized(lessonLocks) {
        lockWaiters[lessonId] = (lockWaiters[lessonId] ?: 0) + 1
        lessonLocks.getOrPut(lessonId) { Mutex() }
    }

    private fun releaseLock(lessonId: String) = synchronized(lessonLocks) {
        val remaining = (lockWaiters[lessonId] ?: 1) - 1
        if (remaining <= 0) {
            lockWaiters.remove(lessonId)
            lessonLocks.remove(lessonId)
        } else {
            lockWaiters[lessonId] = remaining
        }
    }

    private suspend fun <T> withLessonLock(lessonId: String, block: suspend () -> T): T {
        val mutex = acquireLock(lessonId)
        try {
            return mutex.withLock { block() }
        } finally {
            releaseLock(lessonId)
        }
    }

    // ---- تحكّم المستخدم في النقل الجاري ----
    //
    /// إلغاءات مطلوبة لدروس بعينها. الفحص داخل حلقة القراءة لا خارجها:
    /// حذف المعرّف من الطابور وحده لا يوقف نقلاً بدأ فعلاً، فيظلّ يستهلك
    /// البيانات إلى آخر بايت ثم يُكتب الملف كأنّ الإلغاء لم يقع.
    private val cancelRequests = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /// هل الطابور موقوف مؤقّتاً؟ (مرآة في الذاكرة لعلم [LocalStore] كي
    /// تُقرأ في حلقة النقل بلا لمس القرص مع كل حزمة بايتات.)
    private val _paused = MutableStateFlow(store.downloadQueuePaused())
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(value: Boolean) {
        _paused.value = value
        store.setDownloadQueuePaused(value)
    }

    fun requestCancel(lessonId: String) {
        cancelRequests += lessonId
    }

    fun clearCancel(lessonId: String) {
        cancelRequests -= lessonId
    }

    /// ⚠️ يُمسح **كل** علم إلغاء عند إلغاء الطابور كلّه. العلم لا معنى له
    /// بعد خروج الدرس من الطابور، وبقاؤه كان يعني أنّ تحميلاً لاحقاً لدرس
    /// أُلغي مرّة (يدويّاً أو من التحميل التلقائي) يُسقَط بصمت.
    fun clearAllCancels() {
        cancelRequests.clear()
    }

    fun isCancelRequested(lessonId: String): Boolean = cancelRequests.contains(lessonId)

    fun localPath(lessonId: String): String? = store.localAudioPath(lessonId)
    fun isDownloaded(lessonId: String): Boolean = localPath(lessonId) != null
    fun all(): Map<String, String> = store.downloads()

    suspend fun download(lesson: Lesson): String {
        require(lesson.id.isNotBlank()) { "معرّف الدرس مفقود." }
        return withLessonLock(lesson.id) { downloadLocked(lesson) }
    }

    private suspend fun downloadLocked(lesson: Lesson): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(lesson.audioUrl)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "رابط الصوت غير آمن أو غير صالح."
        }
        localPath(lesson.id)?.let { return@withContext it }

        val directory = File(appContext.filesDir, "lessons").apply { mkdirs() }
        // تطبيع الامتداد: التخزين يعطي أحياناً `.ogx` (Ogg مُتعدِّد) وما شابه،
        // وتطبيقات المراسلة لا تعدّها صوتاً فتُرسلها ملفّاً عامّاً.
        val extension = when (
            val raw = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
        ) {
            "mp3", "m4a", "aac", "wav", "flac", "amr", "opus", "ogg" -> raw
            "ogx", "oga", "ogv" -> "ogg"
            "m4b", "mp4" -> "m4a"
            "3gp", "3gpp" -> "3gp"
            else -> "mp3"
        }
        val safeId = lesson.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val target = File(directory, "$safeId.$extension")
        val partial = File(directory, "$safeId.$extension.part")
        // ⚠️ بصمة مصدر الجزئي: اسم الجزئي مفتاحه معرّف الدرس لا الرابط، فلو
        // استُبدل صوت الدرس في اللوحة (رابط جديد بنفس الامتداد) لاستُؤنف جزئي
        // الملف القديم فوق الجديد بـRange — فيُحفَظ ملف نصفه من صوتٍ ونصفه من
        // آخر كتنزيل «مكتمل» يُشغَّل مشوَّهاً بلا إنترنت إلى الأبد. الرابط
        // يُكتب في ملف جانبي، واختلافه يُسقط الجزئي فيبدأ التنزيل من الصفر.
        val sourceMark = File(directory, "$safeId.$extension.part.src")
        if (partial.length() > 0L) {
            val previousUrl = runCatching { sourceMark.readText() }.getOrDefault("")
            if (previousUrl != lesson.audioUrl) partial.delete()
        }
        runCatching { sourceMark.writeText(lesson.audioUrl) }

        // 💾 «البايت يُملَك»: قبل فتح أي اتصال، تُبذر في الجزئي **البادئة
        // المتصلة من البايت 0** المتجمّعة في كاش البثّ (استماعٌ سابق لنفس
        // المحتوى) — عبر واجهة الكاش العامة وحدها (تقفل الـspans ضد الإخلاء
        // أثناء النسخ)، وبلا أي دمج لمقاطع القفز المتفرقة (ملف تالف حتماً).
        // أفضل جهد: أي شكّ أو فشل يُبقي المسار الشبكي الحالي حرفياً.
        val seededTotal = seedPartialFromStreamCache(lesson, partial)
        if (seededTotal > 0L && partial.length() == seededTotal) {
            // الدرس كله كان في كاش البث — تنزيل مكتمل بصفر شبكة.
            return@withContext completePartial(lesson, partial, target, sourceMark)
        }

        // ميزانية مساحة استباقية: الحجم معلوم من الفهرس (sizeBytes) فلا نبدأ
        // نقلاً محكوماً بالفشل — يُخلى تلقائيُّ الطبقة الدنيا أولاً إن أمكن.
        if (lesson.sizeBytes > 0L) {
            val needed = (lesson.sizeBytes - partial.length()).coerceAtLeast(0L) + SPACE_MARGIN_BYTES
            val available = runCatching {
                android.os.StatFs(appContext.filesDir.absolutePath).availableBytes
            }.getOrDefault(Long.MAX_VALUE)
            if (available < needed) evictAutoDownloads(needed - available, lesson.id)
        }

        var connection: HttpURLConnection? = null
        try {
            // استئناف التنزيل من حيث توقف: نطلب المدى المتبقي إن وُجد ملف جزئي.
            val resumeFrom = partial.length().takeIf { it > 0L } ?: 0L
            connection = (URL(lesson.audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MinbarAdkassahk/${com.ali.menbaradkshk.BuildConfig.VERSION_NAME}")
                // بلا ضغط وسيط: gzip شفّاف يجعل Content-Length وحساب Range
                // غير مطابقَين للبايتات المكتوبة فيفسد الاستئناف والنِّسب.
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
                connect()
            }
            val code = connection.responseCode
            // 206 = الخادم قبل الاستئناف؛ 200 مع طلب مدى = لا يدعمه فنبدأ من الصفر.
            val resuming = resumeFrom > 0L && code == 206
            if (resumeFrom > 0L && code == 200) partial.delete()
            // 416: الملف الجزئي تجاوز حجم الملف على الخادم (استُبدل الملف
            // غالباً) — نحذفه ونعيد المحاولة من الصفر بدل فشل دائم يُسقط الدرس.
            if (resumeFrom > 0L && code == 416) {
                partial.delete()
                throw RetryableDownloadException(java.io.IOException("HTTP 416"))
            }
            // أعطال الخادم/الازدحام المؤقّتة قابلة للإعادة تلقائياً — معاملتها
            // كفشل دائم كانت تُسقط الدرس من الطابور بلا أي محاولة ثانية
            // (شكوى مستخدمين فعلية). الملف الجزئي يبقى للاستئناف.
            if (code in intArrayOf(408, 425, 429, 500, 502, 503, 504)) {
                throw RetryableDownloadException(java.io.IOException("HTTP $code"))
            }
            require(code in 200..299) { "تعذّر تنزيل الملف ($code)." }
            // 🛡️ بوّابة أسيرة (واي فاي فندق/مقهى): تعيد 200 وصفحة HTML لتسجيل
            // الدخول — كانت تُحفظ كملفّ mp3 «مكتمل» فيتقطّع صوته إلى الأبد.
            // فشل قابل للإعادة: بعد تجاوز البوّابة يُستأنف التحميل الصحيح.
            if (connection.contentType.orEmpty().startsWith("text/html", ignoreCase = true)) {
                throw RetryableDownloadException(java.io.IOException("استجابة HTML لا ملفّ صوتي"))
            }
            if (resuming) {
                // تحقُّق من إزاحة الاستئناف: خادم يُعيد 206 بمدى لا يبدأ من
                // موضعنا كان سيُلحق بايتات بإزاحة خاطئة فيتلف الملف بصمت.
                val start = connection.getHeaderField("Content-Range")
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore('-')
                    ?.trim()
                    ?.toLongOrNull()
                if (start != null && start != resumeFrom) {
                    partial.delete()
                    throw RetryableDownloadException(
                        java.io.IOException("مدى الاستئناف غير متطابق ($start ≠ $resumeFrom)"),
                    )
                }
            }
            val remaining = connection.getHeaderField("Content-Length")
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val alreadyHave = if (resuming) resumeFrom else 0L
            val total = if (remaining > 0L) remaining + alreadyHave else 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = alreadyHave
                    // ⚠️ الإصدار كان يقع مع كل قراءة (~8 ك.ب): خريطة جديدة
                    // عشرات المرّات في الثانية تُعيد تركيب كل صفّ ظاهر يقرأ
                    // التقدّم. المستهلك الحقيقي يقرأ آخر قيمة كل ثانية، فلا
                    // يجوز أن يعود الإصدار بلا خنق: نسبة جديدة أو ربع ميغابايت.
                    var lastEmittedBytes = alreadyHave
                    var lastEmittedPercent = -2
                    while (true) {
                        // إلغاء الكوروتين لا يقاطع خيط Dispatchers.IO: بلا هذا
                        // الفحص يستمرّ النقل حتى EOF بعد onStopJob، ثم يرمي
                        // `withContext` إلغاءً يُلتقط كفشل دائم فيُمحى الطابور.
                        // الرمي هنا يُغلق التيّارات (use) والاتصال (finally) فوراً.
                        ensureActive()
                        // تحكّم المستخدم يُفحص مع كل حزمة: الإيقاف والإلغاء
                        // يجب أن يوقفا استهلاك البيانات **فوراً** لا عند
                        // نهاية الملف. الرمي هنا يغلق التيّارات والاتصال.
                        if (_paused.value) throw DownloadPausedException()
                        if (cancelRequests.contains(lesson.id)) {
                            throw DownloadCancelledException(lesson.id)
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val percent = if (total > 0L) ((received * 100L) / total).toInt() else -1
                        if (
                            percent != lastEmittedPercent ||
                            received - lastEmittedBytes >= PROGRESS_EMIT_BYTES
                        ) {
                            lastEmittedPercent = percent
                            lastEmittedBytes = received
                            _progress.value = _progress.value + (
                                lesson.id to DownloadProgress(lesson.id, percent, received, total)
                            )
                        }
                    }
                    output.fd.sync()
                    // 🧮 اكتمال البايتات: انتهاء التيار انتهاءً «نظيفاً» مبكراً
                    // (بروكسي شبكة، بوابة أسيرة، قطع خادم) يعيد -1 بلا استثناء،
                    // فكان ملف 40% يُحفَظ كتنزيل مكتمل ويتقطّع صوته بلا إنترنت
                    // إلى الأبد (isDownloaded ترى الملف موجوداً فلا يُعاد). النقص
                    // قابل للإعادة والجزئي يبقى ليُستأنف من موضعه؛ والزائد
                    // يُشفى ذاتياً: الاستئناف التالي يعيد 416 فيُحذف الجزئي.
                    if (total > 0L && received != total) {
                        throw RetryableDownloadException(
                            java.io.IOException("نقل ناقص ($received من $total)"),
                        )
                    }
                }
            }
            completePartial(lesson, partial, target, sourceMark)
        } catch (cancelled: CancellationException) {
            // إلغاء (onStopJob/إغلاق العملية) ليس فشلاً: الملف الجزئي يبقى
            // للاستئناف، والإلغاء يُعاد رميه كما هو كي لا يُحذف الدرس من الطابور.
            throw cancelled
        } catch (paused: DownloadPausedException) {
            // ⏸ الملف الجزئي **يبقى**: الاستئناف يطلب المدى المتبقي بـRange
            // فيُكمل من البايت نفسه بلا إعادة تنزيل ما نزل.
            throw paused
        } catch (cancelled: DownloadCancelledException) {
            // ✕ إلغاء صريح: لا معنى لإبقاء نصف ملفّ لن يُستأنف.
            partial.delete()
            sourceMark.delete()
            throw cancelled
        } catch (retryable: RetryableDownloadException) {
            // ♻️ قابل للإعادة (HTTP 5xx/416/نقل ناقص): كان يسقط في مصيدة
            // Throwable أدناه فتحذف الجزئيَّ الذي وعد التعليق أعلاه بإبقائه —
            // أي أن كل انقطاع يُعيد التنزيل من الصفر. يُعاد رميه كما هو
            // (من حذف جزئيّه قبل الرمي — كحالة 416 — حَذَفه صراحةً هناك).
            throw retryable
        } catch (failure: java.io.IOException) {
            // ⛔ امتلاء التخزين ليس انقطاع شبكة: كان يُصنَّف «قابلاً للإعادة»
            // فيوقظ الوظيفة بلا نهاية ويعرض «بانتظار عودة الاتصال…» وهي رسالة
            // كاذبة. لا يجوز أن يعود: خطأ دائم يُخرج الدرس من الطابور.
            val reason = "${failure.message} ${failure.cause?.message}"
            com.ali.menbaradkshk.util.DiagLog.log(
                appContext, "dl", "${lesson.id} io: ${failure.message}",
            )
            if (reason.contains("ENOSPC") || reason.contains("No space left")) {
                // امتلاء فعليّ: قبل الاستسلام يُخلى من التنزيلات **التلقائية**
                // (اليدوية محصَّنة دائماً) بقدر الحاجة، فإن أُخلي شيء فالفشل
                // قابل للإعادة والجزئي يبقى يُستأنف من بايته؛ وإلا فدائم كما كان.
                val freed = evictAutoDownloads(
                    (lesson.sizeBytes - partial.length()).coerceAtLeast(SPACE_MARGIN_BYTES),
                    lesson.id,
                )
                if (freed > 0L) throw RetryableDownloadException(failure)
                partial.delete()
                sourceMark.delete()
                throw IllegalStateException("لا تكفي المساحة على الجهاز لإكمال التنزيل.")
            }
            // انقطاع شبكة: نُبقي الملف الجزئي للاستئناف لاحقاً ونعلن قابلية الإعادة.
            throw RetryableDownloadException(failure)
        } catch (failure: Throwable) {
            partial.delete()
            sourceMark.delete()
            throw failure
        } finally {
            connection?.disconnect()
            _progress.value = _progress.value - lesson.id
            // العلم أدّى غرضه (أوقف النقل) فيُمسح هنا لا في مكان بعيد:
            // بقاؤه يجعل أيّ محاولة تحميل تالية لهذا الدرس تُلغى بصمت.
            cancelRequests -= lesson.id
        }
    }

    /**
     * إتمام تنزيل: تحقّق البصمة (إن كانت هوية المحتوى معلومة من الفهرس)،
     * ثم النقل الذرّي للاسم النهائي وتسجيل الفهرسين (المسار + البيانات الغنية).
     *
     * فشل البصمة = بايتات لا تطابق ما تصفه الوثيقة (وسيطٌ عابث، ملف استُبدل
     * تحت نفس الرابط رغم ختم المسارات) — يُحذف كل شيء ويُعاد قابلاً للإعادة،
     * فلا يدخل المكتبة ملف لا تشهد له بصمته أبداً.
     */
    private fun completePartial(
        lesson: Lesson,
        partial: File,
        target: File,
        sourceMark: File,
    ): String {
        check(partial.length() > 0L) { "ملف الصوت فارغ." }
        val computedSha = if (lesson.sha256.isNotBlank()) sha256Of(partial) else ""
        if (lesson.sha256.isNotBlank() && computedSha != lesson.sha256) {
            partial.delete()
            sourceMark.delete()
            com.ali.menbaradkshk.util.DiagLog.log(
                appContext, "dl", "${lesson.id} sha-mismatch ${computedSha.take(12)}",
            )
            throw RetryableDownloadException(
                java.io.IOException("بصمة الملف لا تطابق هوية المحتوى المعلنة."),
            )
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            // بعض الأنظمة ترفض rename رغم أنّ الوجهة في المجلد نفسه —
            // النسخ ثم الحذف خطة بديلة تُنقذ تنزيلاً اكتمل فعلاً.
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        store.setDownload(lesson.id, target.absolutePath)
        store.setDownloadMeta(
            lesson.id,
            sha = computedSha.ifBlank { lesson.sha256 },
            source = if (store.consumeAutoQueued(lesson.id)) "auto" else "manual",
            sizeBytes = target.length(),
        )
        // تنزيلٌ وصل بأي طريق يمسح إشارة «حذفه المستخدم» السلبية.
        store.clearUserDeletedDownload(lesson.id)
        // البصمة أدّت غرضها — الملف صار باسمه النهائي في الفهرس.
        sourceMark.delete()
        return target.absolutePath
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * بذر الجزئي من كاش البثّ: يعيد الطول الكلي المعروف للمحتوى (أو 0) بعد
     * محاولة إلحاق ما ينقص الجزئيَّ من بادئة الكاش المتصلة من الصفر.
     *
     * شروط الرفض (أيّها تحقق ⇒ لا بذر وإبقاء كل شيء كما هو): لا بادئة أطول
     * من الجزئي؛ بادئة أطول من الطول الكلي المعلوم (فساد)؛ فشل/نقص القراءة
     * (يُحذف الجزئي كله — أسلم من خليط). مفتاح الكاش = بصمة المحتوى إن
     * وُجدت (فيبقى الكاش صالحاً عبر تبديل المضيف) وإلا الرابط (السلوك القديم).
     */
    private fun seedPartialFromStreamCache(lesson: Lesson, partial: File): Long = runCatching {
        val cache = com.ali.menbaradkshk.MinbarApplication.mediaCache(appContext)
        val key = lesson.sha256.ifBlank { lesson.audioUrl }
        val metaLen = androidx.media3.datasource.cache.ContentMetadata.getContentLength(
            cache.getContentMetadata(key),
        ).takeIf { it > 0L } ?: 0L
        val prefix = cache.getCachedLength(key, 0L, Long.MAX_VALUE).takeIf { it > 0L } ?: 0L
        val have = partial.length()
        if (prefix <= have) return@runCatching metaLen
        if (metaLen > 0L && prefix > metaLen) return@runCatching 0L
        val source = androidx.media3.datasource.cache.CacheDataSource(cache, null)
        try {
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(Uri.parse(lesson.audioUrl))
                .setKey(key)
                .setPosition(have)
                .setLength(prefix - have)
                .build()
            source.open(spec)
            FileOutputStream(partial, true).use { output ->
                val buffer = ByteArray(1 shl 16)
                var copied = 0L
                while (copied < prefix - have) {
                    val count = source.read(
                        buffer, 0,
                        minOf(buffer.size.toLong(), prefix - have - copied).toInt(),
                    )
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                }
                output.fd.sync()
                if (copied != prefix - have) {
                    // نسخ ناقص = لا نثق بالخليط: يُحذف الجزئي كله ويُعاد شبكياً.
                    partial.delete()
                    return@runCatching 0L
                }
            }
        } finally {
            runCatching { source.close() }
        }
        metaLen
    }.getOrDefault(0L)

    /**
     * إخلاء تنزيلات **تلقائية** (الأقدم أولاً، واليدوي والمثبَّت لا يُمسّان
     * أبداً) حتى تحرير [neededBytes] تقريباً. يعيد ما حُرّر فعلاً.
     */
    private fun evictAutoDownloads(neededBytes: Long, sparedLessonId: String): Long {
        if (neededBytes <= 0L) return 0L
        var freed = 0L
        for (id in store.autoDownloadedIdsOldestFirst()) {
            if (freed >= neededBytes) break
            if (id == sparedLessonId) continue
            val path = store.localAudioPath(id) ?: continue
            val size = File(path).length()
            if (runCatching { File(path).delete() }.getOrDefault(false)) {
                store.removeDownload(id)
                freed += size
            }
        }
        return freed
    }

    /**
     * دروس منزَّلة صار صوتها **قديماً**: هوية المحتوى في الفهرس تخالف بصمة
     * التنزيل المسجَّلة. لا يُحكم على تنزيل بلا بصمة مسجّلة (سبق المعمارية)
     * إلا إن حمل الدرس بصمة الآن — فتنزيله القديم مجهول الهوية ويُجدَّد.
     */
    fun staleDownloadIds(lessons: List<Lesson>): List<String> = lessons.mapNotNull { lesson ->
        if (lesson.sha256.isBlank()) return@mapNotNull null
        val path = store.localAudioPath(lesson.id) ?: return@mapNotNull null
        if (!File(path).isFile) return@mapNotNull null
        val recorded = store.downloadSha(lesson.id)
        if (recorded != lesson.sha256) lesson.id else null
    }

    /**
     * يكنس الملفّات الجزئيّة لدروسٍ أُلغيت.
     *
     * ⚠️ الإلغاء لا يحذف الجزئيّ إلا حين يكون النقل **جارياً** (المعالج
     * `DownloadCancelledException` هناك)؛ أمّا المنتظِر في الطابور فلا يمرّ
     * بذلك المسار أصلاً، وقد يكون خلّف جزئيّاً من محاولة سابقة انقطعت. فكان
     * حوار «إلغاء الكل» يَعِد بحذف الأجزاء ووعده غير منفَّذ، وتبقى ميغابايتات
     * محجوزة بلا أن يراها المستخدم في «تنزيلاتي».
     */
    suspend fun discardPartials(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val directory = File(appContext.filesDir, "lessons")
        val files = directory.listFiles() ?: return@withContext
        // قاعدة التسمية نفسها المستعملة في التنزيل: `<safeId>.<ext>.part`،
        // ومعها بصمة المصدر `<safeId>.<ext>.part.src` كي لا تبقى يتيمة.
        val prefixes = ids.map { "${it.replace(Regex("[^A-Za-z0-9_-]"), "_")}." }
        files.forEach { file ->
            val name = file.name
            val isPartialArtifact = name.endsWith(".part") || name.endsWith(".part.src")
            if (isPartialArtifact && prefixes.any { name.startsWith(it) }) file.delete()
        }
    }

    suspend fun delete(lessonId: String) = withContext(Dispatchers.IO) {
        store.localAudioPath(lessonId)?.let { File(it).delete() }
        store.removeDownload(lessonId)
        // حذفٌ بيد المستخدم إشارة سلبية دائمة للتنزيل التلقائي وحده.
        store.markUserDeletedDownload(lessonId)
    }

    suspend fun deleteAll() {
        // ⚠️ كان يُنادى `delete` لكل درس على حدة: قراءةٌ كاملة للتفضيلات
        // وتحليل JSON وكتابةٌ ونبضة `revision` لكلّ واحد — أي مئات الكتابات
        // وإعادات التركيب لأمرٍ واحد. تمرير مجموعة فارغة يعني «لا صالح يبقى»
        // فتُحذف الملفّات كلّها ويُكتب الفهرس مرّة واحدة.
        withContext(Dispatchers.IO) { store.pruneDownloads(emptySet()) }
    }

    companion object {
        /// أدنى قدر من البايتات بين إصدارَي تقدّم حين لا تتغيّر النسبة
        /// (ملفّ بلا حجم معلن، أو ملفّ ضخم تبقى نسبته ثابتة طويلاً).
        private const val PROGRESS_EMIT_BYTES = 256L * 1024L

        /// هامش أمان فوق حجم الملف قبل بدء النقل (يغطي `.part` مؤقتاً + النظام).
        private const val SPACE_MARGIN_BYTES = 32L * 1024L * 1024L

        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context).also { instance = it }
        }
    }
}

