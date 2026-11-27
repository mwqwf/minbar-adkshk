package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** Dedicated store under noBackupFilesDir. Call on an I/O thread, not application start/UI. */
internal class DormantMergeScratchStore(
    private val noBackup: File,
    private val layout: Layout = Layout.MergeInputs,
) {
    internal enum class Layout(val rootName: String) {
        MergeInputs(DIRECTORY), OrderedOpus("minbar_private_ordered_opus_v1")
    }

    internal class Attempt internal constructor(
        val directory: File, private val dispose: () -> Unit,
    ) : Closeable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) dispose() }
    }

    /** Invoke from the verified route's I/O lane, including on cold re-entry. */
    fun recoverAbandoned() = locked(::scan)

    private fun <T> locked(block: (File) -> T): T {
        val root = File(noBackup.canonicalFile, layout.rootName)
        if (!root.isDirectory) root.mkdir()
        if (!directDirectory(root)) throw IOException("Private merge store unavailable")
        return synchronized(gate) {
            gateFile(root).use { fence -> fence.channel.lock().use { block(root) } }
        }
    }

    private fun scan(root: File) {
        for (old in children(root)) if (attemptName.matches(old.name) && directDirectory(old) && old !in live) {
            recover(old)
        }
    }

    fun openAttempt(): Attempt = locked { root ->
        scan(root)
        val directory = File(root, "attempt_${UUID.randomUUID()}")
        if (!directory.mkdir()) throw IOException("Private merge attempt unavailable")
        val owner = File(directory, OWNER)
        if (!owner.createNewFile()) throw IOException("Private merge owner unavailable")
        val handle = openExisting(owner)
        try {
            if (handle.channel.tryLock() == null) throw IOException("Private merge attempt busy")
            live.add(directory)
            Attempt(directory) {
                synchronized(gate) {
                    try {
                        handle.use {
                            locked {
                                if (!clear(directory)) throw IOException("Could not clean private merge attempt")
                            }
                        }
                    } finally {
                        live.remove(directory)
                    }
                }
            }
        } catch (failure: Throwable) {
            handle.close()
            throw failure
        }
    }

    fun <R> withAttempt(block: (File) -> R): R {
        return openAttempt().use { block(it.directory) }
    }

    private fun recover(directory: File) {
        val owner = File(directory, OWNER)
        if (!directFile(owner)) {
            // Crash after mkdir but before publishing the lock: only discard an empty owned slot.
            if (children(directory).isEmpty()) directory.delete()
            return
        }
        openExisting(owner).use { handle ->
            val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            lock?.use { clear(directory) }
        }
    }

    /** Recognize only our exact layout; never recursively walk unknown trees or links. */
    private fun clear(directory: File): Boolean {
        if (!directDirectory(directory)) return false
        val entries = children(directory)
        val owner = entries.singleOrNull { it.name == OWNER && directFile(it) } ?: return false
        val merges = entries.filter { it != owner }
        if (layout == Layout.OrderedOpus) {
            if (merges.size > 1 || merges.any { it.name != OPUS_PART || !directFile(it) }) return false
            if (merges.any { !it.delete() }) return false
            return owner.delete() && directory.delete()
        }
        if (merges.size > 1 || merges.any { !mergeName.matches(it.name) || !directDirectory(it) }) return false
        for (merge in merges) {
            val parts = children(merge)
            if (parts.any { !partName.matches(it.name) || !directFile(it) }) return false
            if (parts.any { !it.delete() } || !merge.delete()) return false
        }
        return owner.delete() && directory.delete()
    }

    private fun gateFile(root: File): RandomAccessFile {
        val path = File(root, ".scan")
        path.createNewFile() // exclusive create; does not replace an existing path
        return openExisting(path)
    }

    private fun openExisting(file: File): RandomAccessFile {
        if (!directFile(file)) throw IOException("Private merge lock unavailable")
        return RandomAccessFile(file, "rw")
    }

    private fun children(directory: File): List<File> =
        directory.listFiles()?.toList() ?: throw IOException("Cannot read private merge directory")

    private fun directFile(file: File) = file.isFile && file.canonicalFile == file
    private fun directDirectory(file: File) = file.isDirectory && file.canonicalFile == file

    companion object {
        internal const val DIRECTORY = "minbar_private_merge_v1"
        internal const val OWNER = ".owner"
        private val live = mutableSetOf<File>() // do not open/close a second descriptor of a locally locked inode
        private val gate = Any() // prevent JVM-wide overlapping locks on the persistent scan fence
        private const val uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private val attemptName = Regex("attempt_$uuid")
        private val mergeName = Regex("merge_$uuid")
        internal const val OPUS_PART = "opus-result.part"
        private val partName = Regex("part_[0-3]\\.bin")
    }
}
