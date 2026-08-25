package com.ali.menbaradkshk.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🖼️ تصغير صورة قبل إرسالها للمطوّر.
 *
 * **لماذا؟** إنترنت الجمهور ضعيف، وصورة الهاتف اليوم تتجاوز خمسة ميغابايت.
 * الصورة هنا دليلٌ على عطل أو ورقة درس — يكفيها عرضٌ 1280 وجودةٌ متوسّطة،
 * فيهبط الحجم إلى عُشره تقريباً ويصل الرفع في ثوانٍ بدل دقائق.
 *
 * فكّ الترميز يمرّ بـ**Coil** لا بـ`BitmapFactory` (نفس علّة [ImageMerger]):
 * فحص Play يحذّر من فكّ الترميز اليدوي، وCoil يطبّق اتجاه EXIF بنفسه فلا
 * تصل الصورة مقلوبة.
 */
object ImageShrink {
    private const val TARGET_WIDTH = 1280
    private const val MAX_HEIGHT = 4_000

    /** أقصى حجم للصورة الواحدة بعد الضغط. */
    const val MAX_BYTES = 700L * 1_024L

    /** يصغّر الصورة ويعيد ملفّاً في كاش التطبيق جاهزاً للرفع. */
    suspend fun shrink(context: Context, uri: Uri): File {
        // `execute` معلَّقة وتدير خيوطها بنفسها فلا نلفّها بـIO.
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(TARGET_WIDTH, MAX_HEIGHT)
            .precision(Precision.INEXACT)
            // ARGB_8888 لا HARDWARE: البِتماب العتاديّ لا يُضغط برمجيّاً.
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
        requireNotNull(image) { "تعذّرت قراءة الصورة — اختر غيرها." }
        val decoded = (image as? BitmapImage)?.bitmap ?: image.toBitmap(image.width, image.height)
        // شبكة أمان: `Bitmap.Config.HARDWARE` أُضيف في أندرويد ٨، ومجرّد قراءته
        // على أقدم منه تُلقي `NoSuchFieldError` — والحارس مجّانيّ.
        val bitmap = if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            decoded.config == Bitmap.Config.HARDWARE
        ) {
            decoded.copy(Bitmap.Config.ARGB_8888, false) ?: decoded
        } else {
            decoded
        }

        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "support_images").apply { mkdirs() }
            // نظافة الكاش: صور لم تُرسَل تُحذف بعد يوم.
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val out = File(dir, "img_${System.currentTimeMillis()}.jpg")
            // نُنقص الجودة تدريجياً حتى تدخل الحدّ بدل جودة ثابتة قد تتجاوزه.
            var written = false
            for (quality in intArrayOf(80, 70, 60, 45)) {
                written = out.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                }
                if (written && out.length() <= MAX_BYTES) break
            }
            check(written) { "تعذّر تجهيز الصورة." }
            if (out.length() > MAX_BYTES) {
                out.delete()
                error("الصورة كبيرة جداً — اختر صورة أصغر.")
            }
            out
        }
    }
}
