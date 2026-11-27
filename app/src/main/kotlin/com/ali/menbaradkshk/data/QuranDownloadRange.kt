package com.ali.menbaradkshk.data

import java.io.IOException

private val STRONG_ETAG = Regex("\"[!#-~\\u0080-\\u00ff]*\"")
private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)

internal fun strongQuranDownloadETag(value: String?): String? =
    value?.trim()?.takeIf(STRONG_ETAG::matches)

internal fun quranDownloadRangeTotal(header: String?, offset: Long, contentLength: Long?): Long? {
    if (offset <= 0L) return null
    val match = header?.trim()?.let(CONTENT_RANGE::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return total.takeIf {
        start == offset && total > offset && end == total - 1 &&
            (contentLength == null || contentLength == total - offset)
    }
}

internal class QuranDownloadOverrun : IOException("تجاوز النقل حجم الملف المعلن")
