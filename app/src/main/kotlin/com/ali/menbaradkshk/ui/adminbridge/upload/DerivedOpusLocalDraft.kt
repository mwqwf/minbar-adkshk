package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Deliberately unpublished; call only after an explicitly reviewed job in the verified admin host. */
internal class DerivedOpusLocalDraft(
    private val store: AdminUploadDraftStore,
    private val accountKey: String,
    private val checkActive: () -> Unit,
) {
    /** Expected metadata comes from the client job, never from the candidate output. */
    internal class Job(
        val generation: Long,
        orderedOriginals: List<AdminUploadDraft>,
        val expectedFrames48k: Long,
        val channels: Int,
    ) {
        val orderedOriginals: List<AdminUploadDraft> = java.util.Collections.unmodifiableList(ArrayList(orderedOriginals))
        init {
            require(generation > 0 && this.orderedOriginals.size in 1..16)
            require(this.orderedOriginals.map { it.id }.toSet().size == this.orderedOriginals.size)
            require(expectedFrames48k in 1..48_000L * 8 * 60 * 60 && channels in 1..2)
        }
    }
    internal data class Result(
        val generation: Long, val orderedOriginals: List<AdminUploadDraft>,
        val byteCount: Long, val sha256: String,
    )

    internal data class PlaybackEvidence(val sha256: String, val frames48k: Long)

    // verifier, not worker: evidence from an independently selected playback/decoder route.
    // A failure leaves the originating durable drafts as they were, and publishes nothing.
    fun keepForLocalReview(
        job: Job,
        result: Result,
        title: String,
        verifyPlayback: () -> PlaybackEvidence,
        openResult: () -> InputStream,
    ): AdminUploadDraft = keepForLocalReview(job, result, title, verifyPlayback, { it() }, openResult)

    internal fun keepForLocalReview(
        job: Job,
        result: Result,
        title: String,
        verifyPlayback: () -> PlaybackEvidence,
        install: (() -> AdminUploadDraft) -> AdminUploadDraft,
        openResult: () -> InputStream,
    ): AdminUploadDraft {
        checkActive()
        if (!store.isForAccount(accountKey) || result.generation != job.generation ||
            result.orderedOriginals != job.orderedOriginals || result.byteCount !in 1..MAX_ORIGINAL_BYTES ||
            !result.sha256.matches(Regex("[0-9a-f]{64}"))) {
            throw IOException("Opus receipt does not match the current ordered job")
        }
        // Re-read all referenced originals by SHA, not just current draft metadata.
        job.orderedOriginals.forEach { store.verifyMultipartManifest(accountKey, it, checkActive) }
        // Native verification must run before entering the draft store lock. Bind
        // the inspected byte identity and the exact rendered count to that result.
        val playback = verifyPlayback()
        checkActive()
        if (playback.sha256 != result.sha256 || playback.frames48k != job.expectedFrames48k)
            throw IOException("Playback did not verify the exact derived Opus result")
        val clean = safeDraftFileName(title).removeSuffix(".ogg").take(100)
        return store.prepare("$clean.ogg", result.byteCount, checkActive = checkActive,
            validateAudio = { candidate ->
                if (candidate.length() != result.byteCount) throw IOException("Derived Opus size changed")
                val hash = candidate.inputStream().buffered().use {
                    validateDerivedOpus(it, result.byteCount, job.expectedFrames48k, job.channels, checkActive)
                }
                if (hash != result.sha256) throw IOException("Derived Opus SHA-256 changed")
                job.orderedOriginals.forEach { store.verifyMultipartManifest(accountKey, it, checkActive) }
            }, install = install, openOriginal = openResult)
    }
}

/** Narrow reader for the app's unpublished bounded Ogg writer, not a general Opus decoder. */
internal fun validateDerivedOpus(
    source: InputStream, size: Long, frames: Long, channels: Int, checkActive: () -> Unit,
): String {
    require(size in 1..MAX_ORIGINAL_BYTES && frames in 1..48_000L * 8 * 60 * 60 && channels in 1..2)
    val input = DataInputStream(source)
    val digest = MessageDigest.getInstance("SHA-256")
    val page = ByteArray(27 + 6 + 1276)
    var offset = 0L
    var seq = 0
    var serial = 0
    var skip = 0L
    var previous = 0L
    var complete = false
    fun insist(condition: Boolean) { if (!condition) throw IOException("Invalid bounded Opus result") }
    while (offset < size) {
        checkActive()
        insist(!complete && size - offset >= 27)
        input.readFully(page, 0, 27)
        val h = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int, word: String) = word.indices.all { page[at + it].toInt() == word[it].code }
        insist(tag(0, "OggS") && page[4] == 0.toByte() && h.getInt(18) == seq)
        val flags = page[5].toInt()
        val granule = h.getLong(6)
        val id = h.getInt(14)
        if (seq == 0) serial = id else insist(serial == id)
        val laces = page[26].toInt() and 255
        insist(laces in 1..6 && size - offset >= 27 + laces)
        input.readFully(page, 27, laces)
        val payload = 27 + laces
        insist((27 until payload - 1).all { page[it] == 255.toByte() } && page[payload - 1] != 255.toByte())
        val n = (27 until payload).sumOf { page[it].toInt() and 255 }
        insist(n in 1..1276 && size - offset >= payload + n)
        input.readFully(page, payload, n)
        val extent = payload + n
        var crc = 0
        for (i in 0 until extent) {
            if (i !in 22..25) crc = crc xor ((page[i].toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        insist(h.getInt(22) == crc)
        when (seq) {
            0 -> {
                insist(flags == 2 && granule == 0L && n == 19 && tag(payload, "OpusHead") &&
                    page[payload + 8] == 1.toByte() && page[payload + 9].toInt() == channels &&
                    h.getInt(payload + 12) == 48_000 && h.getShort(payload + 16) == 0.toShort() &&
                    page[payload + 18] == 0.toByte())
                skip = (h.getShort(payload + 10).toInt() and 65535).toLong()
            }
            1 -> insist(flags == 0 && granule == 0L && n == 22 && tag(payload, "OpusTags") &&
                h.getInt(payload + 8) == 6 && tag(payload + 12, "Minbar") && h.getInt(payload + 18) == 0)
            else -> {
                val toc = page[payload].toInt() and 255
                val mode = toc ushr 3
                val ms20 = if (mode < 12) mode % 4 == 1 else if (mode < 16) mode % 2 == 1 else mode % 4 == 3
                insist(n >= 2 && toc and 3 == 0 && ms20)
                if (flags == 4) {
                    insist(granule == frames + skip && granule > previous && granule <= previous + 960 &&
                        offset + extent == size)
                    complete = true
                } else {
                    insist(flags == 0 && granule == previous + 960 && granule < frames + skip)
                }
                previous = granule
            }
        }
        digest.update(page, 0, extent)
        offset += extent
        seq++
    }
    insist(complete && input.read() == -1)
    checkActive()
    return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
