package com.ali.menbaradkshk.util

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 🎛️ دمج صوتيات **بأي صيغ** في ملف واحد — الحل الجذري لقيد «MP3 فقط»:
 *
 * - كل ملف يُفَكّ إلى PCM عبر MediaCodec (يدعم mp3/m4a/aac/ogg/opus/wav/
 *   amr/flac وكل ما يفكّه الجهاز أصلاً).
 * - يُوحَّد الصوت إلى PCM ستيريو 16-bit مع استمرار الطور بين الدُفعات.
 * - الإخراج Opus في Ogg عندما ينجح تهيئة مرمّز أندرويد، وإلا AAC-LC
 *   ‏128kbps في M4A وسيطًا (تظهر الصيغة الفعلية قبل الرفع).
 * - يُعالَج تدفقياً دون تحميل الملفات بأكملها في الذاكرة.
 */
object AudioTranscodeMerger {
    private const val TARGET_CHANNELS = 2
    private const val TIMEOUT_US = 10_000L
    private const val STALL_TIMEOUT_NS = 30L * 1_000_000_000L
    private const val BYTES_PER_FRAME = 2 * TARGET_CHANNELS

    private enum class Profile(val mime: String, val ext: String, val rate: Int, val bitrate: Int) {
        OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, "ogg", 48_000, 96_000),
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC, "m4a", 44_100, 128_000),
    }

    /// سقف زمن فكّ **ملفّ واحد**: عشر دقائق تكفي أطول درسٍ على أبطأ جهاز
    /// بفارقٍ واسع، ولا تترك ملفّاً تالفاً يعلّق العمليّة إلى الأبد.
    private const val DECODE_TIMEOUT_MS = 10L * 60L * 1_000L

    const val OUTPUT_MIME = "audio/mp4"
    const val OUTPUT_EXTENSION = "m4a"

    /** صيغة غير قابلة للفك على هذا الجهاز (تصل رسالتها للمستخدم كما هي). */
    class UnsupportedAudioException(message: String) : Exception(message)
    private class OpusUnavailable(cause: Exception) : Exception(cause)

    /** يحافظ على ترتيب المقاطع؛ اختيار صيغة أخرى يقتصر على فشل تهيئة Opus. */
    fun mergeForUpload(inputs: List<File>, outputStem: File): File {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return merge(inputs, File("${outputStem.path}.ogg"), Profile.OPUS)
            } catch (_: OpusUnavailable) {
                // لم تُقرأ أجزاءٌ بعد؛ لا نكرر دمج ملف تالف أو محاولة كتابة فاشلة.
            }
        }
        return mergeToM4a(inputs, "${outputStem.path}.$OUTPUT_EXTENSION")
    }

    fun mergeToM4a(inputs: List<File>, outputPath: String): File =
        merge(inputs, File(outputPath), Profile.AAC)

    private fun merge(inputs: List<File>, output: File, profile: Profile): File {
        require(inputs.isNotEmpty()) { "لا ملفات للدمج." }
        output.parentFile?.mkdirs()
        require(!output.exists()) { "ملف ناتج الدمج موجود سلفًا." }
        val encoder = try {
            MediaCodec.createEncoderByType(profile.mime).also { codec ->
                try {
                    codec.configure(
                        MediaFormat.createAudioFormat(profile.mime, profile.rate, TARGET_CHANNELS).apply {
                            if (profile == Profile.AAC) setInteger(
                                MediaFormat.KEY_AAC_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                            )
                            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                        },
                        null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                    codec.start()
                } catch (failure: Throwable) {
                    runCatching { codec.release() }
                    throw failure
                }
            }
        } catch (failure: Exception) {
            if (profile == Profile.OPUS) throw OpusUnavailable(failure)
            throw failure
        }

        val muxer = try {
            MediaMuxer(output.absolutePath, if (profile == Profile.OPUS)
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (failure: Throwable) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            output.delete()
            throw failure
        }
        var trackIndex = -1
        var muxerStarted = false
        var totalFrames = 0L
        val encoderInfo = MediaCodec.BufferInfo()

        fun drainEncoder(untilEos: Boolean) {
            var lastProgress = System.nanoTime()
            while (true) {
                check(System.nanoTime() - lastProgress < STALL_TIMEOUT_NS) { "توقّف مرمّز الصوت دون إكمال الملف." }
                val index = encoder.dequeueOutputBuffer(
                    encoderInfo,
                    if (untilEos) TIMEOUT_US else 0L,
                )
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    index >= 0 -> {
                        // ⚠️ `?: continue` وحده كان يقفز **قبل**
                        // `releaseOutputBuffer`، فتُحتجز الصومعة إلى الأبد؛ ومع
                        // نفادها يعود `dequeueOutputBuffer` بلا شيء أبداً فتدور
                        // الحلقة بلا نهاية ويتجمّد الدمج. الصومعة تُعاد أوّلاً.
                        val encoded = encoder.getOutputBuffer(index)
                        if (encoded == null) {
                            encoder.releaseOutputBuffer(index, false)
                            continue
                        }
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encoderInfo.size = 0
                        }
                        if (encoderInfo.size > 0 && muxerStarted) {
                            lastProgress = System.nanoTime()
                            encoded.position(encoderInfo.offset)
                            encoded.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, encoderInfo)
                        }
                        val eos = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                        if (eos) return
                    }
                }
            }
        }

        // يدفع PCM موحَّداً (16-bit ستيريو بمعدّل الإخراج) إلى المرمّز مع تصريف مستمر
        // كي لا تمتلئ صوامعه فيتجمّد الفكّ.
        fun feedPcm(data: ByteArray, length: Int) {
            var offset = 0
            var lastProgress = System.nanoTime()
            while (offset < length) {
                check(System.nanoTime() - lastProgress < STALL_TIMEOUT_NS) { "توقّف مرمّز الصوت أثناء الدمج." }
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    // ⚠️ بالعلّة نفسها في [drainEncoder]: القفز بلا إعادة صومعة
                    // الدخل يستنزفها فيعود `dequeueInputBuffer` بلا شيء أبداً
                    // وتدور الحلقة إلى الأبد. نُعيدها بطولٍ صفر ثم نتابع.
                    val buffer = encoder.getInputBuffer(inIndex)
                    if (buffer == null) {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        continue
                    }
                    buffer.clear()
                    val capacity = minOf(buffer.remaining(), length - offset)
                    val chunk = capacity - capacity % BYTES_PER_FRAME
                    check(chunk > 0) { "مرمّز الصوت لم يوفّر مساحة لإطار كامل." }
                    buffer.put(data, offset, chunk)
                    encoder.queueInputBuffer(
                        inIndex,
                        0,
                        chunk,
                        totalFrames * 1_000_000L / profile.rate,
                        0,
                    )
                    totalFrames += chunk / BYTES_PER_FRAME
                    offset += chunk
                    lastProgress = System.nanoTime()
                }
                drainEncoder(false)
            }
        }

        var complete = false
        try {
            inputs.forEach { file ->
                // كل ملف يجب أن يساهم بصوت فعلي؛ كان الملف المبتور الذي يفكّ
                // صفر عيّنات يسقط بصمت ويُرفع الدرس المدموج ناقصاً جزءاً كاملاً.
                val framesBefore = totalFrames
                decodeInto(file, profile.rate, sink = ::feedPcm)
                if (totalFrames == framesBefore) {
                    throw UnsupportedAudioException(
                        "تعذّر استخراج صوت من «${file.name}» — الملف تالف أو فارغ.",
                    )
                }
            }
            // راية نهاية التدفق ثم تصريف كامل.
            val eosRequestedAt = System.nanoTime()
            while (true) {
                check(System.nanoTime() - eosRequestedAt < STALL_TIMEOUT_NS) { "تعذّر إنهاء ترميز الصوت." }
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    encoder.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        totalFrames * 1_000_000L / profile.rate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    break
                }
                drainEncoder(false)
            }
            drainEncoder(untilEos = true)
            check(muxerStarted && totalFrames > 0L) { "لم ينتج الدمج مسارًا صوتيًا." }
            // تمرير فشل الإنهاء مهمّ؛ الملف الجزئي ليس جاهزًا للرفع.
            muxerStarted = false
            muxer.stop()
            complete = true
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            if (!complete) output.delete()
        }
        if (output.length() == 0L) {
            output.delete()
            throw UnsupportedAudioException("تعذّر إنشاء ملف صوتي صالح.")
        }
        return output
    }

    internal fun decodePrivateOpusPreview(file: File, checkActive: () -> Unit, sink: (ByteArray, Int) -> Unit) =
        decodeInto(file, 48_000, exactOpus = true, checkActive = checkActive, sink = sink)

    /** يفكّ ملفاً واحداً ويسلّم PCM الموحَّد إلى [sink] دفعةً دفعة. */
    private fun decodeInto(
        file: File, targetRate: Int, exactOpus: Boolean = false,
        checkActive: () -> Unit = {}, sink: (ByteArray, Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (error: Exception) {
            extractor.release()
            throw UnsupportedAudioException("تعذّرت قراءة «${file.name}» — الملف تالف أو غير مدعوم.")
        }
        var trackIndex = -1
        var sourceFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                trackIndex = i
                sourceFormat = format
                break
            }
        }
        if (trackIndex < 0 || sourceFormat == null) {
            extractor.release()
            throw UnsupportedAudioException("«${file.name}» لا يحتوي مساراً صوتياً.")
        }
        val mime = sourceFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        val opus = try {
            if (!exactOpus) null
            else if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS && file.inputStream().use {
                    ByteArray(4).let { head -> it.read(head) == 4 && head.contentEquals(byteArrayOf(79, 103, 103, 83)) }
                }) {
                file.inputStream().buffered().use { OggOpusTrimPlan.read(it, checkActive) }.also { plan ->
                    check(sourceFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, -1) == 48_000)
                    check(sourceFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, -1) == plan.channels)
                    val header = checkNotNull(sourceFormat.getByteBuffer("csd-0")).duplicate()
                    val bytes = ByteArray(header.remaining()).also(header::get)
                    check(bytes.size == 19 && ((bytes[10].toInt() and 255) or
                        ((bytes[11].toInt() and 255) shl 8)) == plan.preSkip)
                    bytes[10] = 0
                    bytes[11] = 0
                    sourceFormat.setByteBuffer("csd-0", ByteBuffer.wrap(bytes))
                    sourceFormat.setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))
                    sourceFormat.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
                    sourceFormat.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
                }
            } else error("Unsupported exact Opus container")
        } catch (_: Exception) {
            extractor.release()
            throw UnsupportedAudioException("تعذّر التحقق من حدود صوت Opus في «${file.name}».")
        }
        extractor.selectTrack(trackIndex)
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            extractor.release()
            throw UnsupportedAudioException("صيغة «${file.name}» غير مدعومة على هذا الجهاز.")
        }
        try {
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var sampleRate = sourceFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, targetRate)
            var channels = sourceFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmFloat = false
            var rawDecodedFrames = 0L
            var resampler = Resampler(sampleRate, channels, targetRate)
            var inputDone = false
            var outputDone = false
            // ⏱️ مهلة كليّة: الحلقة كانت تدور حتى راية EOS وحدها، وملفٌّ تالف
            // لا يُصدرها يُعلّق الخيط أبداً — وهو خيط عمل الرفع، فيبقى «جارٍ
            // الدمج» إلى ما لا نهاية بلا رسالة ولا مخرج.
            val deadlineMs = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            while (!outputDone) {
                checkActive()
                if (System.currentTimeMillis() > deadlineMs) {
                    throw UnsupportedAudioException(
                        "استغرق فكّ «${file.name}» وقتاً طويلاً — الملف تالف أو غير مدعوم.",
                    )
                }
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmFloat = Build.VERSION.SDK_INT >= 24 &&
                            format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(sampleRate, channels, targetRate)
                    }

                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val samples = if (pcmFloat) {
                                    floatBufferToShorts(buffer)
                                } else {
                                    pcm16BufferToShorts(buffer)
                                }
                                val source = if (opus == null) samples else {
                                    check(sampleRate == 48_000 && channels == opus.channels &&
                                        samples.size % channels == 0)
                                    val start = rawDecodedFrames
                                    val end = start + samples.size / channels
                                    check(end <= opus.packetFrames)
                                    rawDecodedFrames = end
                                    val left = maxOf(start, opus.preSkip.toLong())
                                    val right = minOf(end, opus.preSkip + opus.audibleFrames)
                                    if (left >= right) ShortArray(0)
                                    else samples.copyOfRange(((left - start) * channels).toInt(),
                                        ((right - start) * channels).toInt())
                                }
                                val out = resampler.process(source)
                                if (out.isNotEmpty()) sink(out, out.size)
                            }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (eos) outputDone = true
                    }
                }
            }
            if (opus != null && rawDecodedFrames != opus.packetFrames) {
                throw UnsupportedAudioException("لم يسلّم مفكّك Opus كل إطارات «${file.name}» دون قصّ تلقائي.")
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun pcm16BufferToShorts(buffer: ByteBuffer): ShortArray {
        val shorts = ShortArray(buffer.remaining() / 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun floatBufferToShorts(buffer: ByteBuffer): ShortArray {
        val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val shorts = ShortArray(floats.remaining())
        for (i in shorts.indices) {
            val value = (floats.get() * Short.MAX_VALUE).toInt()
            shorts[i] = value.coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            ).toShort()
        }
        return shorts
    }

    /**
     * إعادة عيّنة خطية ذات حالة: تحفظ آخر إطار وموضع الكسر بين الدُفعات،
     * فلا انقطاع في الطور عند حدود الصوامع. تحوّل أيضاً عدد القنوات إلى
     * ستيريو (الأحادي يُضاعَف، والزائد عن اثنتين يُسقَط).
     */
    private class Resampler(sourceRate: Int, private val sourceChannels: Int, targetRate: Int) {
        private val step = sourceRate.toDouble() / targetRate
        private var position = 0.0
        private var previousLeft = 0
        private var previousRight = 0
        private var hasPrevious = false
        private val sameRate = sourceRate == targetRate

        fun process(samples: ShortArray): ByteArray {
            if (samples.isEmpty() || sourceChannels <= 0) return ByteArray(0)
            val frameCount = samples.size / sourceChannels
            if (frameCount == 0) return ByteArray(0)
            // Channel mapping alone must not enter the interpolation path: at equal rates
            // that path repeats a leading sample and drops the last sample of a clip.
            if (sameRate) {
                val bytes = ByteArray(frameCount * BYTES_PER_FRAME)
                val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                if (sourceChannels == 2) {
                    output.put(samples, 0, frameCount * 2)
                } else {
                    for (frame in 0 until frameCount) {
                        val first = samples[frame * sourceChannels]
                        output.put(first)
                        output.put(if (sourceChannels >= 2) samples[frame * sourceChannels + 1] else first)
                    }
                }
                return bytes
            }
            fun left(frame: Int): Int = samples[frame * sourceChannels].toInt()
            fun right(frame: Int): Int =
                if (sourceChannels >= 2) samples[frame * sourceChannels + 1].toInt() else left(frame)

            if (!hasPrevious) {
                previousLeft = left(0)
                previousRight = right(0)
                hasPrevious = true
            }
            // الإطارات المتاحة للاستيفاء: السابق المحفوظ + إطارات هذه الدفعة.
            val out = ByteBuffer.allocate(
                ((frameCount / step).toInt() + 8) * BYTES_PER_FRAME,
            ).order(ByteOrder.LITTLE_ENDIAN)
            while (position < frameCount) {
                val base = position.toInt()
                val fraction = position - base
                val leftA = if (base == 0) previousLeft else left(base - 1)
                val rightA = if (base == 0) previousRight else right(base - 1)
                // نستوفي بين الإطار السابق للموضع والإطار عنده — فيبقى صالحاً
                // حتى آخر إطار في الدفعة بلا انتظار الدفعة التالية.
                val leftB = left(base)
                val rightB = right(base)
                val l = (leftA + (leftB - leftA) * fraction).toInt()
                val r = (rightA + (rightB - rightA) * fraction).toInt()
                out.putShort(l.coerceIn(-32768, 32767).toShort())
                out.putShort(r.coerceIn(-32768, 32767).toShort())
                position += step
            }
            position -= frameCount
            previousLeft = left(frameCount - 1)
            previousRight = right(frameCount - 1)
            return out.array().copyOf(out.position())
        }
    }
}
