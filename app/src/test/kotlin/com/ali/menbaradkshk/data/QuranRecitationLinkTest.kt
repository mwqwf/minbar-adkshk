package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * يقفل سلوك استخراج رقم الحزب من عنوان الدرس.
 *
 * العناوين أدناه **منسوخة حرفياً** من المحتوى المنشور في قسمَي ورش وقالون
 * (٦٠ درساً لكلٍّ). أُدخلت يدوياً على دفعات فاختلطت فيها الصيغ: أرقام
 * («الحزب ٥٣»)، وحروف بصيغة الرفع («الثلاثون») وبصيغة الجرّ («الخمسين»)،
 * ومسافات زائدة، وواو منفصلة («الثاني و العشرون»)، وهمزات («الأربعون»).
 *
 * هذا الاختبار هو ما يجعل ربط «اسمع هذا الحزب» موثوقاً: أي انحدار فيه يعني
 * تلميحاً يفتح حزباً خاطئاً — وهو أسوأ من ألّا يظهر التلميح أصلاً.
 */
class QuranRecitationLinkTest {

    /** عناوين قسم قالون كما هي في قاعدة البيانات، بترتيب الحزب المتوقَّع. */
    private val spelled = mapOf(
        "الحزب الأول" to 1,
        "الحزب الثاني " to 2,
        "الحزب الثالث" to 3,
        "الحزب الرابع " to 4,
        "الحزب الخامس " to 5,
        "الحزب السادس" to 6,
        "الحزب السابع " to 7,
        "الحزب الثامن" to 8,
        "الحزب التاسع " to 9,
        "الحزب العاشر" to 10,
        "الحزب الحادي عشر" to 11,
        "الحزب الثاني عشر " to 12,
        "الحزب الثالث عشر " to 13,
        "الحزب الرابع عشر" to 14,
        "الحزب الخامس عشر " to 15,
        "الحزب السادس عشر" to 16,
        "الحزب السابع عشر" to 17,
        "الحزب الثامن عشر " to 18,
        "الحزب التاسع عشر" to 19,
        "الحزب العشرون" to 20,
        "الحزب الواحد والعشرون" to 21,
        "الحزب الثاني و العشرون" to 22,
        "الحزب الثالث والعشرون " to 23,
        "الحزب الرابع والعشرون" to 24,
        "الحزب الخامس والعشرون " to 25,
        "الحزب السادس والعشرون" to 26,
        "الحزب السابع والعشرون " to 27,
        "الحزب الثامن والعشرون" to 28,
        "الحزب التاسع والعشرون " to 29,
        "الحزب الثلاثون " to 30,
        "الحزب الواحد والثلاثون" to 31,
        "الحزب الثاني والثلاثون " to 32,
        "الحزب الثالث والثلاثون " to 33,
        "الحزب الرابع والثلاثون " to 34,
        "الحزب الخامس والثلاثون " to 35,
        "الحزب السادس والثلاثون " to 36,
        "الحزب السابع والثلاثون " to 37,
        "الحزب الثامن والثلاثون " to 38,
        "الحزب التاسع والثلاثون " to 39,
        "الحزب الأربعون " to 40,
        "الحزب الواحد والأربعون " to 41,
        "الحزب الثاني والأربعون " to 42,
        "الحزب الثالث والأربعون " to 43,
        "الحزب الرابع والأربعون " to 44,
        "الحزب الخامس والأربعون " to 45,
        "الحزب السادس والأربعون " to 46,
        "الحزب السابع والأربعون " to 47,
        "الحزب الثامن والأربعون " to 48,
        "الحزب التاسع والأربعون " to 49,
        "الحزب الخمسين" to 50,
        "الحزب الواحد والخمسين " to 51,
        "الحزب الثاني والخمسين" to 52,
        "الحزب الثالث والخمسين " to 53,
        "الحزب الرابع والخمسين " to 54,
        "الحزب الخامس والخمسين " to 55,
        "الحزب السادس والخمسين " to 56,
        "الحزب السابع والخمسين " to 57,
        "الحزب الثامن والخمسين " to 58,
        "الحزب التاسع والخمسين " to 59,
        "الحزب الستون" to 60,
    )

    @Test
    fun everySpelledTitleResolvesToItsHizb() {
        spelled.forEach { (title, expected) ->
            assertEquals(title, expected, QuranRecitationLink.hizbNumberIn(title))
        }
    }

    @Test
    fun spelledTitlesCoverAllSixtyHizbsWithoutGaps() {
        assertEquals(
            (1..60).toSet(),
            spelled.keys.mapNotNull(QuranRecitationLink::hizbNumberIn).toSet(),
        )
    }

    @Test
    fun numericTitlesAreRead() {
        (46..60).forEach { n ->
            assertEquals(n, QuranRecitationLink.hizbNumberIn("الحزب $n"))
        }
        // أرقام هنديّة أيضاً — واردة في إدخال يدويّ عربيّ.
        assertEquals(7, QuranRecitationLink.hizbNumberIn("الحزب ٧"))
        assertEquals(53, QuranRecitationLink.hizbNumberIn("الحزب ٥٣"))
    }

    /**
     * الحارس الأهمّ: عناوين المنبر مليئة بالأعداد في سياقات ليست أحزاباً.
     * لولا اشتراط كلمة «حزب» لفتح التلميح درساً خاطئاً تماماً.
     */
    @Test
    fun unrelatedTitlesResolveToNothing() {
        assertNull(QuranRecitationLink.hizbNumberIn("شرح الأربعون النووية للشيخ طلحة"))
        assertNull(QuranRecitationLink.hizbNumberIn("شرح الأصول الثلاثة للشيخ محمد بن عبد الوهاب"))
        assertNull(QuranRecitationLink.hizbNumberIn("شرح الأحاديث العشرة للشيخ طلحة"))
        assertNull(QuranRecitationLink.hizbNumberIn("الدرس 12"))
        assertNull(QuranRecitationLink.hizbNumberIn("مقدّمة"))
        assertNull(QuranRecitationLink.hizbNumberIn(""))
    }

    @Test
    fun hizbIsFoundFromFlatAyahIndex() {
        val index = QuranIndex(
            surahs = listOf(Surah(1, "الفاتحة", 7, true, 0, 1)),
            juzs = emptyList(),
            hizbs = listOf(QuranMark(1, 0), QuranMark(2, 100), QuranMark(3, 250)),
            pages = emptyList(),
            riwayat = emptyList(),
        )
        assertEquals(1, QuranRecitationLink.hizbAt(index, 0))
        assertEquals(1, QuranRecitationLink.hizbAt(index, 99))
        assertEquals(2, QuranRecitationLink.hizbAt(index, 100))
        assertEquals(2, QuranRecitationLink.hizbAt(index, 249))
        assertEquals(3, QuranRecitationLink.hizbAt(index, 6235))
    }
}
