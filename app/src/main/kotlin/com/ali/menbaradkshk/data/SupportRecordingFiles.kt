package com.ali.menbaradkshk.data

import java.io.File
import java.io.IOException

internal const val SUPPORT_AUDIO_MAX_BYTES = 10L * 1024 * 1024
private val outboxStem = Regex("[a-f0-9]{8}-[a-f0-9](?:[a-f0-9]{3}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})?")
private val legacyBasename = Regex("voice_[0-9]{1,19}\\.m4a")

/** Only app-owned direct child files take part in copying, uploading or removal. */
internal class SupportRecordingFiles(
    private val drafts: File,
    private val recorderCache: File,
) {
    fun ownedDraft(file: File, id: String): Boolean =
        outboxStem.matches(id) && file.name == "$id.m4a" && direct(file, drafts)

    fun ownedScratch(file: File): Boolean =
        legacyBasename.matches(file.name) && direct(file, recorderCache)

    fun stage(file: File, id: String): File {
        require(ownedScratch(file) && file.isFile) { "Invalid local recording" }
        require(outboxStem.matches(id))
        check(drafts.isDirectory || drafts.mkdirs())
        val target = File(drafts, "$id.m4a")
        require(ownedDraft(target, id) && !target.exists())
        val expected = file.length()
        require(expected in 1..SUPPORT_AUDIO_MAX_BYTES)
        val temp = File.createTempFile("draft_", ".part", drafts)
        try {
            file.inputStream().use { input ->
                temp.outputStream().use { output ->
                    val block = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        val size = input.read(block)
                        if (size < 0) break
                        count += size
                        require(count <= expected) { "Local recording changed" }
                        output.write(block, 0, size)
                    }
                    require(count == expected) { "Incomplete local recording" }
                    output.fd.sync()
                }
            }
            if (!temp.renameTo(target)) throw IOException("Cannot commit local recording")
            return target
        } finally {
            temp.delete()
        }
    }

    fun removeOwned(file: File, id: String) {
        if (ownedDraft(file, id) || ownedScratch(file)) {
            check(!file.exists() || file.delete()) { "Cannot delete local recording" }
        }
    }

    fun clear() {
        drafts.listFiles()?.forEach { file ->
            val stem = file.name.removeSuffix(".m4a")
            if (ownedDraft(file, stem) || (file.name.startsWith("draft_") && file.name.endsWith(".part") && direct(file, drafts))) {
                check(file.delete() || !file.exists())
            }
        }
        recorderCache.listFiles()?.forEach { file ->
            if (ownedScratch(file)) check(file.delete() || !file.exists())
        }
    }

    private fun direct(file: File, directory: File): Boolean = runCatching {
        val root = directory.canonicalFile
        val parent = file.absoluteFile.parentFile
        (parent == directory.absoluteFile || parent == root) && file.canonicalFile == File(root, file.name)
    }.getOrDefault(false)
}

/** Access from the Store's single outbox lock. A failed preference write is not a queue update. */
internal class CommittedSupportOutbox<T>(private val load: () -> List<T>) {
    private var cached: List<T>? = null

    fun current(): List<T> = cached ?: load().toList().also { cached = it }

    fun replace(next: List<T>, persist: () -> Boolean) {
        current() // initialize before persist, which may modify its own memory even on error
        check(persist()) { "Cannot save local support outbox" }
        cached = next.toList()
    }

    fun blockErasedRows() { cached = emptyList() }
}
