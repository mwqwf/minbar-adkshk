package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات بحث المصحف — المنطق كلّه خالص، فيُختبَر بلا جهاز ولا واجهة.
 *
 * والاختبارات مكتوبة بأسئلة المستخدم لا بأسماء الدوال: «من كتب ٥ ماذا يُعرض
 * عليه؟»، «من كتب نور هل يجد النور قبل التكوير؟».
 */
class QuranSearchTest {

    private fun surah(n: Int, name: String, ayahs: Int, start: Int) =
        Surah(number = n, name = name, ayahs = ayahs, makki = true, start = start, page = 1)

    private val index = QuranIndex(
        surahs = listOf(
            surah(1, "الفاتحة", 7, 0),
            surah(2, "البقرة", 286, 7),
            surah(5, "المائدة", 120, 293),
            surah(24, "النور", 64, 413),
            surah(81, "التكوير", 29, 477),
        ),
        juzs = listOf(QuranMark(1, 0), QuranMark(5, 100)),
        hizbs = emptyList(),
        pages = listOf(QuranMark(1, 0), QuranMark(5, 60)),
        riwayat = listOf(Riwaya("hafs", "حفص", "h.jz", emptyList())),
    )

    @Test
    fun `الرقم وحده يعرض السورة والجزء والصفحة`() {
        val hits = searchQuranIndex(index, "5")
        assertEquals(3, hits.size)
        assertTrue(hits[0] is QuranHit.SurahHit)
        assertEquals("الجزء", (hits[1] as QuranHit.MarkHit).label)
        assertEquals("صفحة", (hits[2] as QuranHit.MarkHit).label)
    }

    @Test
    fun `الأرقام العربية الهندية تعمل كاللاتينية`() {
        assertEquals(searchQuranIndex(index, "5").size, searchQuranIndex(index, "٥").size)
        assertEquals(
            5,
            (searchQuranIndex(index, "٥").first() as QuranHit.SurahHit).surah.number,
        )
    }

    @Test
    fun `الكلمة الموجهة تحصر النتيجة في نوعها`() {
        val juz = searchQuranIndex(index, "جزء ٥")
        assertEquals(1, juz.size)
        assertEquals(100, (juz.first() as QuranHit.MarkHit).start)

        val page = searchQuranIndex(index, "صفحة 5")
        assertEquals(1, page.size)
        assertEquals(60, (page.first() as QuranHit.MarkHit).start)

        val onlySurah = searchQuranIndex(index, "سورة 5")
        assertEquals(1, onlySurah.size)
        assertEquals(5, (onlySurah.first() as QuranHit.SurahHit).surah.number)
    }

    @Test
    fun `رقم خارج المدى لا يعطي نتيجة كاذبة`() {
        assertTrue(searchQuranIndex(index, "700").isEmpty())
    }

    @Test
    fun `البحث بالاسم يتجاهل التشكيل ويقدّم البادئة على المتضمَّن`() {
        val hits = searchQuranIndex(index, "نور").filterIsInstance<QuranHit.SurahHit>()
        assertEquals(listOf(24), hits.map { it.surah.number })

        // «الفاتحه» بهاء و«الفاتحة» بتاء مربوطة سواء بعد التطبيع.
        assertEquals(
            1,
            searchQuranIndex(index, "الفاتحه").filterIsInstance<QuranHit.SurahHit>().size,
        )
    }

    @Test
    fun `كلمة سورة قبل الاسم لا تمنع المطابقة`() {
        val hits = searchQuranIndex(index, "سورة البقرة").filterIsInstance<QuranHit.SurahHit>()
        assertEquals(listOf(2), hits.map { it.surah.number })
    }

    @Test
    fun `أرقام الآيات تُعرض بالصورة الهنديّة`() {
        assertEquals("١", arabicIndicDigits(1))
        assertEquals("٢٨٦", arabicIndicDigits(286))
        // ودورةٌ كاملة: ما كُتب هندياً يُقرأ لاتينياً في البحث.
        assertEquals("286", toWesternDigits(arabicIndicDigits(286)))
    }

