package com.ali.menbaradkshk.ui

import android.content.ClipData
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
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val path = vm.store.localAudioPath(lesson.id) ?: return@runCatching null
                val file = File(path)
                if (!file.isFile || file.length() <= 0L) return@runCatching null
                val shared = stageAudioShare(context.cacheDir, file, lesson.displayTitle)
                    ?: return@runCatching null
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        shared.file,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = audioMimeType(shared.file.name)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, lesson.displayTitle)
                        clipData = ClipData.newRawUri(lesson.displayTitle, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    intent to shared
                } catch (e: Exception) {
                    shared.discard()
                    throw e
                }
            }.getOrNull()
        }
        if (prepared == null) {
            // البند لا يظهر أصلاً لغير المنزَّل، فبلوغُنا هنا يعني أنّ الملفّ
            // أُزيل من تحتنا — نُخبر بالعربيّة بلا مصطلح تقنيّ.
            vm.showMessage("تعذّر تجهيز الملف للإرسال. تأكّد أنّ الدرس ما يزال منزَّلاً وأنّ هناك مساحة كافية.")
            return@launch
        }
        val (intent, shared) = prepared
        try {
            context.startActivity(Intent.createChooser(intent, "إرسال الملف الصوتي"))
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { shared.discard() }
            vm.showMessage("تعذّر فتح تطبيق لإرسال الملف.")
        }
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
