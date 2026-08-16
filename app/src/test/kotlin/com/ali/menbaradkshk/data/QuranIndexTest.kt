package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات «أين تقع الآية» — أخطر محور في الميزة كلّها.
 *
 * خطأٌ هنا لا يظهر انهياراً بل **آيةً تُنسب إلى غير سورتها**: عنوانٌ خاطئ
 * فوق نتيجة بحث، أو رقم آية مزاح بواحد في «تابع القراءة» و«علاماتي»
 * والأجزاء والأحزاب — وكلّها تحسب موضعها بالمعادلة نفسها
 * `flat - surah.start + 1`. فتُثبَّت المعادلة وحدودها باختبار لا بمراجعة.
 */
class QuranIndexTest {

    private fun surah(n: Int, name: String, ayahs: Int, start: Int) =
        Surah(number = n, name = name, ayahs = ayahs, makki = true, start = start, page = 1)

    /// حدود حقيقيّة من المصحف: الفاتحة ٧، البقرة ٢٨٦، التوبة (بلا بسملة)،
    /// والناس آخر المصحف (فهرسها ينتهي عند 6235).
    private val index = QuranIndex(
        surahs = listOf(
            surah(1, "الفاتحة", 7, 0),
            surah(2, "البقرة", 286, 7),
            surah(9, "التوبة", 129, 1235),
            surah(114, "الناس", 6, 6230),
        ),
        juzs = emptyList(),
        hizbs = emptyList(),
        pages = emptyList(),
        riwayat = listOf(Riwaya("hafs", "حفص", "h.jz", emptyList())),
    )

    @Test
    fun `أوّل آية وآخر آية في كل سورة تُنسبان إليها`() {
        for (s in index.surahs) {
            assertEquals(s.number, index.surahAt(s.start).number)
            assertEquals(s.number, index.surahAt(s.start + s.ayahs - 1).number)
        }
    }

    @Test
    fun `الحدّ بين سورتين لا يزحف بآية`() {
        // آخر الفاتحة (6) ثم أوّل البقرة (7) — أشهر موضع يقع فيه خطأ الإزاحة.
        assertEquals(1, index.surahAt(6).number)
        assertEquals(2, index.surahAt(7).number)
    }

    @Test
    fun `رقم الآية المعروض يبقى داخل مدى سورته`() {
        for (s in index.surahs) {
            assertEquals(1, s.start - s.start + 1)
            assertEquals(s.ayahs, (s.start + s.ayahs - 1) - s.start + 1)
        }
    }

    @Test
    fun `فهرس قبل أوّل المصحف أو بعد آخره لا يُسقط التطبيق`() {
        assertEquals(1, index.surahAt(-5).number)
        assertEquals(114, index.surahAt(99_999).number)
    }

    @Test
    fun `آخر آية في المصحف هي آخر الناس`() {
        val nas = index.surahs.last()
        assertEquals(QuranRepository.AYAH_COUNT - 1, nas.start + nas.ayahs - 1)
        assertEquals(114, index.surahAt(QuranRepository.AYAH_COUNT - 1).number)
    }

    // ---- روابط الصوت: الرقم الخاطئ = تلاوة آية غير المعروضة ----

    @Test
    fun `رابط الآية بستّ خانات - ثلاث للسورة وثلاث للآية`() {
        val reciter = Reciter("r", "قارئ", "https://x/", perAyah = true)
        assertEquals("https://x/001001.mp3", reciter.ayahUrl(1, 1))
        assertEquals("https://x/002286.mp3", reciter.ayahUrl(2, 286))
        assertEquals("https://x/114006.mp3", reciter.ayahUrl(114, 6))
        assertEquals("https://x/009.mp3", reciter.surahUrl(9))
    }

    @Test
    fun `القارئ المبدئيّ يُفضّل آية بآية ليصحّ التمييز`() {
        val perSurah = Reciter("a", "أ", "https://a/", perAyah = false)
        val perAyah = Reciter("b", "ب", "https://b/", perAyah = true)
        val riwaya = Riwaya("hafs", "حفص", "h.jz", listOf(perSurah, perAyah))
        assertEquals("b", riwaya.defaultReciter?.id)
        assertTrue(riwaya.hasPerAyah)

        val onlySurah = Riwaya("qalun", "قالون", "q.jz", listOf(perSurah))
        assertEquals("a", onlySurah.defaultReciter?.id)
        assertTrue(!onlySurah.hasPerAyah)

        assertNull(Riwaya("x", "س", "x.jz", emptyList()).defaultReciter)
    }

    @Test
    fun `رواية غير معروفة ترجع إلى حفص لا إلى نصّ رواية أخرى`() {
        assertEquals("hafs", index.riwaya("لا-وجود-لها").id)
        assertEquals("hafs", index.riwaya("hafs").id)
    }

    // ---- مدخلات البحث الشاذّة ----

    @Test
    fun `بحث فارغ أو برمز لا يعطي نتيجة ولا ينهار`() {
        assertTrue(searchQuranIndex(index, "").isEmpty())
        assertTrue(searchQuranIndex(index, "   ").isEmpty())
        assertTrue(searchQuranIndex(index, "؟").isEmpty())
        assertTrue(searchQuranIndex(index, "0").isEmpty())
        // رقم أكبر من مدى `Int` لا يُحوَّل فلا يُنتج موضعاً كاذباً.
        assertTrue(searchQuranIndex(index, "99999999999999").isEmpty())
        assertTrue(searchQuranText(emptyList(), "الرحمن").isEmpty())
        assertTrue(searchQuranText(listOf("نصّ"), "").isEmpty())
    }
}