    @Test
    fun `البحث في نص الآيات يطابق بلا تشكيل ويحترم الحدّ`() {
        val text = listOf(
            "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
            "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ",
            "الرَّحْمَٰنِ الرَّحِيمِ",
        )
        val hits = searchQuranText(text, "الرحمن")
        assertEquals(listOf(0, 2), hits.map { it.flat })

        assertEquals(1, searchQuranText(text, "الرحمن", limit = 1).size)
        // حرفان لا يكفيان: نتيجة بلا معنى وكلفة بلا مقابل.
        assertTrue(searchQuranText(text, "ال").isEmpty())
    }

    /**
     * ⛔ حارس الرسم العثماني — كل سطر هنا **فشل فعلاً** قبل الإصلاح.
     *
     * الآيات أدناه منسوخة حرفياً من أصول التطبيق (`text_*.jz`)، والاستعلامات
     * مكتوبة كما يكتبها الناس بالإملاء المعاصر. وهذا هو جوهر المشكلة: بين
     * الرسم وكتابة الناس أربعة فروق منهجيّة، وكلٌّ منها كان يُصفّر النتيجة.
     */
    @Test
    fun `الرسم العثماني يُطابق ما يكتبه الناس بالإملاء المعاصر`() {
        // الألف الخنجريّة: حذفها كان يعطي «الصرط» فلا يجد كاتبُ «الصراط».
        assertFound("ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ", "الصراط المستقيم")
        // `وٰ` تنوب عن الألف — «الصلاة» و«الزكاة» كانتا صفراً.
        assertFound("وَأَقِيمُوا۟ ٱلصَّلَوٰةَ وَءَاتُوا۟ ٱلزَّكَوٰةَ", "الصلاة")
        assertFound("وَأَقِيمُوا۟ ٱلصَّلَوٰةَ وَءَاتُوا۟ ٱلزَّكَوٰةَ", "الزكاة")
        // الهمزة المفردة، والمسافة: `يَٰٓأَيُّهَا` كلمة واحدة والناس كلمتان.
        assertFound("يَٰٓأَيُّهَا ٱلَّذِينَ ءَامَنُوا۟", "يا ايها الذين امنوا")
        // الهمزة على نبرة.
        assertFound("يَٰبَنِىٓ إِسْرَٰٓءِيلَ", "إسرائيل")
        // `ىٰ` ألفاً هنا…
        assertFound("وَٱلشَّمْسِ وَضُحَىٰهَا", "وضحاها")
        assertFound("وَٱلضُّحَىٰ", "والضحى")
        // …وياءً هنا. الوجهان لازمان معاً، وقاعدةٌ واحدة تكسر أحدهما.
        assertFound("تَبَارَكَ ٱلَّذِى بِيَدِهِ ٱلْمُلْكُ", "الذي بيده الملك")
        assertFound("وَٱلَّتِىٓ أَحْصَنَتْ فَرْجَهَا", "والتي")
        // الياء البرّيّة — ترد في مصحفَي ورش وقالون دون حفص.
        assertFound("لَّهُۥ مَا فِے اِ۬لسَّمَٰوَٰتِ", "في السماوات")
        // ورش وقالون: همزات الوصل الخاصّة.
        assertFound("اِ۬لْحَمْدُ لِلهِ رَبِّ اِ۬لْعَٰلَمِينَ", "الحمد لله رب العالمين")
    }

    /** لا يجوز أن يتّسع البحث حتى يطابق ما ليس فيه. */
    @Test
    fun `البحث لا يطابق ما ليس في الآية`() {
        val ayah = listOf("قُلْ هُوَ ٱللَّهُ أَحَدٌ")
        assertTrue(searchQuranText(ayah, "الرحمن").isEmpty())
        assertTrue(searchQuranText(ayah, "الصلاة").isEmpty())
    }

    private fun assertFound(ayah: String, query: String) {
        assertEquals(
            "«$query» لم تُوجد في: $ayah",
            1,
            searchQuranText(listOf(ayah), query).size,
        )
    }
}
