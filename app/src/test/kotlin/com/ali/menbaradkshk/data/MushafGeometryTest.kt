package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات هندسة المصحف المصوَّر.
 *
 * **لماذا هذه بالذات؟** لأنّ خطأً فيها لا يظهر انهياراً بل تمييزاً على آية
 * غير التي تُتلى — وهو أسوأ من غياب الميزة، إذ يثق القارئ بما يراه.
 */
class MushafGeometryTest {

    /// صفحة مصطنعة: ثلاثة أسطر، الآية 5 على سطرين متتاليين والآية 6 على الثالث.
    private val page = intArrayOf(
        5, 1000, 1000, 9000, 1800,
        5, 1000, 2000, 9000, 2800,
        6, 1000, 3000, 9000, 3800,
    )

    @Test
    fun `النقر داخل مستطيل يعيد آيته`() {
        assertEquals(5, MushafGeometry.ayahAt(page, 5000, 1400))
        assertEquals(5, MushafGeometry.ayahAt(page, 5000, 2400))
        assertEquals(6, MushafGeometry.ayahAt(page, 5000, 3400))
    }

    @Test
    fun `النقر بين سطرين يعيد أقربهما رأسياً`() {
        // 2850 أقرب إلى سطر الآية 6 (يبدأ 3000) منه إلى سطر الآية 5 (ينتهي 2800).
        assertEquals(6, MushafGeometry.ayahAt(page, 5000, 2910))
        assertEquals(5, MushafGeometry.ayahAt(page, 5000, 2850))
        // عند التساوي التامّ يفوز الأعلى — قرارٌ ثابت لا عشوائيّ.
        assertEquals(5, MushafGeometry.ayahAt(page, 5000, 2900))
    }

    @Test
    fun `النقر في الهامش لا يُهمَل بل يُنسب لأقرب سطر`() {
        assertEquals(5, MushafGeometry.ayahAt(page, 200, 1400))
        assertEquals(6, MushafGeometry.ayahAt(page, 9900, 3400))
    }

    @Test
    fun `صفحة بلا مستطيلات تعيد ناقص واحد`() {
        assertEquals(-1, MushafGeometry.ayahAt(IntArray(0), 100, 100))
    }

    @Test
    fun `مستطيلات الآية الممتدّة تُرجَع كلّها`() {
        val collected = mutableListOf<Int>()
        MushafGeometry.forEachRect(page, 5) { _, y1, _, _ -> collected += y1 }
        assertEquals(listOf(1000, 2000), collected)
    }

    @Test
    fun `وجود الآية في الصفحة`() {
        assertTrue(MushafGeometry.containsAyah(page, 6))
        assertFalse(MushafGeometry.containsAyah(page, 7))
    }

    // ---- آية ⇒ صفحة ----

    private val starts = intArrayOf(0, 7, 12, 20, 33)

    @Test
    fun `أوّل آية في الصفحة تعيد صفحتها`() {
        assertEquals(1, MushafGeometry.pageOfAyah(starts, 0))
        assertEquals(2, MushafGeometry.pageOfAyah(starts, 7))
        assertEquals(5, MushafGeometry.pageOfAyah(starts, 33))
    }

    @Test
    fun `آية في وسط الصفحة تعيد صفحتها`() {
        assertEquals(1, MushafGeometry.pageOfAyah(starts, 3))
        assertEquals(2, MushafGeometry.pageOfAyah(starts, 11))
        assertEquals(4, MushafGeometry.pageOfAyah(starts, 32))
        assertEquals(5, MushafGeometry.pageOfAyah(starts, 999))
    }

    @Test
    fun `أوّل آية في صفحة معطاة`() {
        assertEquals(0, MushafGeometry.firstAyahOfPage(starts, 1))
        assertEquals(20, MushafGeometry.firstAyahOfPage(starts, 4))
        // خارج المدى لا يُسقط التطبيق — يعود إلى أوّل المصحف.
        assertEquals(0, MushafGeometry.firstAyahOfPage(starts, 900))
    }

    @Test
    fun `رابط الصفحة بثلاث خانات ولكل رواية مجلَّدها`() {
        assertEquals(
            MushafRepository.IMAGE_BASE + "hafs%2F001.webp?alt=media",
            MushafRepository.pageUrl("hafs", 1),
        )
        assertEquals(
            MushafRepository.IMAGE_BASE + "warsh%2F604.webp?alt=media",
            MushafRepository.pageUrl("warsh", 604),
        )
        // خارج المدى يُحصر بدل أن يُنتج رابطاً ميّتاً.
        assertEquals(
            MushafRepository.pageUrl("qalun", 604),
            MushafRepository.pageUrl("qalun", 900),
        )
    }

    /**
     * ⛔ الروايات الثلاث لها مصاحف مصوَّرة رسميّة — ولا رابعة.
     *
     * وهذا حارسٌ لا زينة: عرض صفحةِ رواية تحت اسم أخرى نسبةُ رسمٍ إلى غير
     * روايته، وهو خطأ في كتاب الله لا عيبُ واجهة.
     */
    @Test
    fun `المصحف المصوَّر للروايات الثلاث ولا رابعة`() {
        assertTrue(MushafRepository.supportsRiwaya("hafs"))
        assertTrue(MushafRepository.supportsRiwaya("warsh"))
        assertTrue(MushafRepository.supportsRiwaya("qalun"))
        assertFalse(MushafRepository.supportsRiwaya("douri"))
        assertFalse(MushafRepository.supportsRiwaya(""))
    }

    /** ترميز مرجع الرواية وفكّه — جسر الترقيمين. */
    @Test
    fun `مرجع الرواية يُرمَّز ويُفكّ بلا خسارة`() {
        assertEquals(2255, MushafGeometry.riwayaRef(2, 255))
        assertEquals(2, MushafGeometry.surahOfRef(2255))
        assertEquals(114, MushafGeometry.surahOfRef(MushafGeometry.riwayaRef(114, 6)))
        // البقرة ٢٨٦ — أطول سورة، فحدُّ الآلاف يكفيها وزيادة.
        assertEquals(2, MushafGeometry.surahOfRef(MushafGeometry.riwayaRef(2, 286)))
    }

    @Test
    fun `صفحة المرجع تُوجد أو تعود صفراً بلا انهيار`() {
        val pages = listOf(
            intArrayOf(2253, 10, 10, 90, 20),
            intArrayOf(2254, 10, 10, 90, 20, 2255, 10, 30, 90, 40),
        )
        assertEquals(1, MushafGeometry.pageOfRef(pages, 2253))
        assertEquals(2, MushafGeometry.pageOfRef(pages, 2255))
        assertEquals(0, MushafGeometry.pageOfRef(pages, 9999))
        assertEquals(0, MushafGeometry.pageOfRef(emptyList(), 2253))
    }

    @Test
    fun `الرحلة كاملة - آية ثم صفحتها ثم أوّل آية فيها`() {
        val page = MushafGeometry.pageOfAyah(starts, 15)
        assertEquals(3, page)
        assertEquals(12, MushafGeometry.firstAyahOfPage(starts, page))
    }
}
