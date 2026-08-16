package com.ali.menbaradkshk.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * 🖼️ المصحف المصوَّر — إحداثيات الآيات على صفحات المصحف الورقي.
 *
 * **لماذا إحداثيات محلّية وصور من الشبكة؟** لأنّ الإحداثيات هي وحدها ما
 * يجعل الصورة «حيّة»: بها نعرف أين تقع كل آية على الورقة، فنُميّز الآية
 * الجارية أثناء التلاوة ونفتح التلاوة بالنقر عليها. وحجمها ١١٩ ك.ب فتُحزَم
 * مع التطبيق بلا أثر يُذكر. أمّا الصور فـ٥١ م.ب — حزمها في التطبيق يضاعف
 * حجمه بلا داعٍ، وأغلب الناس يقرأ سوراً معدودة، فتُجلب عند الطلب وتُخزَّن
 * في كاش الصور نفسه الذي يستعمله بقيّة التطبيق.
 *
 * والملفّ يُفكّ **مرّة واحدة** ويبقى في الذاكرة: ١٣٧٦٦ مستطيلاً في مصفوفات
 * `IntArray` مسطّحة (≈٢٧٥ ك.ب) لا كائنات، لأنّ كائناً لكل مستطيل يعني عشرات
 * الآلاف من الكائنات في الكومة وضغطاً على جامع المهملات مع كل تقليب صفحة.
 */
class MushafRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val loadLock = Mutex()

    /// مفكوكٌ لكل رواية على حدة — الروايات ثلاث، ولكلٍّ تخطيط صفحاتها.
    private val cache = mutableMapOf<String, Layout>()

    /**
     * تخطيط مصحف رواية: مستطيلات الآيات لكل صفحة (٦٠٤ صفحة، الفهرس 0 = صفحة 1).
     *
     * @param numbering `flat` ⇒ [ayahRef] فهرس مسطّح 0..6235 بعدّ حفص.
     *   `riwaya` ⇒ [ayahRef] هو `سورة×1000 + آية` **بعدّ الرواية نفسها**.
     */
    data class Layout(
        val pages: List<IntArray>,
        val numbering: String,
        /// مقاس صورة الصفحة بالبكسل **بعد قصّ الهوامش**. يختلف بين الروايات
        /// (٩٢٠×١٣٠١ لحفص، ٩٤٢×١٢٩٢ لورش…) فلا يصلح ثابتٌ واحد: نسبةٌ خاطئة
        /// تعني هامشاً مجهول القدر بين حافّة الصورة وحافّة الصندوق، فتنزلق كل
        /// مستطيلات الآيات عن مواضعها.
        val pageWidth: Float,
        val pageHeight: Float,
    ) {
        val isFlat: Boolean get() = numbering == "flat"

        /// نسبة العرض إلى الارتفاع — يبني بها العرضُ صندوقاً مطابقاً للصورة.
        val aspect: Float get() = pageWidth / pageHeight
    }

    suspend fun layout(riwayaId: String): Layout {
        cache[riwayaId]?.let { return it }
        return loadLock.withLock {
            cache[riwayaId] ?: withContext(Dispatchers.IO) {
                parse(readAsset(assetFor(riwayaId)))
            }.also { cache[riwayaId] = it }
        }
    }

    private fun readAsset(name: String): String =
        app.assets.open(name).use { raw ->
            GZIPInputStream(raw).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun parse(json: String): Layout {
        val root = JSONObject(json)
        val array = root.getJSONArray("pages")
        val result = ArrayList<IntArray>(array.length())
        for (p in 0 until array.length()) {
            val page = array.getJSONArray(p)
            val flat = IntArray(page.length() * MushafGeometry.STRIDE)
            for (r in 0 until page.length()) {
                val rect = page.getJSONArray(r)
                val base = r * MushafGeometry.STRIDE
                for (k in 0 until MushafGeometry.STRIDE) flat[base + k] = rect.getInt(k)
            }
            result.add(flat)
        }
        require(result.size == PAGE_COUNT) {
            "المصحف المصوَّر فيه ${result.size} صفحة بدل $PAGE_COUNT"
        }
        return Layout(
            pages = result,
            numbering = root.optString("numbering", "flat"),
            // القيم الافتراضية هي اللوحة قبل القصّ — احتياطٌ لو قُرئ ملفّ قديم.
            pageWidth = root.optDouble("pw", 1000.0).toFloat(),
            pageHeight = root.optDouble("ph", 1430.0).toFloat(),
        )
    }

    companion object {
        const val PAGE_COUNT = 604

        /**
         * ✅ صور صفحات المصحف الملوّن — **على استضافتنا** في Firebase Storage.
         *
         * مصدرها ملفّات **مجمع الملك فهد** الرسميّة (حفص ١٤٤١، ورش ١٤٤٢،
         * قالون ١٤٤٣)، وإذنُه المنشور يبيح استعمالها مجّاناً في البرامج
         * الحاسوبيّة داخل المملكة وخارجها؛ والقيد الوحيد طباعةٌ ورقيّة للبيع.
         *
         * ولم تُستهلك من خادم طرفٍ ثالث: استهلاك خدمة الغير أو إعادة نشر
         * محتواه بلا إذن مكتوب مخالفةٌ لسياسة متجر Play. فرُفعت مرّة واحدة
         * إلى تخزين المشروع نفسه الذي يستضيف الصوتيات.
         */
        const val IMAGE_BASE =
            "https://firebasestorage.googleapis.com/v0/b/mxqp-8d1e8.firebasestorage.app/o/quran%2F"

        /** رابط صفحة بعينها (1..604) من مصحف رواية. */
        fun pageUrl(riwayaId: String, page: Int): String {
            val number = page.coerceIn(1, PAGE_COUNT).toString().padStart(3, '0')
            return IMAGE_BASE + riwayaId + "%2F" + number + ".webp?alt=media"
        }

        /// ⚠️ الامتداد `.jz` لا `.gz` — أدوات البناء تفكّ ضغط أصول `.gz`
        /// تلقائياً فيختفي الملفّ الذي يطلبه الكود (انظر [QuranRepository]).
        private fun assetFor(riwayaId: String) = "quran/mushaf_$riwayaId.jz"

        /**
         * ✅ الروايات الثلاث كلّها لها مصحف مصوَّر ملوّن رسميّ.
         *
         * ولكلٍّ **تخطيطُ صفحاته وترقيمُ آياته**: عدّ ورش وقالون المدنيّ يقسم
         * آية الكرسي آيتين مثلاً. فلا تُخلط إحداثيات رواية بصور أخرى بحال —
         * وعرض رسمٍ تحت اسم رواية أخرى خطأٌ في كتاب الله لا عيبُ واجهة.
         */
        fun supportsRiwaya(riwayaId: String): Boolean = riwayaId in SUPPORTED

        private val SUPPORTED = setOf("hafs", "warsh", "qalun")

        @Volatile
        private var instance: MushafRepository? = null

        fun get(context: Context): MushafRepository =
            instance ?: synchronized(this) {
                instance ?: MushafRepository(context).also { instance = it }
            }
    }
}

