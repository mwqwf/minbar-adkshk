package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inactive app-side, single-generation worker-to-private-review caller. No Activity, Binder
 * implementation or upload permission. Construct for one verified account and one client host.
 */
internal class OrderedOpusReviewSession(
    private val store: AdminUploadDraftStore,
    private val accountKey: String,
    private val scratchDirectory: File,
    private val timer: ScheduledExecutorService,
    private val checkLease: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val privateScratch: DormantMergeScratchStore? = null,
) {
    private val worker = OpusWorkerExperiment(nanoTime)
    private val state = Any()
    private var running = false
    private var current: Active? = null
    private var pending: Candidate? = null

    /** Factory must not perform Android bind/start until [Endpoint.open] is called. */
    internal fun interface Port {
        fun prepare(request: DerivedOpusLocalDraft.Job, onExactBinderDeath: () -> Unit): Endpoint
    }
    internal interface Endpoint {
        fun open(): InputStream
        // Signal only; adapter closes this job's read descriptor and requests verified worker death
        // from an independent lane. No waiting for Binder, codec, a remote ack or caller lock.
        fun requestRetire()
    }

    private inner class Active(
        val token: OpusWorkerExperiment.Job, val start: Long, val budget: Long,
        private val checkCaller: () -> Unit,
    ) {
        @Volatile var endpoint: Endpoint? = null
        @Volatile var abandoned = false
        private val retireSent = AtomicBoolean(false)

        fun retire() {
            val value = endpoint ?: return
            if (retireSent.compareAndSet(false, true)) value.requestRetire()
        }

        fun checkTimeAndLease() {
            if (abandoned || nanoTime() - start >= budget) throw IOException("Opus review session expired")
            checkLease()
            checkCaller()
        }

        fun checkTransport() {
            checkTimeAndLease()
            if (!worker.live(token)) throw IOException("Opus worker endpoint lost")
        }
    }

    /** A verified local candidate, separate from the worker and from any durable draft. */
    internal interface StagedReview : Closeable {
        val generation: Long
        val sha256: String
        val frames48k: Long
        val channels: Int
        fun <T> withCandidate(block: (File, checkCurrent: () -> Unit) -> T): T
        fun saveForReview(): AdminUploadDraft
    }

    private inner class Candidate(
        private val part: File,
        private val job: DerivedOpusLocalDraft.Job,
        private val receipt: DerivedOpusLocalDraft.Result,
        private val proof: DerivedOpusLocalDraft.PlaybackEvidence,
        private val title: String,
        private val reviewStarted: Long,
        private val reviewBudgetNs: Long,
        private val releaseScratch: () -> Unit,
    ) : StagedReview {
        override val generation: Long get() = job.generation
        override val sha256: String get() = receipt.sha256
        override val frames48k: Long get() = job.expectedFrames48k
        override val channels: Int get() = job.channels
        private val admission = Any()
        private val saving = AtomicBoolean()
        @Volatile private var closed = false
        private var expiration: ScheduledFuture<*>? = null

        private fun checkCurrent() {
            if (closed || nanoTime() - reviewStarted >= reviewBudgetNs)
                throw IOException("Opus candidate is no longer current")
            checkLease()
        }

        internal fun armExpiration() {
            val alarm = timer.schedule(::close,
                (reviewBudgetNs - (nanoTime() - reviewStarted)).coerceAtLeast(0), NANOSECONDS)
            synchronized(admission) {
                if (closed) alarm.cancel(false) else expiration = alarm
            }
        }

        /** Only a private explicit Preview action may use this file. Never retain or share the path. */
        override fun <T> withCandidate(block: (File, checkCurrent: () -> Unit) -> T): T {
            checkCurrent()
            val result = block(part, ::checkCurrent)
            checkCurrent()
            return result
        }

        /** User confirmation means keep locally. It does not stage, grant, dispatch, or upload. */
        override fun saveForReview(): AdminUploadDraft {
            if (!saving.compareAndSet(false, true)) throw IOException("Opus candidate already used")
            try {
                checkCurrent()
                return DerivedOpusLocalDraft(store, accountKey, ::checkCurrent).keepForLocalReview(
                    job, receipt, title, { proof },
                    install = { writeFinal ->
                        synchronized(admission) {
                            checkCurrent()
                            writeFinal()
                        }
                    },
                    openResult = { part.inputStream().buffered() },
                )
            } finally {
                close()
            }
        }

        override fun close() {
            synchronized(admission) {
                if (closed) return
                closed = true
                expiration?.cancel(false)
                expiration = null
            }
            try {
                releaseScratch()
            } finally {
                synchronized(state) { if (pending === this) pending = null }
            }
        }
    }

    /** Main account revocation/cancel signal, independent of the blocking copy or verifier. */
    fun cancel() {
        val (job, candidate) = synchronized(state) {
            current?.abandoned = true
            current to pending
        }
        if (job != null) {
            worker.cancel(job.token)
            job.retire()
        }
        candidate?.close()
    }

    /** Legacy test/local caller that explicitly combines candidate qualification and saving. */
    fun buildLocalReview(
        orderedOriginals: List<AdminUploadDraft>,
        expectedFrames48k: Long,
        channels: Int,
        title: String,
        budgetNs: Long,
        byteLimit: Long,
        port: Port,
        playback: (File) -> DerivedOpusLocalDraft.PlaybackEvidence,
    ): AdminUploadDraft = prepareLocalReview(orderedOriginals, expectedFrames48k, channels, title,
        budgetNs, byteLimit, port, verifyDecoded = playback).use { it.saveForReview() }

    /**
     * Structurally validate and independently decode before allowing private review.
     * Do not auto-play: [verifyDecoded] is a silent independent qualification step.
     */
    fun prepareLocalReview(
        orderedOriginals: List<AdminUploadDraft>,
        expectedFrames48k: Long,
        channels: Int,
        title: String,
        budgetNs: Long,
        byteLimit: Long,
        port: Port,
        reviewBudgetNs: Long = 15L * 60 * 1_000_000_000,
        checkCaller: () -> Unit = {},
        verifyDecoded: (File) -> DerivedOpusLocalDraft.PlaybackEvidence,
    ): StagedReview {
        checkLease()
        require(store.isForAccount(accountKey))
        require(byteLimit in 1..MAX_ORIGINAL_BYTES)
        require(reviewBudgetNs in 1..30L * 60 * 1_000_000_000)
        val active = synchronized(state) {
            checkCaller()
            check(!running && pending == null) { "An Opus attempt is already running or awaiting review" }
            val start = nanoTime()
            val token = worker.begin(budgetNs) ?: throw IOException("Previous Opus endpoint has not confirmed death")
            Active(token, start, budgetNs, checkCaller).also { current = it; running = true }
        }
        val token = active.token
        var armed: ScheduledFuture<*>? = null
        var releaseScratch: (() -> Unit)? = null
        try {
            val request = DerivedOpusLocalDraft.Job(token.generation, orderedOriginals, expectedFrames48k, channels)
            armed = timer.schedule({
                active.abandoned = true
                worker.watchdog(token)
                active.retire()
            }, (budgetNs - (nanoTime() - active.start)).coerceAtLeast(0), NANOSECONDS)
            request.orderedOriginals.forEach { store.verifyMultipartManifest(accountKey, it, active::checkTransport) }
            active.checkTransport()
            val scratch = privateScratch?.openAttempt()
            if (scratch != null) releaseScratch = scratch::close
            val directory = scratch?.directory ?: scratchDirectory.absoluteFile
            if (directory.canonicalFile != directory || !directory.isDirectory) throw IOException("Unsafe Opus scratch")
            val part = if (scratch == null) File.createTempFile("opus-", ".part", directory)
                else File(directory, DormantMergeScratchStore.OPUS_PART).also {
                    if (!it.createNewFile()) throw IOException("Private Opus result already exists")
                }
            if (scratch == null) releaseScratch = { part.delete(); Unit }
            val endpoint = port.prepare(request) { worker.confirmEndpointDeath(token) }
            active.endpoint = endpoint
            active.checkTransport()
            val size = endpoint.open().use { source ->
                part.outputStream().buffered().use { out ->
                    OpusWorkerResultPipe.stage(source, token.generation, byteLimit, out, active::checkTransport)
                }
            }
            val hash = part.inputStream().buffered().use {
                validateDerivedOpus(it, size, expectedFrames48k, channels, active::checkTransport)
            }
            val proof = verifyDecoded(part)
            active.checkTransport()
            if (proof.sha256 != hash || proof.frames48k != expectedFrames48k)
                throw IOException("Target player did not verify this Opus result")
            // Only the client authorizes completion after both validations. At that point a
            // later exact endpoint death is expected; it cannot create a second local draft.
            if (!worker.complete(token, true)) throw IOException("Opus generation expired before review")
            active.retire()
            val candidate = Candidate(part, request,
                DerivedOpusLocalDraft.Result(token.generation, request.orderedOriginals, size, hash),
                proof, title, nanoTime(), reviewBudgetNs, checkNotNull(releaseScratch))
            synchronized(state) {
                active.checkTimeAndLease()
                pending = candidate
            }
            try {
                candidate.armExpiration()
                releaseScratch = null // The candidate owns the attempt through preview and explicit save.
                return candidate
            } catch (t: Throwable) {
                candidate.close()
                throw t
            }
        } finally {
            armed?.cancel(false)
            try {
                worker.cancel(token)
                if (active.endpoint == null) worker.confirmEndpointDeath(token) else active.retire()
            } finally {
                try {
                    releaseScratch?.invoke()
                } finally {
                    synchronized(state) {
                        current = null
                        running = false
                    }
                }
            }
        }
    }
}
