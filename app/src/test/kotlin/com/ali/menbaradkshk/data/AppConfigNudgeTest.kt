package com.ali.menbaradkshk.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يقفل سياسة «التذكير بالتحديث عند فتح درس».
 *
 * الشرط الذي طُلب صراحةً: **مرّتان في اليوم كحدّ أقصى**، مع مخرج نهائي
 * للمستخدم. وهو توازن دقيق: تذكيرٌ أقلّ لا يصل إلى من لا يقرأ الإشعارات،
 * وأكثر منه يُنفّر من التطبيق نفسه. فأيّ انحدار هنا يُفسد أحد الطرفين، ولا
 * يظهر في اختبار يدويّ إلا بعد يوم كامل — فالاختبار هو الحارس الوحيد عملياً.
 */
/**
 * ⚠️ `sdk = [35]` مطابقاً لبقيّة اختبارات Robolectric في المشروع، ولا
 * يُغيَّر: اختباران بمستويَي SDK مختلفين يُحمِّلان النواة الأصليّة مرّتين
 * فيصطدمان عند تركيب أرشيف الخطوط (`FileSystemAlreadyExistsException`)،
 * فيسقط أحدهما لسببٍ لا علاقة له بما يختبره.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppConfigNudgeTest {

    private lateinit var config: AppConfigRepository
    private lateinit var prefs: android.content.SharedPreferences

    private val optional = AppConfigRepository.Status.Optional(
        latest = 999,
        message = "",
        storeUrl = AppConfigRepository.PLAY_URL,
    )

    /**
     * ⚠️ تصفير المفردة (singleton) قبل كل اختبار **شرطُ عزل لا تجميل**:
     * Robolectric يعيد بناء `Application` لكل اختبار، بينما تحتفظ المفردة
     * بمرجع `SharedPreferences` للسياق القديم. فتمسح الاختبارات تفضيلاتِ
     * سياقٍ ويقرأ المستودعُ تفضيلاتِ آخر — فتتسرّب حالة اختبار إلى ما بعده
     * (وتُسقط اختباراتٍ أخرى في الملفّات المجاورة).
     */
    @Before
    fun setUp() {
        AppConfigRepository::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = app.getSharedPreferences("minbar_app_config", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigRepository.get(app)
    }

    @Test
    fun noNudgeWhenNoUpdateAvailable() {
        assertFalse(config.shouldNudgeOnLesson(AppConfigRepository.Status.None))
    }

    @Test
    fun firstLessonOfTheDayIsNudged() {
        assertTrue(config.shouldNudgeOnLesson(optional))
    }

    @Test
    fun secondNudgeWaitsForTheGapThenIsAllowed() {
        config.markLessonNudged()
        // مباشرةً بعد الأولى: لا تذكير — تذكيران متلاصقان يُقرآن إزعاجاً.
        assertFalse(config.shouldNudgeOnLesson(optional))

        // بعد مضيّ الفجوة (٤ ساعات): التذكير الثاني مسموح.
        prefs.edit()
            .putLong("lesson_nudge_at_ms", System.currentTimeMillis() - 5 * 60 * 60 * 1000L)
            .commit()
        assertTrue(config.shouldNudgeOnLesson(optional))
    }

    @Test
    fun thirdNudgeInTheSameDayIsRefused() {
        config.markLessonNudged()
        prefs.edit()
            .putLong("lesson_nudge_at_ms", System.currentTimeMillis() - 5 * 60 * 60 * 1000L)
            .commit()
        config.markLessonNudged()
        // مرّتان اليوم ⇒ لا ثالثة مهما مضى من الوقت داخل اليوم نفسه.
        prefs.edit()
            .putLong("lesson_nudge_at_ms", System.currentTimeMillis() - 10 * 60 * 60 * 1000L)
            .commit()
        assertFalse(config.shouldNudgeOnLesson(optional))
    }

    @Test
    fun counterResetsOnANewDay() {
        config.markLessonNudged()
        config.markLessonNudged()
        // يومٌ جديد ⇒ العدّاد يبدأ من الصفر والفجوة انقضت.
        prefs.edit()
            .putLong("lesson_nudge_day", 0L)
            .putLong("lesson_nudge_at_ms", 0L)
            .commit()
        assertTrue(config.shouldNudgeOnLesson(optional))
    }

    @Test
    fun muteSilencesThisVersionOnly() {
        config.muteLessonNudge(999)
        assertFalse(config.shouldNudgeOnLesson(optional))

        // نسخة أحدث ⇒ يعود التذكير: خيار الصمت لا يُجمّد المستخدم إلى الأبد
        // على نسخة ميتة.
        val newer = AppConfigRepository.Status.Optional(
            latest = 1000,
            message = "",
            storeUrl = AppConfigRepository.PLAY_URL,
        )
        assertTrue(config.shouldNudgeOnLesson(newer))
    }

    @Test
    fun requiredUpdateIsNudgedToo() {
        val required = AppConfigRepository.Status.Required(
            latest = 999,
            message = "",
            storeUrl = AppConfigRepository.PLAY_URL,
        )
        assertTrue(config.shouldNudgeOnLesson(required))
    }
}
