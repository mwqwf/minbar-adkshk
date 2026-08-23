package com.ali.menbaradkshk.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * ⬇️ تنزيل تلاوة سورة للعمل **بلا إنترنت**.
 *
 * **لماذا بالسورة لا بالمصحف كلّه؟** المصحف الكامل لقارئ واحد يقارب
 * الغيغابايت، وتنزيله زرٌّ واحدٌ يملأ هاتف المستخدم بلا أن يشعر — وهذا يخالف
 * قاعدة «لا يضرّ جهاز المستخدم» مخالفةً صريحة. أمّا السورة فوحدة الاستماع
 * الطبيعيّة: من يريد الكهف كلّ جمعة ينزّلها مرّة، ولا يدفع ثمن الباقي.
 *
 * والتخزين منفصل تماماً عن «تنزيلاتي» (فهرس الدروس): تلاوات المصحف لا تظهر
 * هناك ولا تختلط بإحصاءات الدروس — كما لا تختلط في المشغّل (انظر
 * `PlaybackService.isLesson`).
 */
class QuranDownloadRepository private constructor(context: Context) {

    private val app = context.applicationContext

    /** حالة التنزيل الجاري — سورة واحدة في كل مرّة يكفي ويبقي الأمر بسيطاً. */
    data class Progress(
        val reciterId: String,
        val surah: Int,
        val done: Int,
        val total: Int,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /// يُرفع عند اكتمال/حذف تنزيل كي تُعيد الواجهة قراءة الحالة من القرص.
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /// ⚠️ نطاق خاصّ بالحذف: `deleteRecursively` على تنزيلات قارئٍ «آية بآية»
    /// يمرّ على آلاف الملفّات، وكان يقع على خيط الواجهة من نقرة التأكيد
    /// مباشرةً فتتجمّد الشاشة. والنطاق مستقلّ عن الشاشة كي لا يُقطع حذفٌ بدأ.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /// يحذف على خيط الإدخال/الإخراج ثمّ يرفع المراجعة — الترتيب مقصود كي
    /// تُعيد الواجهة حساب الحجم بعد اختفاء الملفّات لا قبله.
    private fun purge(block: () -> Unit) {
        ioScope.launch {
            block()
            _revision.value++
        }
    }

    private fun rootFor(reciterId: String): File =
        File(File(app.filesDir, DIR), reciterId)

    private fun dirFor(reciterId: String, surah: Int): File =
        File(rootFor(reciterId), surah.toString())

    private fun fileFor(reciterId: String, surah: Int, ayah: Int): File =
        File(dirFor(reciterId, surah), "$ayah.mp3")

    /** المسار المحلّي لآية إن كانت منزَّلة، وإلا `null`. */
    fun localAyah(reciterId: String, surah: Int, ayah: Int): String? =
        fileFor(reciterId, surah, ayah).takeIf { it.isFile && it.length() > 0L }?.absolutePath

    /**
     * هل السورة منزَّلة كاملةً لهذا القارئ؟
     *
     * نعدّ الملفات لا نثق بعلامةٍ محفوظة: حذف المستخدم لبيانات التطبيق أو
     * تنظيف النظام للمساحة يترك العلامة كاذبةً فيُشغَّل صمتٌ بلا إنترنت.
     *
     * ⚠️ العدد المتوقَّع يتبع نمط القارئ لا عدد آيات السورة: قارئ «السورة
     * كاملة» ينزّل ملفاً واحداً باسم `1.mp3` (انظر [downloadSurah])، فلو
     * طالبناه بكل الآيات لبقيت سورته «غير منزَّلة» أبداً — فلا تظهر علامة
     * الاكتمال، ويُعاد فحصها في كل تنزيل شامل بلا داعٍ.
     */
    fun isSurahDownloaded(reciter: Reciter, surah: Int, ayahs: Int): Boolean {
        val dir = dirFor(reciter.id, surah)
        if (!dir.isDirectory) return false
        val expected = if (reciter.perAyah) ayahs else 1
        return (1..expected).all {
            fileFor(reciter.id, surah, it).let { f -> f.isFile && f.length() > 0L }
        }
    }

    /** حجم ما نُزّل لهذا القارئ بالبايت (لعرضه في الإعدادات وللحذف). */
    fun bytesFor(reciterId: String): Long =
        rootFor(reciterId).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteSurah(reciterId: String, surah: Int) = purge {
        dirFor(reciterId, surah).deleteRecursively()
        // مجلّد القارئ الفارغ يُزال أيضاً كي لا تتراكم مجلّدات خاوية.
        rootFor(reciterId).takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    }

    /**
     * حذف تنزيلات **قارئ واحد**.
     *
     * ⚠️ هذا ما تحتاجه الواجهة عملياً: الحجم المعروض في الحوار محسوبٌ
     * بـ[bytesFor] لقارئٍ بعينه، فلو حذفنا الجميع لكان الوعد المكتوب مخالفاً
     * للفعل — يخسر المستخدم تنزيلاتِ قرّاء لم يسأله أحدٌ عنهم.
     */
    fun deleteReciter(reciterId: String) = purge {
        rootFor(reciterId).deleteRecursively()
    }

    /// حذف تلاوات **كل** القرّاء — لا تستعملها إلا حيث يقول النصّ ذلك صراحةً.
    fun deleteAll() = purge {
        File(app.filesDir, DIR).deleteRecursively()
    }

    /**
     * ينزّل تلاوة السورة كاملةً.
     *
     * يتخطّى ما نُزّل أصلاً فيصلح للاستئناف بعد انقطاع، ويكتب إلى ملفّ مؤقّت
     * ثم يعيد التسمية — فلا يبقى ملفّ نصفيّ يُشغَّل مبتوراً إن قُطع الاتصال.
     */
    suspend fun downloadSurah(
        reciter: Reciter,
        surahNumber: Int,
        ayahs: Int,
    ) = withContext(Dispatchers.IO) {
        val urls: List<Pair<Int, String>> = if (reciter.perAyah) {
            (1..ayahs).map { it to reciter.ayahUrl(surahNumber, it) }
        } else {
            // قارئ بملفّ سورة كاملة: ملفّ واحد نخزّنه بالرقم 1.
            listOf(1 to reciter.surahUrl(surahNumber))
        }
        val dir = dirFor(reciter.id, surahNumber).apply { mkdirs() }
        _progress.value = Progress(reciter.id, surahNumber, 0, urls.size)
        try {
            urls.forEachIndexed { i, (ayah, url) ->
                coroutineContext.ensureActive()
                val target = File(dir, "$ayah.mp3")
                if (!(target.isFile && target.length() > 0L)) fetchWithRetry(url, target)
                _progress.value = Progress(reciter.id, surahNumber, i + 1, urls.size)
            }
            _revision.value++
        } finally {
            _progress.value = null
        }
    }

    // ---- 🖼️ صور المصحف المصوَّر ----

    /** حالة تنزيل صور الصفحات — عمليّة واحدة في كل مرّة، كتنزيل التلاوة. */
    data class PageProgress(val done: Int, val total: Int) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    private val _pageProgress = MutableStateFlow<PageProgress?>(null)
    val pageProgress: StateFlow<PageProgress?> = _pageProgress.asStateFlow()

    /// ⚠️ مجلَّد لكل رواية: مصاحفها ثلاثة مختلفة تخطيطاً وترقيماً، فخلط
    /// صفحاتها في مجلَّد واحد يعني عرض صفحة رواية تحت اسم أخرى.
    private fun pagesDir(riwayaId: String): File =
        File(File(app.filesDir, PAGES_DIR), riwayaId)

    private fun pageFile(riwayaId: String, page: Int): File =
        File(pagesDir(riwayaId), "$page.webp")

    /**
     * المسار المحلّي لصورة صفحة إن كانت منزَّلة، وإلا `null`.
     *
     * تُستعمل كنموذج لـcoil مباشرةً: الملفّ المحلّي يسبق الشبكة دائماً فتعمل
     * الصفحة بلا إنترنت ولا تُستهلك بيانات لمن نزّلها مرّة.
     */
    fun localPage(riwayaId: String, page: Int): String? =
        pageFile(riwayaId, page).takeIf { it.isFile && it.length() > 0L }?.absolutePath

    /** عدد الصفحات المنزَّلة فعلاً لرواية — يُعدّ من القرص لا من علامة محفوظة. */
    /// ⚠️ `.webp` حصراً: الملفّات الجزئيّة `‎.part` صارت **تبقى** بعد الانقطاع
    /// (انظر [fetch])، فعدُّ كل ما في المجلّد كان سيُظهر صفحاتٍ لم تكتمل.
    fun downloadedPageCount(riwayaId: String): Int =
        pagesDir(riwayaId).listFiles()
            ?.count { it.isFile && it.length() > 0L && it.name.endsWith(".webp") } ?: 0

    /** حجم صور المصحف المنزَّلة بالبايت لرواية بعينها. */
    fun pagesBytes(riwayaId: String): Long =
        pagesDir(riwayaId).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deletePages(riwayaId: String) = purge {
        pagesDir(riwayaId).deleteRecursively()
    }

    /**
     * ينزّل صور المصحف المصوَّر كلّها (٦٠٤ صفحة، ≈٥١ م.ب).
     *
     * يتخطّى المنزَّل أصلاً فيُكمل بعد الانقطاع، وقابلة للإلغاء في أي لحظة —
     * بنفس أسلوب تنزيل التلاوة تماماً كي لا يتعلّم المستخدم نظامين.
     */
    suspend fun downloadMushafPages(riwayaId: String) = withContext(Dispatchers.IO) {
        val dir = pagesDir(riwayaId).apply { mkdirs() }
        val total = MushafRepository.PAGE_COUNT
        _pageProgress.value = PageProgress(0, total)
        try {
            for (page in 1..total) {
                coroutineContext.ensureActive()
                val target = File(dir, "$page.webp")
                if (!(target.isFile && target.length() > 0L)) {
                    fetchWithRetry(MushafRepository.pageUrl(riwayaId, page), target)
                }
                _pageProgress.value = PageProgress(page, total)
            }
            _revision.value++
        } finally {
            _pageProgress.value = null
            _revision.value++
        }
    }

    /**
     * جلب ملفّ واحد إلى مسار مؤقّت ثم إعادة تسميته.
     *
     * ⚠️ **النسخ يدويّ بفحص إلغاء مع كل حزمة** لا `input.copyTo`: إلغاء
     * الكوروتين لا يقاطع خيطاً محجوزاً في `read()`، وكان الفحص الوحيد **بين**
     * الملفّات — فمن ضغط «إيقاف» على تنزيل قارئٍ بملفّ سورة كاملة (عشرات
     * الميغابايتات في ملفّ واحد) تُقال له «أُوقف التنزيل» بينما بياناته تُستهلك
     * إلى آخر بايت، ثم يُحذف المؤقّت فيذهب كلّه هدراً. (القاعدة نفسها مطبَّقة
     * في [DownloadRepository].)
     */
    private suspend fun fetch(url: String, target: File) {
        val temp = File(target.parentFile, target.name + ".part")
        var connection: HttpURLConnection? = null
        // 📶 الملفّ الجزئي **يبقى** عند الانقطاع لا يُمحى.
        //
        // ⚠️ كان `finally { temp.delete() }` غير مشروط: كلّ انقطاعٍ يمحو ما
        // نُزّل فيُعاد من الصفر — وسورةٌ بملفّ واحد قد تبلغ عشرات
        // الميغابايتات، فعلى إنترنتٍ ضعيف لا تكتمل أبداً مهما أعاد المستخدم.
        // والحذف يبقى في حالتين فقط: الإلغاء الصريح (المستخدم أوقف)، والنجاح
        // (فقد صار الملفّ باسمه النهائي).
        var keepPartial = false
        try {
            val existing = if (temp.isFile) temp.length() else 0L
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "MinbarAdkassahk/${com.ali.menbaradkshk.BuildConfig.VERSION_NAME}",
                )
                // استئنافٌ من البايت نفسه لا من أوّله.
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            // 206 وحده يعني أن الخادم قبل الاستئناف؛ و200 مع طلب Range يعني
            // أنّه تجاهله وأرسل الملفّ كاملاً، فنكتب من الصفر لا فوق الجزئي.
            val append = code == 206 && existing > 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (temp.length() <= 0L) throw java.io.IOException("ملفّ فارغ")
            if (!temp.renameTo(target)) throw java.io.IOException("تعذّر حفظ الملف")
        } catch (cancelled: CancellationException) {
            // إلغاء صريح: لا نُبقي أثراً — المستخدم طلب التوقّف لا التأجيل.
            runCatching { temp.delete() }
            throw cancelled
        } catch (failure: Throwable) {
            keepPartial = true
            throw failure
        } finally {
            if (!keepPartial) runCatching { temp.delete() }
            connection?.disconnect()
        }
    }

    /**
     * ⚠️ فشل آية واحدة كان يُسقط السورة كلّها.
     *
     * على إنترنتٍ ضعيف تتعثّر آيةٌ من مئة لسببٍ عابر، فيضيع تنزيلٌ كاد يكتمل
     * ويعود المستخدم إلى أوّله. محاولاتٌ محدودة بمهلةٍ متزايدة تعالج العابر
     * بلا أن تُخفي العطل الحقيقي (بعدها يُرمى الاستثناء كما كان).
     */
    private suspend fun fetchWithRetry(url: String, target: File) {
        var attempt = 0
        while (true) {
            try {
                fetch(url, target)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                attempt++
                if (attempt >= FETCH_ATTEMPTS) throw failure
                kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    companion object {
        private const val DIR = "quran_audio"
        private const val PAGES_DIR = "quran_pages"

        /// عدد محاولات جلب الملفّ الواحد قبل إسقاط التنزيل.
        private const val FETCH_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_500L

        @Volatile
        private var instance: QuranDownloadRepository? = null

        fun get(context: Context): QuranDownloadRepository =
            instance ?: synchronized(this) {
                instance ?: QuranDownloadRepository(context).also { instance = it }
            }
    }
}
