package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections

internal const val MULTIPART_CHUNK_BYTES = 8 * 1024 * 1024
internal const val MAX_MULTIPART_PARTS = 12
private const val HASH_BUFFER_BYTES = 64 * 1024
private val lowercaseSha256 = Regex("[a-f0-9]{64}")

/** The future protocol carries only verified digests and lengths. No account, path, or audio bytes. */
internal data class AdminMultipartPart(val index: Int, val bytes: Int, val sha256: String)
internal data class AdminMultipartManifest(
    val totalBytes: Long,
    val wholeSha256: String,
    val parts: List<AdminMultipartPart>,
)

/** Reads only the declared bytes plus one EOF probe. Caller owns the stream. */
internal fun verifyAdminMultipartManifest(
    input: InputStream,
    totalBytes: Long,
    declaredSha256: String,
    checkActive: () -> Unit,
): AdminMultipartManifest {
    if (totalBytes !in 1..MAX_ORIGINAL_BYTES) throw DraftSizeException()
    require(lowercaseSha256.matches(declaredSha256))
    val partCount = ((totalBytes + MULTIPART_CHUNK_BYTES - 1) / MULTIPART_CHUNK_BYTES).toInt()
    check(partCount in 1..MAX_MULTIPART_PARTS)
    val full = MessageDigest.getInstance("SHA-256")
    val chunk = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    val parts = ArrayList<AdminMultipartPart>(partCount)
    var remaining = totalBytes
    try {
        while (remaining > 0) {
            val length = minOf(remaining, MULTIPART_CHUNK_BYTES.toLong()).toInt()
            var left = length
            while (left > 0) {
                checkActive()
                val requested = minOf(left, buffer.size)
                val n = input.read(buffer, 0, requested)
                if (n <= 0 || n > requested) throw IOException("Local draft length changed or source unreadable")
                full.update(buffer, 0, n)
                chunk.update(buffer, 0, n)
                left -= n
            }
            parts += AdminMultipartPart(parts.size + 1, length, chunk.digest().toLowerHex())
            remaining -= length
        }
        checkActive()
        if (input.read() != -1) throw IOException("Local draft length changed")
        val whole = full.digest().toLowerHex()
        if (whole != declaredSha256) throw IOException("Local draft hash changed")
        checkActive()
        return AdminMultipartManifest(totalBytes, whole, Collections.unmodifiableList(parts))
    } finally {
        buffer.fill(0)
    }
}

private fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (b in this@toLowerHex) {
            val n = b.toInt() and 0xff
            append(digits[n ushr 4])
            append(digits[n and 0x0f])
        }
    }
}
