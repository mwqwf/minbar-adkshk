package com.ali.menbaradkshk.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.ali.menbaradkshk.data.Lesson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 📤 «أرسل الملف الصوتي» — انتشار الدرس **بلا إنترنت إطلاقاً**.
 *
 * **لماذا؟** المشاركة العادية ترسل رابطاً، والرابط لا ينفع من لا إنترنت
 * عنده — وهو حال أكثر جمهور التطبيق. أمّا الدرس المنزَّل فملفُّه على الجهاز
 * فعلاً، فيمكن أن ينتقل بالبلوتوث أو بأيّ تطبيق مشاركة ملفّات بين هاتفين في
 * مجلسٍ واحد بلا شبكة.
 *
 * والفرق عن زرّ المشاركة العام مقصود: **لا نصّ ولا رابط في النيّة** — بعض
 * مستقبِلي الملفّات (البلوتوث خاصّة) يربكهم وجود نصّ مع الملفّ فيرسلون
 * النصّ وحده. هنا الملفّ هو الرسالة كلّها.
 */
fun sendLessonAudioFile(context: Context, vm: AppViewModel, lesson: Lesson) {
    vm.viewModelScope.launch {
        // ⚠️ تجهيز النسخة **نسخُ ملفّ صوتيّ كامل** قد يبلغ عشرات
        // الميغابايتات — على خيط الواجهة يتجمّد التطبيق (ANR).
        val intent = withContext(Dispatchers.IO) {
            runCatching {
                val path = vm.store.localAudioPath(lesson.id) ?: return@runCatching null
                val file = File(path)
                if (!file.isFile || file.length() <= 0L) return@runCatching null
                val shared = readableAudioCopy(context, file, lesson.displayTitle) ?: file
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shared,
                )
                Intent(Intent.ACTION_SEND).apply {
                    type = audioMimeType(shared.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, lesson.displayTitle)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.getOrNull()
        }
        if (intent == null) {
            // البند لا يظهر أصلاً لغير المنزَّل، فبلوغُنا هنا يعني أنّ الملفّ
            // أُزيل من تحتنا — نُخبر بالعربيّة بلا مصطلح تقنيّ.
            vm.showMessage("تعذّر إرسال الملف. تأكّد أنّ الدرس ما يزال منزَّلاً.")
            return@launch
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "إرسال الملف الصوتي"))
        }.onFailure { vm.showMessage("لا يوجد تطبيق يستقبل الملفّات على هذا الجهاز.") }
    }
}

/// هل للدرس ملفّ صوتيّ حاضر على الجهاز فعلاً؟ يُتحقَّق من القرص لا من
/// السجلّ وحده — بند «أرسل الملف الصوتي» يجب أن **يختفي** لا أن يظهر معطّلاً
/// أو يظهر ثم يفشل.
fun hasLocalAudioFile(vm: AppViewModel, lesson: Lesson): Boolean = runCatching {
    val path = vm.store.localAudioPath(lesson.id) ?: return@runCatching false
    val file = File(path)
    file.isFile && file.length() > 0L
}.getOrDefault(false)

/**
 * نسخة للإرسال باسم **عنوان الدرس** وامتداد قياسيّ، داخل `cache/share`.
 *
 * الملفّ المخزَّن اسمه معرّف داخليّ لا يفهمه المستقبِل («a7f3…»)، وقد يكون
 * امتداده غريباً فلا يُعرف أنّه صوت. والنسخ لا يتراكم: كلّ إرسال يحذف ما
 * مضى عليه عشر دقائق — ⛔ ولا يحذف الأحدث، لأنّ المستقبِل يقرأ الملفّ بعد
 * اختيار المستخدم من المُختار، فحذفٌ فوريّ يقطع إرسالاً جارياً.
 */
private fun readableAudioCopy(context: Context, source: File, title: String): File? = runCatching {
    val extension = standardExtension(source.name.substringAfterLast('.', ""))
    val safeTitle = title
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "درس" }
    val directory = File(context.cacheDir, "share").apply {
        mkdirs()
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
    val target = File(directory, "$safeTitle.$extension")
    source.inputStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    target.takeIf { it.length() > 0L }
}.getOrNull()

/// امتداد يعرفه المستقبِلون — Firebase تعطي أحياناً `.ogx` و`.bin` وما شابه.
private fun standardExtension(raw: String): String = when (raw.lowercase()) {
    "mp3", "m4a", "aac", "wav", "flac", "amr", "opus", "ogg" -> raw.lowercase()
    "ogx", "oga", "ogv" -> "ogg"
    "m4b", "mp4" -> "m4a"
    "3gp", "3gpp" -> "3gp"
    else -> "mp3"
}

/// نوع الملفّ المعلن — بعض المستقبِلين يرفضون "audio/*" المبهم.
private fun audioMimeType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga", "ogx", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "3gp", "3gpp" -> "audio/3gpp"
        else -> "audio/*"
    }
