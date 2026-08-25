package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/// اختبار الحدّ النصي للمزامنة التفاضليّة: `updateCompat` في اللوحة يكتب
/// `updatedAt` بصيغة `toISOString` (ميلي ثانية بثلاث خانات دائماً وZ)، وصحّة
/// الالتقاط تعتمد على أن يقارَن الحدّ معجمياً فيوافق الترتيب الزمني.
class IsoUpdatedBoundTest {

    @Test
    fun `الصيغة تطابق toISOString بتوقيت UTC`() {
        val ms = Instant.parse("2026-08-23T10:20:30.500Z").toEpochMilli()
        assertEquals("2026-08-23T10:20:30.500Z", isoUpdatedBound(ms))
    }

    @Test
    fun `صفر الميلي ثانية يبقى بثلاث خانات - عرض ثابت شرط المقارنة المعجمية`() {
        val ms = Instant.parse("2026-08-23T10:20:30Z").toEpochMilli()
        assertEquals("2026-08-23T10:20:30.000Z", isoUpdatedBound(ms))
    }

    @Test
    fun `الترتيب المعجمي يوافق الترتيب الزمني مع طوابع اللوحة`() {
        val bound = isoUpdatedBound(Instant.parse("2026-08-23T10:20:30.000Z").toEpochMilli())
        // طابع لوحة أحدث بنصف ثانية يجب أن يتجاوز الحدّ (فيلتقطه whereGreaterThan).
        assertTrue(bound < "2026-08-23T10:20:30.500Z")
        // وطابع أحدث بثانية عبر حدود الثواني كذلك.
        assertTrue(bound < "2026-08-23T10:20:31.000Z")
        // وطابع أقدم يسبق الحدّ فلا يُلتقط — أي لا جلب زائد لما لم يتغيّر.
        assertTrue(bound > "2026-08-23T10:20:29.999Z")
    }

    @Test
    fun `حدّان متتاليان زمنياً متتاليان معجمياً`() {
        val earlier = isoUpdatedBound(1_700_000_000_000L)
        val later = isoUpdatedBound(1_700_000_000_001L)
        assertTrue(earlier < later)
    }
}
