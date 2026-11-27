package com.ali.menbaradkshk.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Backups contain settings/documents only, never audio, images or download caches.
internal const val BACKUP_MAX_BYTES = 8 * 1024 * 1024

internal suspend fun readBoundedBackup(input: InputStream, limit: Int = BACKUP_MAX_BYTES): String {
    require(limit in 1..BACKUP_MAX_BYTES)
    val out = ByteArrayOutputStream(minOf(limit, 16_384))
    val buffer = ByteArray(minOf(limit, 16_384))
    while (out.size() < limit) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read == -1) return out.toString(Charsets.UTF_8.name())
        require(read > 0)
        out.write(buffer, 0, read)
    }
    currentCoroutineContext().ensureActive()
    require(input.read() == -1) { "Backup exceeds maximum size" }
    currentCoroutineContext().ensureActive()
    return out.toString(Charsets.UTF_8.name())
}