object MushafGeometry {

    /// طول سجلّ المستطيل الواحد: `[آية, x1, y1, x2, y2]`.
    const val STRIDE = 5

    /// مقياس التطبيع في الملفّ: كل الإحداثيات نسبةٌ من 10000.
    const val SCALE = 10000

    /**
     * الآية التي تقع عندها النقطة (بإحداثيات مطبَّعة 0..10000).
     *
     * إن لم تقع النقطة داخل مستطيل — وهو الغالب، فبين الأسطر فراغ وفي حواف
     * الصفحة هوامش — نأخذ **أقرب سطر رأسياً ثم أقربه أفقياً**، لأنّ إصبع
     * القارئ يقع بين السطور كثيراً، ورفض النقرة صمتاً يجعل الميزة تبدو
     * معطّلة. `-1` لا تُرجَع إلا لصفحة بلا مستطيلات أصلاً.
     */
    fun ayahAt(page: IntArray, x: Int, y: Int): Int {
        if (page.isEmpty()) return -1
        var bestAyah = -1
        var bestCost = Long.MAX_VALUE
        var i = 0
        while (i < page.size) {
            val ayah = page[i]
            val x1 = page[i + 1]
            val y1 = page[i + 2]
            val x2 = page[i + 3]
            val y2 = page[i + 4]
            if (x in x1..x2 && y in y1..y2) return ayah
            // المسافة الرأسية تزن أضعاف الأفقية: السطر المجاور رأسياً آيةٌ
            // أخرى تماماً، أمّا البعد الأفقي داخل السطر نفسه فلا يعني شيئاً.
            val dy = when {
                y < y1 -> (y1 - y).toLong()
                y > y2 -> (y - y2).toLong()
                else -> 0L
            }
            val dx = when {
                x < x1 -> (x1 - x).toLong()
                x > x2 -> (x - x2).toLong()
                else -> 0L
            }
            val cost = dy * 100L + dx
            if (cost < bestCost) {
                bestCost = cost
                bestAyah = ayah
            }
            i += STRIDE
        }
        return bestAyah
    }

