package com.ali.menbaradkshk.adminsurface.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val pendingImagePath = Regex("/admin/transcript-review/(tsub_(?:[0-9a-f]{20}|[0-9]{10,16}_[0-9a-f]{6}))/images/0")
internal fun isPendingImagePath(path: String): Boolean = pendingImagePath.matches(path)
private const val maxImageBytes = 10_485_760
internal const val pendingImageAccept = "image/jpeg,image/png,image/webp"

internal fun previewResponseLength(mime: String?, length: String?, cache: String?, encoding: String?): Int {
    require(mime in setOf("image/jpeg", "image/png", "image/webp"))
    require(encoding == null || encoding.equals("identity", ignoreCase = true))
    val directives = cache.orEmpty().split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }.toSet()
    require(directives == setOf("private", "no-store"))
    require(length != null && length.matches(Regex("[1-9][0-9]{0,7}")))
    return length.toInt().also { require(it in 1..maxImageBytes) }
}

internal suspend fun decodePendingImage(input: InputStream, length: Int, mime: String, permitted: () -> Boolean): Bitmap {
    require(length in 1..maxImageBytes)
    val bytes = ByteArray(length)
    try {
        var offset = 0
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            check(permitted())
            val read = input.read(bytes, offset, minOf(length - offset, 16_384))
            require(read > 0)
            offset += read
        }
        check(permitted())
        require(input.read() == -1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, length, bounds)
        require(bounds.outMimeType == mime)
        val sample = privateImageSample(bounds.outWidth, bounds.outHeight)
        currentCoroutineContext().ensureActive()
        check(permitted())
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, length, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
    } finally {
        bytes.fill(0)
    }
}

internal fun privateImageSample(width: Int, height: Int): Int {
    require(width in 1..6144 && height in 1..6144 && width.toLong() * height <= 24_000_000L)
    var sample = 1
    while (maxOf(width, height) / sample > 1536) sample *= 2
    return sample
}

internal class AdminTranscriptImagePreview(
    private val permitted: () -> Boolean,
    private val fetch: suspend (String) -> Bitmap,
) {
    fun available() = permitted()

    suspend fun firstPage(item: TranscriptPreviewItem): Bitmap {
        require(item.kind == TranscriptPreviewKind.TextAndPages)
        val path = "/admin/transcript-review/${item.id}/images/0"
        require(isPendingImagePath(path))
        currentCoroutineContext().ensureActive()
        check(available())
        val bitmap = fetch(path)
        try {
            currentCoroutineContext().ensureActive()
            check(available())
            return bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }
}
