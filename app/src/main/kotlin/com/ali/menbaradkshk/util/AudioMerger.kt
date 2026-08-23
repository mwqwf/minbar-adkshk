package com.ali.menbaradkshk.util

import java.io.File
import java.io.FileOutputStream

// 🔗 دمج عدة ملفات MP3 في ملف واحد بلا إعادة ترميز — منقول حرفياً من
// audio_merge.dart في الأصل: قصّ ID3v2/ID3v1 وإطار Xing/Info/VBRI الوصفي
// ثم لصق إطارات MPEG تباعاً بالترتيب المطلوب.

class Mp3FormatException(message: String) : Exception(message)

object AudioMerger {
    const val maxFiles = 10
    private const val maxInputBytes = 32 * 1024 * 1024
    private const val maxTotalBytes = 80 * 1024 * 1024

    fun isMp3(fileName: String): Boolean = fileName.lowercase().trim().endsWith(".mp3")

    /// يدمج المدخلات بترتيبها في ملف جديد ويعيده.
    fun mergeMp3(
        inputs: List<File>,
        outputPath: String,
        onProgress: ((Double) -> Unit)? = null,
    ): File {
        require(inputs.isNotEmpty()) { "لا توجد ملفات للدمج" }
        require(inputs.size <= maxFiles) { "الحد الأقصى $maxFiles ملفات" }
        var totalBytes = 0L
        for (input in inputs) {
            val size = input.length()
            if (size > maxInputBytes) throw Mp3FormatException("أحد ملفات الدمج كبير جداً")
            totalBytes += size
        }
        if (totalBytes > maxTotalBytes) throw Mp3FormatException("الحجم الكلي للدمج كبير جداً")

        val out = File(outputPath)
        var streamSpec: Int? = null
        try {
            FileOutputStream(out).use { sink ->
                inputs.forEachIndexed { index, input ->
                    // ⚠️ كان `input.readBytes()` يحمل الملفّ **كاملاً** في
                    // الذاكرة (وسقفه ٣٢ م.ب للملفّ الواحد): على أجهزة
                    // minSdk 23 ذات كومةٍ تُقاس بعشرات الميغابايتات يعني هذا
                    // `OutOfMemoryError` — لا رسالةَ خطأٍ بل انهيار التطبيق
                    // وضياع الرفع. الآن نقرأ رأس الملفّ فقط لتحديد نطاق
                    // الإطارات، ثم ننسخ النطاق تدفّقيّاً بصومعة ثابتة.
                    val (frames, spec) = frameRange(input)
                    if (streamSpec == null) streamSpec = spec
                    if (spec != streamSpec) {
                        throw Mp3FormatException("ملفات MP3 المختارة ليست بترميز صوتي متوافق")
                    }
                    copyRange(input, frames.first, frames.last, sink)
                    onProgress?.invoke((index + 1).toDouble() / inputs.size * 100)
                }
                sink.fd.sync()
            }
        } catch (failure: Throwable) {
            if (out.exists()) out.delete()
            throw failure
        }
        return out
    }

