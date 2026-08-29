package com.ali.menbaradkshk.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجلّ تشخيص حلقيّ محليّ (≤64 ك.ب) لأعطال التنزيل والتشغيل وحدها.
 *
 * لماذا: لا Crashlytics ولا Analytics في منبر عمداً (خصوصية)، فكانت شكوى
 * «الدرس لا يعمل» عمىً كاملاً. هذا السجل يبقى على الجهاز، بلا أي هويات
 * (معرّف درس + كود خطأ + نسخة أندرويد فقط)، ولا يغادر الجهاز إلا إن أرفقه
 * المستخدم **بنفسه** مع رسالة «راسِل المطوّر».
 *
 * الكتابة رخيصة (سطر يُلحق)، والقصّ عند تجاوز السقف يبقي النصف الأحدث.
 */
object DiagLog {
    private const val MAX_BYTES = 64L * 1024L
    private const val FILE_NAME = "diag_events.log"
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val line = "${stamp.format(Date())} [$tag] ${message.take(300)}\n"
            file.appendText(line)
            if (file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }
    }

    /// لقطة السجل لإرفاقها الاختياري برسالة دعم — فارغة إن لا أحداث.
    fun snapshot(context: Context): String = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText().orEmpty()
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
