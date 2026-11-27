package com.ali.menbaradkshk.media

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ali.menbaradkshk.data.Lesson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class LessonLoudnessTest {
    @Test fun positiveGainUsesEnhancerAtFullVolume() {
        assertEquals(LessonLoudness.Plan(350, 1f), LessonLoudness.plan(3.5))
        assertEquals(LessonLoudness.Plan(1200, 1f), LessonLoudness.plan(40.0)) // محصور +12
    }

    @Test fun negativeGainLowersPlayerVolumeOnly() {
        val p = LessonLoudness.plan(-6.0)
        assertEquals(0, p.enhancerMb)
        assertEquals(0.501f, p.volume, 0.001f)
        assertEquals(LessonLoudness.plan(-6.0), LessonLoudness.plan(-30.0)) // محصور −6
    }

    @Test fun missingOrInvalidGainIsNeutral() {
        assertEquals(LessonLoudness.NEUTRAL, LessonLoudness.plan(null))
        assertEquals(LessonLoudness.NEUTRAL, LessonLoudness.plan(Double.NaN))
        assertEquals(LessonLoudness.NEUTRAL, LessonLoudness.plan(0.0))
    }

    @Test fun catalogFieldParsesAndRoundTrips() {
        val withGain = Lesson.fromMap("a", mapOf("title" to "t", "gainDb" to 4.2))
        assertEquals(4.2, withGain.gainDb!!, 1e-9)
        assertNull(Lesson.fromMap("b", mapOf("title" to "t")).gainDb)
        assertNull(Lesson.fromMap("c", mapOf("title" to "t", "gainDb" to "x")).gainDb)
        assertEquals(12.0, Lesson.fromMap("d", mapOf("gainDb" to 99)).gainDb!!, 1e-9)
        val back = Lesson.fromJson(JSONObject(withGain.toJson().toString()))
        assertEquals(4.2, back.gainDb!!, 1e-9)
        assertFalse(Lesson.fromMap("e", mapOf("title" to "t")).toJson().has("gainDb"))
    }

    @Test fun mediaItemCarriesGainAndItemsWithoutItResetToNeutral() {
        val item = MediaItem.Builder().setMediaId("a")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(LessonLoudness.extrasFor(-2.5)).build()).build()
        assertEquals(-2.5, LessonLoudness.gainOf(item)!!, 1e-9)
        assertNull(LessonLoudness.gainOf(MediaItem.Builder().setMediaId("verse").build()))
        assertEquals(LessonLoudness.NEUTRAL, LessonLoudness.plan(LessonLoudness.gainOf(null)))
    }
}
