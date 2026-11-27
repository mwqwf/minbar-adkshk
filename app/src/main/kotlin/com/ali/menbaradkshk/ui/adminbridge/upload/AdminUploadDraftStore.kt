package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

internal data class AdminUploadDraft(
    val id: String,
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
)

/** Data is scoped by a pseudonymous key supplied only by the verified admin host. */
internal class AdminUploadDraftStore(
    noBackupDirectory: File,
    accountKey: String,
    private val maxBytes: Long = MAX_ORIGINAL_BYTES,
) {
    private val scope: File
    init {
        require(accountKey.matches(Regex("[0-9a-f]{64}")))
        require(maxBytes in 1..MAX_ORIGINAL_BYTES)
        val base = noBackupDirectory.canonicalFile
        scope = File(File(base, "admin-upload-drafts-v1"), accountKey).absoluteFile
        if (scope.canonicalFile != scope) throw IOException("Unsafe admin draft path")
    }

    /** Only complete records are shown. An interrupted attempt is neither a draft nor queued work. */
    fun isForAccount(accountKey: String): Boolean = scope.name == accountKey

    fun list(): List<AdminUploadDraft> = synchronized(lockFor(scope)) {
        val children = root().listFiles() ?: throw IOException("Cannot list drafts")
        children.asSequence()
            .filter { ID.matches(it.name) }
            .mapNotNull(::readRecord)
            .sortedBy { it.fileName }
            .toList()
    }

    /** Called only after explicit confirmation. Source grants and paths never enter durable metadata. */
    fun prepare(
        fileName: String,
        reportedSize: Long?,
        checkActive: () -> Unit = {},
        validateAudio: (File) -> Unit = {},
        install: (() -> AdminUploadDraft) -> AdminUploadDraft = { it() },
        openOriginal: () -> InputStream,
    ): AdminUploadDraft = synchronized(lockFor(scope)) {
        if (reportedSize != null && (reportedSize <= 0 || reportedSize > maxBytes)) throw DraftSizeException()
        checkActive()
        val folder = root()
        val children = folder.listFiles() ?: throw IOException("Cannot read draft storage")
        // Count even damaged draft directories by actual payload size; never free completed slots silently.
        val complete = children.filter { ID.matches(it.name) && safeDirectory(it) }
        if (complete.size >= MAX_DRAFTS) throw DraftSpaceException()
        val used = complete.sumOf { child ->
            val payload = File(child, AUDIO)
            if (safeFile(payload)) payload.length().coerceAtMost(MAX_LOCAL_BYTES) else 0L
        }
        val available = MAX_LOCAL_BYTES - used
        if (available <= 0) throw DraftSpaceException()
        val maxForThis = minOf(maxBytes, available)
        if (reportedSize != null && reportedSize > maxForThis) throw DraftSpaceException()
        val id = UUID.randomUUID().toString()
        val staging = File(folder, ".$id.part")
        val final = File(folder, id)
        if (!staging.mkdir() || !safeDirectory(staging)) throw IOException("Cannot stage draft")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            val audio = File(staging, AUDIO)
            // Check each count BEFORE writing; do not rely on ContentResolver metadata or available().
            openOriginal().use { source ->
                FileOutputStream(audio).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val n = source.read(buffer, 0, minOf(buffer.size.toLong(), maxForThis - length + 1).toInt())
                        if (n < 0) break
                        if (n == 0) throw IOException("Unreadable draft source")
                        if (n > maxForThis - length) {
                            if (available < maxBytes) throw DraftSpaceException() else throw DraftSizeException()
                        }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        length += n
                    }
                    if (length <= 0) throw DraftSizeException()
                    output.fd.sync()
                }
            }
            checkActive()
            validateAudio(audio)
            checkActive() // validation can outlive the initiating account or URI grant
            val draft = AdminUploadDraft(id, safeDraftFileName(fileName), length, digest.digest().hex())
            complete.asSequence().mapNotNull(::readRecord).firstOrNull {
                it.byteCount == draft.byteCount && it.sha256 == draft.sha256
            }?.let {
                checkActive() // the duplicate branch must not bypass the current lease
                return@synchronized it
            }
            FileOutputStream(File(staging, META)).use { stream ->
                val out = DataOutputStream(stream)
                out.writeInt(VERSION)
                out.writeUTF(draft.fileName)
                out.writeLong(draft.byteCount)
                out.writeUTF(draft.sha256)
                out.flush()
                stream.fd.sync()
            }
            // The staged-review owner may serialize only this final admission with close/revoke.
            // The validating I/O above never holds the review's lifecycle lock.
            install {
                checkActive()
                if (final.exists() || !staging.renameTo(final)) throw IOException("Cannot install draft safely")
                draft
            }
        } finally {
            if (staging.exists()) cleanStagingDirectory(staging)
        }
    }

    /** Removes only interrupted attempts under this account, never completed audio or unknown files. */
    /** Returns only an in-memory, digest-verified snapshot of this account's immutable draft. */
    fun stageSnapshot(id: String, limit: Int): Pair<AdminUploadDraft, ByteArray> = synchronized(lockFor(scope)) {
        require(ID.matches(id) && limit in 1..MAX_STAGE_BYTES)
        val dir = File(root(), id)
        val draft = (if (safeDirectory(dir)) readRecord(dir) else null)
            ?: throw IOException("Local draft unavailable")
        if (draft.byteCount !in 1..limit.toLong()) throw DraftSizeException()
        val audio = File(dir, AUDIO)
        val payload = ByteArray(draft.byteCount.toInt())
        DataInputStream(audio.inputStream().buffered()).use {
            it.readFully(payload)
            if (it.read() != -1) throw IOException("Local draft length changed")
        }
        if (MessageDigest.getInstance("SHA-256").digest(payload).hex() != draft.sha256)
            throw IOException("Local draft hash changed")
        draft to payload
    }

    /** Local-only preparation for a future multipart capability; does not grant upload authority. */
    fun verifyMultipartManifest(
        expectedAccountKey: String,
        draft: AdminUploadDraft,
        checkActive: () -> Unit,
    ): AdminMultipartManifest = synchronized(lockFor(scope)) {
        if (!isForAccount(expectedAccountKey)) throw IOException("Local draft unavailable")
        checkActive()
        val dir = requireCurrentDraft(draft)
        val audio = File(dir, AUDIO)
        if (!safeFile(audio)) throw IOException("Local draft unavailable")
        val manifest = audio.inputStream().use {
            verifyAdminMultipartManifest(it, draft.byteCount, draft.sha256, checkActive)
        }
        checkActive()
        requireCurrentDraft(draft)
        manifest
    }

    /**
     * Dormant local conversion handoff. The callback may only synchronously hand off the borrowed
     * descriptors, not wait for conversion or launch a publication. They close even on cancellation.
     */
    fun <R> withOrderedLocalMergeInputs(
        lease: LocalDraftConversionLease,
        selected: List<AdminUploadDraft>,
        currentLease: () -> LocalDraftConversionLease?,
        checkActive: () -> Unit,
        handoff: (List<AndroidLocalDraftDescriptor>) -> R,
    ): R = synchronized(lockFor(scope)) {
        fun guard() {
            checkActive()
            if (!isForAccount(lease.accountKey) || currentLease() !== lease)
                throw IOException("Local merge lease unavailable")
        }
        withOrderedLocalDraftHandles(selected, AdminUploadDraft::id, AdminUploadDraft::byteCount, AdminUploadDraft::sha256,
            ::guard,
            openVerified = { draft, lease ->
                val audio = File(requireCurrentDraft(draft), AUDIO)
                if (!safeFile(audio)) throw IOException("Local draft unavailable")
                AndroidLocalDraftDescriptor.open(audio, draft, lease)
            },
            verifyCurrent = { draft, handle -> handle.verifyCurrent(File(requireCurrentDraft(draft), AUDIO)) },
            consume = handoff,
        )
    }

    /** Read and reverify only the requested part; no path or persistent grant leaves this store. */
    fun verifiedMultipartPart(
        expectedAccountKey: String,
        draft: AdminUploadDraft,
        part: AdminMultipartPart,
        checkActive: () -> Unit,
    ): ByteArray = synchronized(lockFor(scope)) {
        if (!isForAccount(expectedAccountKey)) throw IOException("Local draft unavailable")
        checkActive()
        val dir = requireCurrentDraft(draft)
        val audio = File(dir, AUDIO)
        if (!safeFile(audio)) throw IOException("Local draft unavailable")
        if (part.index !in 1..MAX_MULTIPART_PARTS || part.bytes !in 1..MULTIPART_CHUNK_BYTES ||
            !part.sha256.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid private draft part")
        val offset = (part.index - 1L) * MULTIPART_CHUNK_BYTES
        if (offset >= draft.byteCount ||
            part.bytes.toLong() != minOf(draft.byteCount - offset, MULTIPART_CHUNK_BYTES.toLong()))
            throw IOException("Invalid private draft part")
        val result = ByteArray(part.bytes)
        try {
            RandomAccessFile(audio, "r").use {
                if (it.length() != draft.byteCount) throw IOException("Local draft length changed")
                it.seek(offset)
                var position = 0
                while (position < result.size) {
                    checkActive()
                    val n = it.read(result, position, minOf(result.size - position, 64 * 1024))
                    if (n <= 0) throw IOException("Local draft unreadable")
                    position += n
                }
                if (it.length() != draft.byteCount) throw IOException("Local draft length changed")
            }
            if (MessageDigest.getInstance("SHA-256").digest(result).hex() != part.sha256)
                throw IOException("Local draft part hash changed")
            checkActive()
            requireCurrentDraft(draft)
            result
        } catch (e: Exception) {
            result.fill(0)
            throw e
        }
    }

    fun confirmedLongPreparation(draft: AdminUploadDraft): AdminLongPrepareIntent? = synchronized(lockFor(scope)) {
        val file = File(requireCurrentDraft(draft), LONG_INTENT)
        if (!file.exists() && file.canonicalFile == file.absoluteFile) return@synchronized null
        if (!safeFile(file) || file.length() !in 1..16384) throw IOException("Private preparation unreadable")
        DataInputStream(file.inputStream()).use { data ->
            if (data.readInt() != VERSION || data.readUTF() != draft.id ||
                data.readUTF() != draft.sha256 || data.readLong() != draft.byteCount)
                throw IOException("Private preparation mismatch")
            val result = AdminLongPrepareIntent(data.readUTF(), data.readUTF(), data.readUTF(),
                data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF())
            if (data.read() != -1) throw IOException("Invalid private preparation")
            result.validate()
            result
        }
    }

    fun freezeLongPreparation(draft: AdminUploadDraft, intent: AdminLongPrepareIntent) = synchronized(lockFor(scope)) {
        val dir = requireCurrentDraft(draft)
        intent.validate()
        val existing = confirmedLongPreparation(draft)
        if (existing != null) {
            if (existing != intent) throw IOException("Private preparation already confirmed")
            return@synchronized
        }
        val partial = File(dir, LONG_INTENT_PART)
        val final = File(dir, LONG_INTENT)
        if (partial.canonicalFile != partial.absoluteFile || (partial.exists() && !safeFile(partial)))
            throw IOException("Unsafe private preparation record")
        FileOutputStream(partial, false).use { stream ->
            val data = DataOutputStream(stream)
            data.writeInt(VERSION)
            data.writeUTF(draft.id)
            data.writeUTF(draft.sha256)
            data.writeLong(draft.byteCount)
            for (value in intent.fields()) data.writeUTF(value)
            data.flush()
            stream.fd.sync()
        }
        if (final.exists() || !partial.renameTo(final)) throw IOException("Cannot freeze private preparation")
    }

    fun confirmedIntent(draft: AdminUploadDraft): UploadStageIntent? = synchronized(lockFor(scope)) {
        val dir = requireCurrentDraft(draft)
        val stamp = File(dir, INTENT)
        if (!stamp.exists() && stamp.canonicalFile == stamp.absoluteFile) null else readIntent(stamp, draft)
    }

    /** Only call after the operator confirms the exact review. Never replace an existing confirmation. */
    fun freezeConfirmedIntent(draft: AdminUploadDraft, request: UploadStageIntent) = synchronized(lockFor(scope)) {
        request.encode(draft)
        val dir = requireCurrentDraft(draft)
        val stamp = File(dir, INTENT)
        val existing = confirmedIntent(draft)
        if (existing != null) {
            if (existing != request) throw IOException("This draft already has different confirmed instructions")
            return@synchronized
        }
        val partial = File(dir, INTENT_PART)
        if (partial.absoluteFile != partial.canonicalFile || (partial.exists() && !safeFile(partial)))
            throw IOException("Unsafe confirmation path")
        // Interrupted confirmation has not authorized any send; overwrite known partial only after a fresh confirmation.
        FileOutputStream(partial, false).use { stream ->
            val data = DataOutputStream(stream)
            data.writeInt(VERSION)
            data.writeUTF(draft.id)
            data.writeUTF(draft.sha256)
            data.writeLong(draft.byteCount)
            for (field in listOf(request.title, request.speaker, request.description, request.categoryId, request.subcategoryId,
                request.expectedCategoryName, request.expectedSubcategoryName, request.mimeType)) data.writeUTF(field)
            data.flush()
            stream.fd.sync()
        }
        if (stamp.exists() || !partial.renameTo(stamp)) throw IOException("Cannot freeze upload instructions")
    }

    private fun requireCurrentDraft(draft: AdminUploadDraft): File {
        require(ID.matches(draft.id))
        val dir = File(root(), draft.id)
        if (readRecord(dir) != draft) throw IOException("Local draft changed")
        return dir
    }

    private fun readIntent(file: File, draft: AdminUploadDraft): UploadStageIntent {
        if (!safeFile(file) || file.length() !in 1..16384) throw IOException("Unsafe confirmed instructions")
        return DataInputStream(file.inputStream()).use { data ->
            if (data.readInt() != VERSION || data.readUTF() != draft.id ||
                data.readUTF() != draft.sha256 || data.readLong() != draft.byteCount) throw IOException("Confirmation mismatch")
            val result = UploadStageIntent(data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(),
                data.readUTF(), data.readUTF(), data.readUTF())
            if (data.read() != -1) throw IOException("Unknown confirmation data")
            result.encode(draft)
            result
        }
    }

    fun recover() = synchronized(lockFor(scope)) {
        root().listFiles()?.filter { PART.matches(it.name) }?.forEach(::cleanStagingDirectory)
    }

    private fun root(): File {
        if (scope.canonicalFile != scope) throw IOException("Unsafe admin draft path")
        if (!scope.isDirectory && !scope.mkdirs()) throw IOException("Cannot create draft storage")
        if (!safeDirectory(scope)) throw IOException("Unsafe admin draft directory")
        return scope
    }

    private fun readRecord(directory: File): AdminUploadDraft? = try {
        val audio = File(directory, AUDIO)
        val metadata = File(directory, META)
        if (!safeDirectory(directory) || !safeFile(audio) || !safeFile(metadata) || metadata.length() !in 1..4096) {
            null
        } else {
            DataInputStream(metadata.inputStream()).use { input ->
                if (input.readInt() != VERSION) return null
                val name = input.readUTF()
                val length = input.readLong()
                val hash = input.readUTF()
                if (name != safeDraftFileName(name) || length !in 1..maxBytes || length != audio.length() ||
                    !hash.matches(Regex("[0-9a-f]{64}")) || input.read() != -1
                ) null else AdminUploadDraft(directory.name, name, length, hash)
            }
        }
    } catch (_: IOException) {
        null
    }

    private fun cleanStagingDirectory(directory: File) {
        if (!safeDirectory(directory) || !PART.matches(directory.name)) return
        for (name in listOf(AUDIO, META)) {
            val file = File(directory, name)
            if (safeFile(file)) file.delete()
        }
        directory.delete() // An unrecognized file means this remains, rather than deleting recursively.
    }

    private fun safeDirectory(file: File) = file.isDirectory && file.absoluteFile == file.canonicalFile
    private fun safeFile(file: File) = file.isFile && file.absoluteFile == file.canonicalFile

    private companion object {
        const val VERSION = 1
        const val AUDIO = "original"
        const val META = "record"
        const val LONG_INTENT = "confirmed-long-prepare"
        const val LONG_INTENT_PART = ".confirmed-long-prepare.part"
        const val INTENT = "confirmed-upload"
        const val INTENT_PART = ".confirmed-upload.part"
        const val MAX_DRAFTS = 32
        const val MAX_LOCAL_BYTES = 380L * 1024 * 1024
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PART = Regex("\\." + ID.pattern + "\\.part")
        val locks = Array(32) { Any() }
        fun lockFor(path: File) = locks[(path.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]
    }
}

internal const val MAX_ORIGINAL_BYTES = 95L * 1024 * 1024
internal class DraftSizeException : IOException("Audio length outside allowed bounds")
internal class DraftSpaceException : IOException("Account draft capacity reached")
internal fun safeDraftFileName(name: String) = name.filterNot {
    it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it == '/' || it == '\\'
}.trim().take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "تسجيل صوتي"
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
