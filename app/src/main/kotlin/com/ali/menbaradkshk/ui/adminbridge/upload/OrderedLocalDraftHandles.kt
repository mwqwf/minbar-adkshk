package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest

/** In-memory identity only; a future verified-account host must revoke/rotate at terminal exit. */
internal class LocalDraftConversionLease(val accountKey: String) {
    init { require(accountKey.matches(Regex("[0-9a-f]{64}"))) }
}

internal const val MAX_LOCAL_MERGE_INPUTS = 4
internal const val MAX_LOCAL_MERGE_BYTES = MAX_LOCAL_MERGE_INPUTS * MAX_ORIGINAL_BYTES

/** Scoped handoff only. A future same-UID consumer must check its own lease and expected hash. */
internal fun <D, H : Closeable, R> withOrderedLocalDraftHandles(
    selected: List<D>,
    id: (D) -> String,
    bytes: (D) -> Long,
    sha: (D) -> String,
    checkActive: () -> Unit,
    openVerified: (D, () -> Unit) -> H,
    verifyCurrent: (D, H) -> Unit,
    consume: (List<H>) -> R,
): R {
    checkActive()
    if (selected.size !in 2..MAX_LOCAL_MERGE_INPUTS) throw IOException("Invalid local merge count")
    val ordered = selected.toList()
    if (ordered.size !in 2..MAX_LOCAL_MERGE_INPUTS) throw IOException("Local merge selection changed")
    val ids = HashSet<String>()
    val hashes = HashSet<String>()
    var remaining = MAX_LOCAL_MERGE_BYTES
    for (draft in ordered) {
        val size = bytes(draft)
        if (size !in 1..MAX_ORIGINAL_BYTES || size > remaining || !ids.add(id(draft)) || !hashes.add(sha(draft)))
            throw IOException("Invalid local merge selection")
        remaining -= size
    }
    val handles = ArrayList<H>(ordered.size)
    var failure: Throwable? = null
    try {
        for (draft in ordered) {
            checkActive()
            val handle = openVerified(draft, checkActive)
            handles += handle // Own it before the next fallible lease/draft check.
            checkActive()
            verifyCurrent(draft, handle)
        }
        for ((index, draft) in ordered.withIndex()) {
            checkActive()
            verifyCurrent(draft, handles[index])
        }
        checkActive()
        val result = consume(handles.toList())
        checkActive()
        return result
    } catch (t: Throwable) {
        failure = t
        throw t
    } finally {
        var pending = failure
        for (handle in handles.asReversed()) {
            try {
                handle.close()
            } catch (cleanup: Throwable) {
                if (pending == null) pending = cleanup else if (pending !== cleanup) pending.addSuppressed(cleanup)
            }
        }
        if (failure == null && pending != null) throw pending
    }
}

/** Positional reads keep the verified descriptor positioned at the beginning for a future decoder.
 * readAt follows POSIX pread: 0 means EOF. It never needs paths or stream grants. */
internal fun verifyLocalDraftBytes(
    byteCount: Long,
    sha: String,
    checkActive: () -> Unit,
    readAt: (Long, ByteArray, Int) -> Int,
) {
    if (!sha.matches(Regex("[0-9a-f]{64}")) || byteCount !in 1..MAX_ORIGINAL_BYTES)
        throw IOException("Invalid local merge draft")
    val expected = ByteArray(32) { i -> sha.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var offset = 0L
    try {
        while (offset < byteCount) {
            checkActive()
            val limit = minOf(buffer.size.toLong(), byteCount - offset).toInt()
            val read = readAt(offset, buffer, limit)
            if (read !in 1..limit) throw IOException("Local merge draft length changed")
            digest.update(buffer, 0, read)
            offset += read
        }
        checkActive()
        if (readAt(offset, buffer, 1) != 0 || !MessageDigest.isEqual(digest.digest(), expected))
            throw IOException("Local merge draft bytes changed")
        checkActive()
    } finally {
        buffer.fill(0)
    }
}
