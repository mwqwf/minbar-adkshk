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
 * 🕌 المصحف الكامل — بيانات محلّية بالكامل داخل الحزمة.
 *
 * **لماذا محلّي لا من الشبكة؟** لأنّ المصحف يُقرأ في المسجد وفي السفر وحيث
 * لا شبكة، ولأنّ نصّ القرآن لا يتغيّر فجلبه من خادم كلفةٌ متكرّرة بلا فائدة.
 * والملفات مضغوطة بـgzip وتُقرأ **كسلاً**: لا يُفكّ ضغط رواية إلا حين
 * يفتحها المستخدم فعلاً، فالإقلاع لا يتأثّر والذاكرة لا تحمل ما لا يُقرأ.
 *
 * الصوت وحده من الشبكة (ملفّ لكل آية)، ويمرّ بكاش المشغّل نفسه.
 */
class QuranRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val loadLock = Mutex()

    @Volatile
    private var index: QuranIndex? = null
    private val texts = mutableMapOf<String, List<String>>()

    /** فهرس المصحف (سور/أجزاء/أحزاب/صفحات/روايات). يُقرأ مرّة واحدة. */
    suspend fun index(): QuranIndex {
        index?.let { return it }
        return loadLock.withLock {
            index ?: withContext(Dispatchers.IO) { parseIndex(readAsset(INDEX_ASSET)) }
                .also { index = it }
        }
    }

    /**
     * نصّ رواية بعينها: مصفوفة مسطّحة 6236 آية بترتيب المصحف.
     *
     * يُحتفظ بالمفكوك في الذاكرة لأنّ التنقّل بين السور يُعيد طلبه فوراً،
     * وفكّ الضغط في كل مرّة كان يُظهر تقطيعاً محسوساً عند التمرير.
     */
    suspend fun text(riwayaId: String): List<String> {
        texts[riwayaId]?.let { return it }
        return loadLock.withLock {
            texts[riwayaId] ?: run {
                val riwaya = index().riwayat.firstOrNull { it.id == riwayaId }
                    ?: throw IllegalArgumentException("رواية غير معروفة: $riwayaId")
                val parsed = withContext(Dispatchers.IO) {
                    parseText(readAsset("$DIR/${riwaya.textAsset}"))
                }
                require(parsed.size == AYAH_COUNT) {
                    "نصّ الرواية $riwayaId فيه ${parsed.size} آية بدل $AYAH_COUNT"
                }
                texts[riwayaId] = parsed
                parsed
            }
        }
    }

    private fun readAsset(path: String): String =
        app.assets.open(path).use { raw ->
            GZIPInputStream(raw).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun parseIndex(json: String): QuranIndex {
        val root = JSONObject(json)
        fun marks(key: String): List<QuranMark> {
            val array = root.optJSONArray(key) ?: JSONArray()
            return (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                QuranMark(number = item.getInt("n"), start = item.getInt("start"))
            }
        }
        val surahArray = root.getJSONArray("surahs")
        val surahs = (0 until surahArray.length()).map { i ->
            val item = surahArray.getJSONObject(i)
            Surah(
                number = item.getInt("n"),
                name = item.getString("name"),
                ayahs = item.getInt("ayahs"),
                makki = item.optString("place") == "makki",
                start = item.getInt("start"),
                page = item.optInt("page", 1),
            )
        }
        val riwayaArray = root.getJSONArray("riwayat")
        val riwayat = (0 until riwayaArray.length()).map { i ->
            val item = riwayaArray.getJSONObject(i)
            val reciterArray = item.optJSONArray("reciters") ?: JSONArray()
            Riwaya(
                id = item.getString("id"),
                name = item.getString("name"),
                textAsset = item.getString("text"),
                reciters = (0 until reciterArray.length()).map { j ->
                    val reciter = reciterArray.getJSONObject(j)
                    Reciter(
                        id = reciter.getString("id"),
                        name = reciter.getString("name"),
                        base = reciter.getString("base"),
                        perAyah = reciter.optString("mode", "ayah") == "ayah",
                    )
                },
            )
        }
        require(surahs.size == SURAH_COUNT) { "الفهرس فيه ${surahs.size} سورة بدل $SURAH_COUNT" }
        require(riwayat.isNotEmpty()) { "الفهرس بلا روايات" }
        return QuranIndex(
            surahs = surahs,
            juzs = marks("juzs"),
            hizbs = marks("hizbs"),
            pages = marks("pages"),
            riwayat = riwayat,
        )
    }

    private fun parseText(json: String): List<String> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }

    companion object {
        const val AYAH_COUNT = 6236
        const val SURAH_COUNT = 114

        /// الرواية الافتراضية — قرار صريح من صاحب التطبيق: حفص هي الرئيسيّة.
        const val DEFAULT_RIWAYA = "hafs"

        private const val DIR = "quran"

        /**
         * ⚠️ الامتداد `.jz` لا `.json.gz` — وهذا **إلزاميّ لا تجميل**:
         * أدوات بناء أندرويد تعامل أصول `.gz` معاملة خاصّة فتفكّ ضغطها عند
         * التحزيم وتحذف اللاحقة، فيختفي الملف الذي يطلبه الكود ويكبر حجم
         * الحزمة. بامتداد محايد تصل البايتات كما هي (٨١٢ ك.ب بدل ١.٣ م.ب).
         */
        private const val INDEX_ASSET = "$DIR/index.jz"

        @Volatile
        private var instance: QuranRepository? = null

        fun get(context: Context): QuranRepository =
            instance ?: synchronized(this) {
                instance ?: QuranRepository(context).also { instance = it }
            }
    }
}

