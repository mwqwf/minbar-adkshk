package com.ali.menbaradkshk.media

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * علوّ التشغيل لكلّ درس (`Lesson.gainDb`): يُوحَّد العلوّ المسموع بين الدروس دون
 * مساس بالملفات أو بصماتها (التنزيلات والحزم والكاش كما هي).
 *
 * - كسبٌ موجب ⇒ `LoudnessEnhancer` على جلسة صوت المشغّل (له محدِّدٌ مدمج، فلا قصّ).
 * - كسبٌ سالب ⇒ خفض حجم المشغّل نفسه (المحسِّن لا يخفض).
 * - بلا قيمة (درس لم يُقَس، آية، عنصر قديم) ⇒ صفر وحجم كامل — يُعاد ضبطه لكلّ عنصر.
 */
internal object LessonLoudness {
    const val EXTRA_GAIN_DB = "minbar.gainDb"

    data class Plan(val enhancerMb: Int, val volume: Float)

    val NEUTRAL = Plan(0, 1f)

    fun plan(gainDb: Double?): Plan {
        val g = gainDb?.takeIf { it.isFinite() }?.coerceIn(-6.0, 12.0) ?: return NEUTRAL
        return when {
            g > 0.0 -> Plan((g * 100.0).roundToInt(), 1f)
            g < 0.0 -> Plan(0, 10.0.pow(g / 20.0).toFloat())
            else -> NEUTRAL
        }
    }

    fun extrasFor(gainDb: Double?): Bundle? = gainDb?.let { Bundle().apply { putDouble(EXTRA_GAIN_DB, it) } }

    fun gainOf(item: MediaItem?): Double? =
        item?.mediaMetadata?.extras?.takeIf { it.containsKey(EXTRA_GAIN_DB) }?.getDouble(EXTRA_GAIN_DB)
}

/** يربط المحسِّن بجلسة صوت المشغّل ويطبّق خطّة العنصر الحاليّ. كلّ أعطال الجهاز تُبتلع: التشغيل أولاً. */
internal class LessonLoudnessApplier(private val player: Player) {
    private var enhancer: LoudnessEnhancer? = null
    private var sessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var current: LessonLoudness.Plan = LessonLoudness.NEUTRAL

    fun onAudioSession(id: Int) {
        if (id == sessionId) return
        release()
        sessionId = id
        if (id != C.AUDIO_SESSION_ID_UNSET) {
            enhancer = runCatching { LoudnessEnhancer(id) }.getOrNull()
        }
        apply(current)
    }

    fun applyFor(item: MediaItem?) = apply(LessonLoudness.plan(LessonLoudness.gainOf(item)))

    private fun apply(plan: LessonLoudness.Plan) {
        current = plan
        enhancer?.let { e ->
            runCatching {
                e.setTargetGain(plan.enhancerMb)
                e.enabled = plan.enhancerMb > 0
            }
        }
        runCatching { player.volume = plan.volume }
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
    }
}
