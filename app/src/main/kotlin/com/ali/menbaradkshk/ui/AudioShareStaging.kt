package com.ali.menbaradkshk.ui

import java.io.File
import java.util.UUID

private const val SHARE_TTL_MS = 24 * 60 * 60_000L
private val attemptName = Regex("share-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

internal class StagedAudioShare(val file: File) {
    fun discard() {
        file.delete()
        file.parentFile?.delete()
    }
}

/** Keep the receiver's filename readable, but give each URI its own immutable snapshot. */
internal fun stageAudioShare(
    cacheDir: File,
    source: File,
    title: String,
    now: Long = System.currentTimeMillis(),
): StagedAudioShare? {
    val directory = File(cacheDir, "audio-share-attempts")
    if (!directory.mkdir() && !directory.isDirectory) return null
    directory.listFiles()?.forEach { old ->
        if (!attemptName.matches(old.name) || !old.isDirectory ||
            old.lastModified() !in 1..(now - SHARE_TTL_MS)
        ) return@forEach
        // Only our format in our private subtree; never walk unrelated cache contents.
        runCatching {
            if (old.canonicalFile == File(directory.canonicalFile, old.name)) {
                old.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                old.delete()
            }
        }
    }

    val attempt = File(directory, "share-${UUID.randomUUID()}")
    if (!attempt.mkdir()) return null
    val safeTitle = title
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "درس" }
    val extension = when (val raw = source.extension.lowercase()) {
        "mp3", "m4a", "aac", "wav", "flac", "amr", "opus", "ogg" -> raw
        "ogx", "oga", "ogv" -> "ogg"
        "m4b", "mp4" -> "m4a"
        "3gp", "3gpp" -> "3gp"
        else -> "mp3"
    }
    val shared = StagedAudioShare(File(attempt, "$safeTitle.$extension"))
    return try {
        val expected = source.length()
        val copied = source.inputStream().use { input ->
            shared.file.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied <= 0 || copied != expected) {
            shared.discard()
            null
        } else {
            attempt.setLastModified(now)
            shared
        }
    } catch (_: Exception) {
        shared.discard()
        null
    }
}
