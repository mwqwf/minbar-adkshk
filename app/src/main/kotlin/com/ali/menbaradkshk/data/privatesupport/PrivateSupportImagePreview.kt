package com.ali.menbaradkshk.data.privatesupport

import android.graphics.Bitmap
import android.graphics.BitmapFactory

private fun withinPrivateSupportDimensions(width: Int, height: Int): Boolean =
    width in 1..2048 && height in 1..2048 && width * height <= 2_000_000

/** Consumes the byte buffer. An accepted bitmap must be recycled on scope exit by its caller. */
internal fun consumePrivateSupportPreview(
    buffer: ByteArray,
    stillCurrent: () -> Boolean,
): Bitmap? {
    var result: Bitmap? = null
    var releaseToCaller = false
    try {
        if (buffer.size !in 4..262_144 || !stillCurrent()) return null
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeByteArray(buffer, 0, buffer.size, bounds)
        if (bounds.outMimeType != "image/jpeg" ||
            !withinPrivateSupportDimensions(bounds.outWidth, bounds.outHeight) ||
            !stillCurrent()
        ) return null
        val pixels = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(buffer, 0, buffer.size, pixels) ?: return null
        result = decoded
        if (decoded.width != bounds.outWidth || decoded.height != bounds.outHeight ||
            decoded.allocationByteCount > 8_192_000 || !stillCurrent()
        ) return null
        releaseToCaller = true
        return decoded
    } catch (_: IllegalArgumentException) {
        return null
    } finally {
        buffer.fill(0)
        if (!releaseToCaller) result?.recycle()
    }
}
