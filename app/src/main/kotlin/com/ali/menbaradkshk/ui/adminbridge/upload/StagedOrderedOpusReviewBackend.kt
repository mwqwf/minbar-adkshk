package com.ali.menbaradkshk.ui.adminbridge.upload

import android.os.ParcelFileDescriptor
import com.ali.menbaradkshk.util.AudioTranscodeMerger
import com.ali.menbaradkshk.util.OggOpusTrimPlan
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Private opt-in screen backend. Caller must supply the same verified local lease and actual
 * nonexported worker port; this does not bind a production service, issue tokens or upload.
 */
internal class StagedOrderedOpusReviewBackend(
    private val store: AdminUploadDraftStore,
    private val lease: LocalDraftConversionLease,
    private val currentLease: () -> LocalDraftConversionLease?,
    private val review: OrderedOpusReviewSession,
    private val port: OrderedOpusReviewSession.Port,
    private val pauseListener: () -> Boolean,
    private val verifyAccountRoute: () -> Unit,
) : LocalOrderedOpusReviewBackend {
    private val preparationGeneration = AtomicLong()
    override fun checkLease() {
        verifyAccountRoute()
        if (currentLease() !== lease) throw IOException("Private review lease ended")
    }

    override fun pauseOrdinaryAudio(): Boolean {
        checkLease()
        return pauseListener()
    }

    override fun prepare(
        orderedOriginals: List<AdminUploadDraft>, checkCaller: () -> Unit,
    ): OrderedOpusReviewSession.StagedReview {
        require(orderedOriginals.size in 2..4)
        checkCaller()
        val attempt = preparationGeneration.incrementAndGet()
        fun checkAttempt() {
            checkCaller()
            checkLease()
            if (preparationGeneration.get() != attempt) throw IOException("Local preparation cancelled")
        }
        checkAttempt()
        // Infer expected time from independently opened and hash-verified original descriptors,
        // not from the worker receipt. No paths, URIs or permanent grants leave the draft store.
        val owned = arrayOfNulls<ParcelFileDescriptor>(orderedOriginals.size)
        val expected = try {
            store.withOrderedLocalMergeInputs(lease, orderedOriginals, currentLease, ::checkAttempt) { borrowed ->
                borrowed.forEachIndexed { index, original ->
                    checkAttempt()
                    owned[index] = ParcelFileDescriptor.dup(original.descriptor.fileDescriptor)
                }
            }
            var total = 0L
            for (index in owned.indices) {
                checkAttempt()
                val copy = checkNotNull(owned[index])
                owned[index] = null
                val plan = ParcelFileDescriptor.AutoCloseInputStream(copy).buffered().use {
                    OggOpusTrimPlan.read(it, ::checkAttempt)
                }
                total = Math.addExact(total, plan.audibleFrames)
                if (total > MAX_STAGE_FRAMES) throw IOException("Private merge exceeds local review limit")
            }
            total
        } finally {
            owned.forEach { remaining -> if (remaining != null) runCatching { remaining.close() } }
        }
        checkAttempt()
        return review.prepareLocalReview(
            orderedOriginals, expected, 2, "دمج التسجيلات", TimeUnit.MINUTES.toNanos(3),
            MAX_ORIGINAL_BYTES, port, checkCaller = ::checkAttempt,
            verifyDecoded = { file -> independentlyDecode(file, expected, ::checkAttempt) },
        )
    }

    private fun independentlyDecode(file: File, expected: Long, checkAttempt: () -> Unit): DerivedOpusLocalDraft.PlaybackEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(16 * 1024)
            while (true) {
                checkAttempt()
                val length = input.read(bytes)
                if (length == -1) break
                digest.update(bytes, 0, length)
            }
        }
        var rendered = 0L
        AudioTranscodeMerger.decodePrivateOpusPreview(file, checkAttempt) { _, length ->
            if (length <= 0 || length % 4 != 0 || length / 4 > expected - rendered)
                throw IOException("Unexpected local decode length")
            rendered += length / 4
        }
        checkAttempt()
        if (rendered != expected) throw IOException("Incomplete local decode")
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return DerivedOpusLocalDraft.PlaybackEvidence(hash, rendered)
    }

    override fun cancel() {
        preparationGeneration.incrementAndGet()
        review.cancel()
    }

    private companion object { const val MAX_STAGE_FRAMES = 30L * 60 * 48_000 }
}