    private fun byteAt(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    /// حجم صومعة النسخ التدفّقي — ثابتة مهما كبر الملفّ.
    private const val COPY_BUFFER = 64 * 1024

    /// نافذة الرأس التي يُبحث فيها عن أوّل إطار MPEG بعد وسم ID3v2. أوسع بكثير
    /// من أيّ حشوٍ واقعيّ بين الوسم وأوّل إطار، وأصغر بكثير من الملفّ نفسه.
    private const val HEADER_WINDOW = 256 * 1024

    /// يقصّ الوسوم ويعيد (نطاق الإطارات في **الملفّ**، بصمة الترميز) — بقراءة
    /// الرأس والذيل فقط، بلا تحميل الملفّ في الذاكرة.
    private fun frameRange(file: File): Pair<LongRange, Int> {
        java.io.RandomAccessFile(file, "r").use { raf ->
            var start = 0L
            var end = raf.length()
            if (end <= 4L) throw Mp3FormatException("لم يُعثر على صوت MPEG في الملف")

            // ID3v2: "ID3" + إصدار(2) + أعلام(1) + حجم syncsafe(4)
            val head = ByteArray(10)
            if (end >= 10L) {
                raf.seek(0L)
                raf.readFully(head)
                if (byteAt(head, 0) == 0x49 && byteAt(head, 1) == 0x44 && byteAt(head, 2) == 0x33) {
                    val size = ((byteAt(head, 6) and 0x7F) shl 21) or
                        ((byteAt(head, 7) and 0x7F) shl 14) or
                        ((byteAt(head, 8) and 0x7F) shl 7) or
                        (byteAt(head, 9) and 0x7F)
                    var s = 10L + size
                    if ((byteAt(head, 5) and 0x10) != 0) s += 10L
                    if (s < end) start = s
                }
            }

            // ID3v1: "TAG" في آخر 128 بايت
            if (end - start > 128L) {
                val tag = ByteArray(3)
                raf.seek(end - 128L)
                raf.readFully(tag)
                if (byteAt(tag, 0) == 0x54 && byteAt(tag, 1) == 0x41 && byteAt(tag, 2) == 0x47) {
                    end -= 128L
                }
            }

            // نافذة الرأس وحدها تكفي للعثور على أوّل إطار وقراءة بصمته.
            val windowSize = minOf((end - start), HEADER_WINDOW.toLong()).toInt()
            if (windowSize < 4) throw Mp3FormatException("لم يُعثر على صوت MPEG في الملف")
            val window = ByteArray(windowSize)
            raf.seek(start)
            raf.readFully(window)
            val (offset, spec) = firstFrameInWindow(window)
            return (start + offset until end) to spec
        }
    }

    /// ينسخ [from]..[to] من الملفّ إلى [sink] بصومعة ثابتة (بلا تحميل كامل).
    private fun copyRange(file: File, from: Long, to: Long, sink: java.io.OutputStream) {
        var remaining = to - from + 1L
        if (remaining <= 0L) return
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buffer = ByteArray(COPY_BUFFER)
            while (remaining > 0L) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = raf.read(buffer, 0, want)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    /// يعيد (إزاحة أول إطار داخل النافذة، بصمة الترميز).
    private fun firstFrameInWindow(b: ByteArray): Pair<Int, Int> {
        var start = 0
        val end = b.size

        // أول إطار MPEG صالح (مع التحقق أن ما يليه إطار صالح أيضاً).
        var p = start
        while (p + 4 <= end) {
            if (byteAt(b, p) == 0xFF && (byteAt(b, p + 1) and 0xE0) == 0xE0) {
                val len = frameLength(b, p)
                if (len > 0) {
                    val next = p + len
                    if (next + 4 > end ||
                        (byteAt(b, next) == 0xFF && (byteAt(b, next + 1) and 0xE0) == 0xE0)
                    ) {
                        break
                    }
                }
            }
            p++
        }
        if (p + 4 > end) throw Mp3FormatException("لم يُعثر على صوت MPEG في الملف")
        start = p

        // إطار Xing/Info/VBRI الوصفي — يُحذف كي لا تظهر مدة خاطئة بعد الدمج.
        val firstLen = frameLength(b, start)
        if (firstLen > 0 && start + firstLen <= end) {
            if (hasVbrMarker(b, start, start + firstLen)) start += firstLen
        }

        // بعد تخطي إطار Xing قد ينتهي المدى أو يفسد الرأس — تحقق قبل القراءة.
        if (start + 4 > end || byteAt(b, start) != 0xFF || (byteAt(b, start + 1) and 0xE0) != 0xE0) {
            throw Mp3FormatException("لم يُعثر على صوت MPEG في الملف")
        }
        val h1 = byteAt(b, start + 1)
        val h2 = byteAt(b, start + 2)
        val h3 = byteAt(b, start + 3)
        val spec = (((h1 shr 3) and 0x03) shl 8) or
            (((h1 shr 1) and 0x03) shl 6) or
            (((h2 shr 2) and 0x03) shl 4) or
            ((h3 shr 6) and 0x03)
        return start to spec
    }

    private fun hasVbrMarker(b: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to - 3) {
            val c = byteAt(b, i)
            if (c == 0x58 && byteAt(b, i + 1) == 0x69 && byteAt(b, i + 2) == 0x6E && byteAt(b, i + 3) == 0x67) return true // Xing
            if (c == 0x49 && byteAt(b, i + 1) == 0x6E && byteAt(b, i + 2) == 0x66 && byteAt(b, i + 3) == 0x6F) return true // Info
            if (c == 0x56 && byteAt(b, i + 1) == 0x42 && byteAt(b, i + 2) == 0x52 && byteAt(b, i + 3) == 0x49) return true // VBRI
        }
        return false
    }

    private val srV1 = intArrayOf(44100, 48000, 32000)
    private val brV1L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val brV1L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val brV1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val brV2L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val brV2L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /// طول إطار MPEG بالبايتات انطلاقاً من رأسه، أو -1 إن كان الرأس فاسداً.
    private fun frameLength(b: ByteArray, p: Int): Int {
        if (p + 4 > b.size) return -1
        val h1 = byteAt(b, p + 1)
        val h2 = byteAt(b, p + 2)
        val version = (h1 shr 3) and 0x03 // 0=MPEG2.5 1=محجوز 2=MPEG2 3=MPEG1
        val layer = (h1 shr 1) and 0x03 // 1=III 2=II 3=I
        val brIdx = (h2 shr 4) and 0x0F
        val srIdx = (h2 shr 2) and 0x03
        val padding = (h2 shr 1) and 0x01
        if (version == 1 || layer == 0 || brIdx == 0 || brIdx == 15 || srIdx == 3) return -1

        val base = srV1[srIdx]
        val sampleRate = when (version) {
            3 -> base
            2 -> base / 2
            else -> base / 4
        }

        val isV1 = version == 3
        val table = when (layer) {
            3 -> if (isV1) brV1L1 else brV2L1 // Layer I
            2 -> if (isV1) brV1L2 else brV2L23 // Layer II
            else -> if (isV1) brV1L3 else brV2L23 // Layer III
        }
        val bitrate = table[brIdx] * 1000
        if (bitrate == 0) return -1

        if (layer == 3) {
            // Layer I
            return (12 * bitrate / sampleRate + padding) * 4
        }
        // Layer II دائماً 144؛ Layer III: للـ MPEG2/2.5 المعامل 72
        val coef = if (layer == 1 && !isV1) 72 else 144
        return coef * bitrate / sampleRate + padding
    }
}
