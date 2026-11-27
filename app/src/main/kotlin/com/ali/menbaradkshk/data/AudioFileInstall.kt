package com.ali.menbaradkshk.data

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/** Install verified bytes without overwriting a path that is currently published. */
internal fun installVerifiedAudio(
    partial: File,
    target: File,
    rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    publish: (File) -> Boolean,
): File {
    val destination = File(target.parentFile, "${target.nameWithoutExtension}-${UUID.randomUUID()}.${target.extension}")
    check(!destination.exists())
    // The stream cache path also arrives here; sync it before declaring the file complete.
    FileInputStream(partial).use { it.fd.sync() }
    if (!rename(partial, destination)) throw IOException("تعذّر تثبيت ملف الصوت المكتمل.")
    try {
        if (!publish(destination)) throw IOException("تعذّر حفظ فهرس تنزيل الصوت.")
    } catch (failure: Exception) {
        // Best effort: the previous published path was never touched even if rollback fails.
        if (!partial.exists()) runCatching { rename(destination, partial) }
        throw failure
    }
    return destination
}
