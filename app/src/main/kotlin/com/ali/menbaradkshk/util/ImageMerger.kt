package com.ali.menbaradkshk.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.toBitmap
import com.ali.menbaradkshk.data.TranscriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🖼️ دمج صور صفحات الكتاب عموديّاً في صورة واحدة — نظير AudioMerger
 * للصوتيات: الترتيب الذي اختاره المستخدم (أيّها أولاً) هو ترتيب اللصق من
 * الأعلى للأسفل. تُوحَّد الأعراض وتُضغط النتيجة JPEG في كاش التطبيق.
 *
 * فكّ الترميز يمرّ بـ**Coil** لا بـ`BitmapFactory` مباشرةً: فحص Play يرصد
 * فكّ الترميز اليدوي ويحذّر من «استخدام مفرط للذاكرة وبطء وتعطّل»، ومكتبة
 * التحميل تتولّى تقليل الدقّة وإدارة الذاكرة. ومكسب إضافي: Coil يطبّق اتجاه
 * EXIF بنفسه، فسقط التحويل اليدوي الذي كان يحاكيه (وكان تكراره بعد Coil
 * سيدوّر الصورة مرّتين).
 */
object ImageMerger {
    const val MAX_MERGE_IMAGES = 4
    private const val TARGET_WIDTH = 1440
    private const val MAX_TOTAL_HEIGHT = 14_000

    /** يدمج الصور بترتيبها ويعيد Uri لملف الناتج في كاش التطبيق. */
    suspend fun mergeVertically(context: Context, uris: List<Uri>): Uri {
        require(uris.size in 2..MAX_MERGE_IMAGES) { "اختر صورتين إلى أربع صور للدمج." }
        // الجلب خارج Dispatchers.IO: `execute` معلَّقة وتدير خيوطها بنفسها.
        // ⚠️ كانت كل الصور تُفكّ بميزانية الطول الكاملة ثم يُتحقَّق من الطول
        // الكلّي **بعد** تخصيصها كلّها — أي أربع بِتمابات ضخمة قبل أن يرفضها
        // الحارس أصلاً. الآن تُفكّ واحدةً واحدة بما تبقّى من الميزانية، ويقع
        // الرفض عند أوّل تجاوز فلا تُخصَّص بقيّة الصور.
        val bitmaps = ArrayList<Bitmap>(uris.size)
        val heights = ArrayList<Int>(uris.size)
        var remainingHeight = MAX_TOTAL_HEIGHT
        for (uri in uris) {
            val bitmap = decodeScaled(context, uri, remainingHeight)
            val height = (bitmap.height.toLong() * TARGET_WIDTH / bitmap.width)
                .toInt().coerceAtLeast(1)
            require(height <= remainingHeight) {
                "الصور طويلة جداً للدمج في صورة واحدة — قصّها أولاً أو أرسلها منفصلة."
            }
            bitmaps.add(bitmap)
            heights.add(height)
            remainingHeight -= height
        }
        return withContext(Dispatchers.IO) {
            val width = TARGET_WIDTH
            val totalHeight = heights.sum()
            val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(merged)
                canvas.drawColor(Color.WHITE)
                var top = 0
                bitmaps.forEachIndexed { index, bmp ->
                    val h = heights[index]
                    canvas.drawBitmap(bmp, null, Rect(0, top, width, top + h), null)
                    top += h
                }
                val dir = File(context.cacheDir, "merged_pages").apply { mkdirs() }
                // نظافة الكاش: نواتج دمج قديمة لم تُستعمل تُحذف بعد يوم.
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                val out = File(dir, "pages_${System.currentTimeMillis()}.jpg")
                // ⛔ الملف الناتج كان يُضغط بجودة ثابتة بلا فحص حجمه، فيرفضه حدّ
                // 10MB الذي يفرضه المستودع على الصورة الواحدة — أي أن التطبيق
                // ينتج ملفاً يرفضه هو نفسه. نُنقص الجودة تدريجياً حتى يدخل الحدّ.
                var written = false
                for (quality in intArrayOf(88, 78, 68, 58)) {
                    written = out.outputStream().use { stream ->
                        merged.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                    }
                    if (written && out.length() <= TranscriptRepository.MAX_IMAGE_BYTES) break
                }
                val fits = written && out.length() <= TranscriptRepository.MAX_IMAGE_BYTES
                // لا نترك ملفاً ضخماً بلا فائدة في الكاش حين يتعذّر التصغير.
                if (!fits) out.delete()
                check(written) { "تعذّر حفظ الصورة المدموجة." }
                check(fits) { "الصورة المدموجة كبيرة جداً — قصّها أو أرسل الصور منفصلة." }
                Uri.fromFile(out)
            } finally {
                merged.recycle()
            }
        }
    }

    /**
     * يقرأ الصورة بمقاس مناسب عبر Coil.
     *
     * ⚠️ لا نُحرّر (`recycle`) البِتماب العائد: قد يملكه Coil. ولذلك نُعطّل
     * كاش الذاكرة لهذه الطلبات تحديداً كي لا تتضخّم بصور ضخمة تُستعمل مرّة
     * واحدة، ونترك تحريرها لجامع المهملات.
     *
     * [maxHeight] ما تبقّى من ميزانية طول اللوحة: لا معنى لفكّ صورة أطول ممّا
     * يمكن أن تشغله في الناتج، والصورة المتجاوِزة تُرفض بعدها فوراً.
     */
    private suspend fun decodeScaled(context: Context, uri: Uri, maxHeight: Int): Bitmap {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(TARGET_WIDTH, maxHeight.coerceAtLeast(1))
            .precision(Precision.INEXACT)
            // ARGB_8888 لا HARDWARE: البِتماب العتاديّ لا يُرسم على Canvas برمجيّ.
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val image = (result as? SuccessResult)?.image
        requireNotNull(image) { "تعذّرت قراءة إحدى الصور." }
        val bitmap = (image as? BitmapImage)?.bitmap
            ?: image.toBitmap(image.width, image.height)
        // شبكة أمان: لو عاد بِتماب عتاديّ رغم الإعداد، ننسخه نسخة برمجيّة.
        // ⚠️ `Bitmap.Config.HARDWARE` أُضيف في أندرويد ٨ (API 26) وأدنى ما ندعمه
        // أندرويد ٦ (API 23): مجرّد **قراءة** الثابت على جهاز أقدم تُلقي
        // `NoSuchFieldError` فينهار التطبيق عند دمج صور «ساهم بالنص». والحارس
        // مجّانيّ: البِتماب العتاديّ لا وجود له أصلاً قبل ٢٦.
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
    }
}
