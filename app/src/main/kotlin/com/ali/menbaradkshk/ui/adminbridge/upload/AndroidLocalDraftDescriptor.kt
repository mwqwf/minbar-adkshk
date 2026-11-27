package com.ali.menbaradkshk.ui.adminbridge.upload

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.IOException

/** A borrowed descriptor, not a file path, URI, grant, or authorization to upload. */
internal class AndroidLocalDraftDescriptor private constructor(
    val draftId: String,
    val byteCount: Long,
    val sha256: String,
    val descriptor: ParcelFileDescriptor,
    private val opened: StructStat,
) : Closeable {
    override fun close() = descriptor.close()

    internal fun verifyCurrent(file: File) = localIo {
        if (file.absoluteFile != file.canonicalFile ||
            !sameRegular(opened, Os.lstat(file.path), byteCount) ||
            !sameRegular(opened, Os.fstat(descriptor.fileDescriptor), byteCount))
            throw IOException("Local merge draft changed")
    }

    companion object {
        /** Call while the store owns its account lock. Read/hash the identical opened inode. */
        internal fun open(file: File, draft: AdminUploadDraft, checkActive: () -> Unit): AndroidLocalDraftDescriptor =
            localIo {
                checkActive()
                if (file.absoluteFile != file.canonicalFile) throw IOException("Unsafe local merge draft")
                val before = Os.lstat(file.path)
                if (!sameRegular(before, before, draft.byteCount)) throw IOException("Invalid local merge draft")
                val flags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                    OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK
                checkActive()
                val raw = Os.open(file.path, flags, 0)
                val descriptor = try {
                    checkActive()
                    ParcelFileDescriptor.dup(raw)
                } finally {
                    // dup owns the independent lifetime; raw is never handed out.
                    // Linux close consumes the fd even if it reports a late I/O error.
                    try { Os.close(raw) } catch (_: ErrnoException) { }
                }
                try {
                    checkActive()
                    val fd = descriptor.fileDescriptor
                    val opened = Os.fstat(fd)
                    if (!sameRegular(before, opened, draft.byteCount) ||
                        Os.fcntlInt(fd, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE != OsConstants.O_RDONLY)
                        throw IOException("Local merge descriptor changed")
                    verifyLocalDraftBytes(draft.byteCount, draft.sha256, checkActive) { offset, buffer, limit ->
                        Os.pread(fd, buffer, 0, limit, offset)
                    }
                    val result = AndroidLocalDraftDescriptor(draft.id, draft.byteCount, draft.sha256, descriptor, opened)
                    checkActive()
                    result.verifyCurrent(file)
                    checkActive()
                    result
                } catch (t: Throwable) {
                    try { descriptor.close() } catch (cleanup: Throwable) {
                        if (t !== cleanup) t.addSuppressed(cleanup)
                    }
                    throw t
                }
            }
    }
}

private fun sameRegular(a: StructStat, b: StructStat, size: Long): Boolean =
    OsConstants.S_ISREG(a.st_mode) && OsConstants.S_ISREG(b.st_mode) &&
        a.st_nlink == 1L && b.st_nlink == 1L &&
        a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_mode == b.st_mode && a.st_size == size && b.st_size == size &&
        a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

/** Do not surface a file name or URI through platform diagnostics. */
private inline fun <T> localIo(block: () -> T): T = try { block() }
catch (_: ErrnoException) { throw IOException("Local merge descriptor unavailable") }