// ---- النماذج ----

data class QuranIndex(
    val surahs: List<Surah>,
    val juzs: List<QuranMark>,
    val hizbs: List<QuranMark>,
    val pages: List<QuranMark>,
    val riwayat: List<Riwaya>,
) {
    /** السورة التي تقع فيها الآية ذات الفهرس المسطّح المعطى. */
    fun surahAt(flatIndex: Int): Surah =
        surahs.lastOrNull { flatIndex >= it.start } ?: surahs.first()

    /** الرواية المطلوبة، وإلا فالافتراضيّة، وإلا فأوّل المتاح. */
    fun riwaya(id: String): Riwaya =
        riwayat.firstOrNull { it.id == id }
            ?: riwayat.firstOrNull { it.id == QuranRepository.DEFAULT_RIWAYA }
            ?: riwayat.first()
}

data class Surah(
    val number: Int,
    val name: String,
    val ayahs: Int,
    val makki: Boolean,
    /// فهرس أوّل آية من السورة في المصفوفة المسطّحة (يبدأ من 0).
    val start: Int,
    val page: Int,
) {
    val endExclusive: Int get() = start + ayahs
    val placeLabel: String get() = if (makki) "مكّية" else "مدنيّة"
}

/// علامة فهرسة عامّة (جزء/حزب/صفحة): رقمها وأوّل آية فيها.
data class QuranMark(val number: Int, val start: Int)

data class Riwaya(
    val id: String,
    val name: String,
    val textAsset: String,
    val reciters: List<Reciter>,
) {
    /**
     * القارئ المبدئيّ لهذه الرواية.
     *
     * **يُفضَّل قارئ «آية بآية» دائماً** لأنّه وحده من يتيح تمييز الآية
     * الجارية والبدء من آية بعينها — وهما جوهر الصفحة. ولولا هذا التفضيل
     * لظهرت الميزة معطَّلةً لمن لم يختر قارئاً بنفسه، فيظنّها لا تعمل أصلاً.
     */
    val defaultReciter: Reciter?
        get() = reciters.firstOrNull { it.perAyah } ?: reciters.firstOrNull()

    /** هل في هذه الرواية قارئ يدعم التمييز آية بآية؟ */
    val hasPerAyah: Boolean get() = reciters.any { it.perAyah }
}

data class Reciter(
    val id: String,
    val name: String,
    /// جذر روابط الصوت، ينتهي بشرطة مائلة.
    val base: String,
    /// `true` ⇒ ملفّ لكل آية (يسمح بتمييز الآية الجارية بلا توقيتات).
    /// `false` ⇒ ملفّ لكل سورة (لا تمييز آية بآية).
    val perAyah: Boolean,
) {
    /** رابط آية بعينها: `base + SSSAAA.mp3` (أرقام بثلاث خانات). */
    fun ayahUrl(surah: Int, ayah: Int): String =
        base + pad(surah) + pad(ayah) + ".mp3"

    /** رابط سورة كاملة: `base + SSS.mp3`. */
    fun surahUrl(surah: Int): String = base + pad(surah) + ".mp3"

    private fun pad(value: Int): String = value.toString().padStart(3, '0')
}