    /**
     * ينفّذ [action] على كل مستطيلات الآية في الصفحة.
     *
     * بمُعامِل دالّة لا بقائمة مُرجَعة: هذه تُستدعى في كل إطار رسم، وبناء
     * قائمة كائنات في كل مرّة يُخلّف قمامةً تُحسّ في التقليب.
     */
    inline fun forEachRect(page: IntArray, ayah: Int, action: (Int, Int, Int, Int) -> Unit) {
        var i = 0
        while (i < page.size) {
            if (page[i] == ayah) action(page[i + 1], page[i + 2], page[i + 3], page[i + 4])
            i += STRIDE
        }
    }

    /**
     * هل تقع النقطة **داخل** مستطيل آيةٍ فعلاً؟
     *
     * تُميّز نقرة «على الآية» من نقرة «على الهامش أو بين السطور» — و[ayahAt]
     * وحدها لا تكفي لأنّها تُرجع أقرب آية مهما بعُدت النقطة.
     */
    fun hitsAyah(page: IntArray, x: Int, y: Int): Boolean {
        var i = 0
        while (i < page.size) {
            if (x >= page[i + 1] && x <= page[i + 3] && y >= page[i + 2] && y <= page[i + 4]) {
                return true
            }
            i += STRIDE
        }
        return false
    }

    /** هل في هذه الصفحة شيء من الآية المعطاة؟ */
    fun containsAyah(page: IntArray, ayah: Int): Boolean {
        var i = 0
        while (i < page.size) {
            if (page[i] == ayah) return true
            i += STRIDE
        }
        return false
    }

    /**
     * رقم الصفحة (1..604) التي تقع فيها الآية ذات الفهرس المسطّح.
     *
     * [pageStarts] أوّل آية في كل صفحة بالترتيب (من `index.jz`)، فالبحث
     * ثنائيّ: آخر صفحة بدايتها ≤ الآية.
     */
    fun pageOfAyah(pageStarts: IntArray, flatAyah: Int): Int {
        if (pageStarts.isEmpty()) return 1
        var low = 0
        var high = pageStarts.size - 1
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pageStarts[mid] <= flatAyah) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer + 1
    }

    /** أوّل آية في الصفحة (1..604) — للانتقال من المصوَّر إلى المكتوب. */
    fun firstAyahOfPage(pageStarts: IntArray, page: Int): Int =
        pageStarts.getOrElse(page - 1) { 0 }

    /**
     * مرجع الآية في مصحف الرواية: `سورة×1000 + رقمها في السورة`.
     *
     * **لماذا هذا الترميز؟** لأنّ مصحفَي ورش وقالون يرقّمان بالعدّ المدنيّ لا
     * بعدّ حفص (٦٢١٤ آية لا ٦٢٣٦ — العدّ المدنيّ يقسم آية الكرسي آيتين مثلاً)،
     * فلا يوجد «فهرس مسطّح» واحد يصلح للجميع. والترميز بالسورة والآية يبقى
     * صحيحاً في الروايات كلّها.
     */
    fun riwayaRef(surah: Int, ayahInSurah: Int): Int = surah * 1000 + ayahInSurah

    /** رقم السورة من مرجعٍ بترميز الرواية. */
    fun surahOfRef(ref: Int): Int = ref / 1000

    /**
     * رقم الصفحة (1..604) التي تحوي هذا المرجع، أو `0` إن لم يوجد.
     *
     * بحثٌ خطّي على ٦٠٤ صفحة — رخيصٌ ويقع عند التبديل والفتح لا في كل إطار،
     * ولا يحتاج فهرساً ثانياً يجب أن يبقى متّسقاً مع التخطيط.
     */
    fun pageOfRef(pages: List<IntArray>, ref: Int): Int {
        for (index in pages.indices) {
            if (containsAyah(pages[index], ref)) return index + 1
        }
        return 0
    }
}
