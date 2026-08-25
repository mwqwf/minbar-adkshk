package com.ali.menbaradkshk.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.sqrt

/**
 * 🎙️ مسجّل صوت بسيط للتطبيق العام (m4a/AAC) — منقول عن مسجّل لوحة الإدارة.
 *
 * **لماذا نُسخ إلى هنا؟** جمهور «راسِل المطوّر» بدويّ لا يكتب العربية بطلاقة،
 * فالصوت هو وسيلته الأولى لا وسيلته الاحتياطية. ولم يكن في التطبيق العام
 * مسجّل أصلاً — كان يرفع ملفات جاهزة فقط.
 *
 * ثلاث علل عولجت في الأصل ونُقلت كما هي لأنّها تصيب أجهزة الجمهور نفسها:
 *  1. فشل `stop()` يترك حاوية MP4 بلا `moov` — ملفّ حجمه > 0 ولا يُشغَّل عند
 *     أحد. مثل هذا التسجيل يُحذف ولا يُرسَل أبداً.
 *  2. حتى بعد نجاح `stop()` نتحقّق أنّ الناتج يُفكّ ترميزه فعلاً.
 *  3. إعدادات الترميز تهبط درجةً درجةً حتى تقبلها الأجهزة الضعيفة بدل أن
 *     يفشل التسجيل من أصله.
 *
 * وزيادةً على الأصل: [elapsedMs] يُحدَّث كل 100ms كي تفرض الواجهة سقف
 * الدقيقتين ذاتياً بلا مؤقّت ثانٍ يتسرّب.
 */
class VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L

    val isRecording: Boolean get() = recorder != null

    private val handler = Handler(Looper.getMainLooper())

    // قد يوقفها خيط غير خيط الواجهة (إيقاف من كوروتين الإرسال).
    @Volatile
    private var sampling = false

    private val _amplitudes = MutableStateFlow<List<Int>>(emptyList())

    /** آخر [LIVE_WINDOW] عيّنة (0..100) — للموجة الحيّة أثناء التسجيل. */
    val amplitudes: StateFlow<List<Int>> = _amplitudes

    private val _elapsedMs = MutableStateFlow(0L)

    /** المدّة المنقضية — تعرضها الواجهة وتوقف بها التسجيل عند السقف. */
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private val samples = mutableListOf<Int>()

    private val tick = object : Runnable {
        override fun run() {
            if (!sampling) return
            // maxAmplitude يرمي على بعض الأجهزة إن لم يكن المسجّل نشطاً.
            val raw = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            val level = normalizeAmplitude(raw)
            synchronized(samples) {
                if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
                samples.add(level)
                _amplitudes.value = samples.takeLast(LIVE_WINDOW)
            }
            _elapsedMs.value = System.currentTimeMillis() - startedAtMs
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        handler.postDelayed(tick, SAMPLE_INTERVAL_MS)
    }

    private fun stopSampling() {
        sampling = false
        handler.removeCallbacks(tick)
    }

    /**
     * إعدادات مرتَّبة من الأفضل إلى الأكثر توافقاً: بعض المرمِّزات على الأجهزة
     * الاقتصاديّة ترفض 44.1kHz أو معدّل البتّ العالي، فنهبط درجةً بدل الفشل.
     */
    private data class Profile(val sampleRate: Int, val bitRate: Int)

    private val profiles = listOf(
        // كلامٌ لا موسيقى: نبدأ من إعداد خفيف يوفّر على إنترنت ضعيف، ثم
        // نهبط أخفّ منه إن رفضه الجهاز.
        Profile(44_100, 64_000),
        Profile(48_000, 64_000),
        Profile(16_000, 32_000),
    )

    fun start(context: Context): File {
        stopQuietly()
        clearSamples()
        val dir = File(context.cacheDir, "support_voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        var lastError: Throwable? = null

        for (profile in profiles) {
            runCatching { file.delete() }
            val r = newRecorder(context)
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // أحاديّ القناة صراحةً: الافتراضي يختلف بين الأجهزة، ومقطع
                // الكلام لا يستفيد من الاستريو إلا بمضاعفة الحجم.
                r.setAudioChannels(1)
                r.setAudioSamplingRate(profile.sampleRate)
                r.setAudioEncodingBitRate(profile.bitRate)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                outputFile = file
                startedAtMs = System.currentTimeMillis()
                _elapsedMs.value = 0L
                startSampling()
                return file
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "profile ${profile.sampleRate}/${profile.bitRate} failed: $e")
                runCatching { r.reset() }
                runCatching { r.release() }
            }
        }
        runCatching { file.delete() }
        throw lastError ?: IllegalStateException("تعذّر تشغيل المسجّل على هذا الجهاز.")
    }

    private fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /**
     * ينهي التسجيل ويعيد الملفّ **إن كان صالحاً للتشغيل فقط**، وإلا `null`
     * — فلا تُرسَل رسالة صوتيّة ميّتة إلى المطوّر.
     */
    fun stop(): File? {
        stopSampling()
        _amplitudes.value = emptyList()
        val r = recorder
        val file = outputFile
        recorder = null
        if (r == null) return file?.takeIf { isPlayable(it) }

        val stoppedCleanly = try {
            r.stop()
            true
        } catch (e: Exception) {
            // فشل الإيقاف ⇒ حاوية بلا moov ⇒ ملفّ غير قابل للتشغيل مهما بدا حجمه.
            Log.w(TAG, "stop() failed — recording unusable: $e")
            false
        } finally {
            runCatching { r.release() }
        }

        if (!stoppedCleanly || file == null || !isPlayable(file)) {
            runCatching { file?.delete() }
            outputFile = null
            return null
        }
        return file
    }

    /** إلغاء وحذف الملفّ — «تراجع» عن تسجيل لم يُعجب صاحبه. */
    fun cancel() {
        stopQuietly()
        clearSamples()
        runCatching { outputFile?.delete() }
        outputFile = null
    }

    private fun stopQuietly() {
        stopSampling()
        _amplitudes.value = emptyList()
        _elapsedMs.value = 0L
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    private fun clearSamples() {
        synchronized(samples) { samples.clear() }
        _amplitudes.value = emptyList()
        _elapsedMs.value = 0L
    }

    fun release() = stopQuietly()

    /** تطبيع السعة إلى 0..100 — بجذر النسبة لتوزيع بصري أوضح. */
    private fun normalizeAmplitude(raw: Int): Int {
        if (raw <= 0) return 0
        val ratio = raw.coerceAtMost(MAX_AMPLITUDE).toDouble() / MAX_AMPLITUDE
        return (sqrt(ratio) * 100).toInt().coerceIn(0, 100)
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val MAX_AMPLITUDE = 32_767
        private const val LIVE_WINDOW = 40
        private const val MAX_SAMPLES = 2_000

        /** ⛔ سقف صارم: دقيقتان. أطول من ذلك يثقل رفعه على إنترنت ضعيف. */
        const val MAX_DURATION_MS = 2 * 60 * 1000L

        /** هل الملفّ قابل للتشغيل فعلاً (مسار صوتي ومدّة موجبة)؟ */
        fun isPlayable(file: File): Boolean {
            if (!file.exists() || file.length() < 1024) return false
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(file.absolutePath)
                val duration = mmr
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val hasAudio = mmr
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                duration > 0L && hasAudio
            } catch (_: Exception) {
                false
            } finally {
                runCatching { mmr.release() }
            }
        }
    }
}
