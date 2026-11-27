package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A future verified session host must keep this same reference until lock, revocation, or sign-out. */
internal class AdminLongUploadLease(val accountKey: String) {
    init { require(accountKey.matches(Regex("[a-f0-9]{64}"))) }
}

internal data class AdminLongUploadState(
    val protocolVersion: Int,
    val draftId: String,
    val totalBytes: Long,
    val wholeSha256: String,
    val verified: List<Int>,
    val missing: List<Int>,
    val completed: Boolean,
)

/** No installed transport. Future implementation must call guard immediately before opening the request,
 * respect coroutine cancellation, and consume the part bytes before returning without retaining them. */
internal interface AdminLongUploadWire {
    suspend fun begin(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState
    suspend fun put(draftId: String, manifest: AdminMultipartManifest, part: AdminMultipartPart, bytes: ByteArray, guard: () -> Unit): AdminLongUploadState
    suspend fun complete(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState
}

internal data class AdminLongUploadReceipt(val draftId: String, val totalBytes: Long, val wholeSha256: String)
internal data class AdminLongAssemblyReceipt(val draftId: String, val totalBytes: Long, val wholeSha256: String)

internal fun interface AdminLongAssemblyWire {
    suspend fun assemble(receipt: AdminLongUploadReceipt, guard: () -> Unit): AdminLongAssemblyReceipt
}

internal class AdminLongUploadOrchestration(
    private val store: AdminUploadDraftStore,
    // Null unless account, role, and the separate long-upload capability are still live.
    private val currentLease: () -> AdminLongUploadLease?,
    private val wire: AdminLongUploadWire,
    private val mutex: Mutex = Mutex(),
    private val assemblyWire: AdminLongAssemblyWire? = null,
    private val currentAssemblyLease: () -> AdminLongUploadLease? = { null },
    private val prepareWire: AdminLongPrepareWire? = null,
    private val currentPrepareLease: () -> AdminLongUploadLease? = { null },
) {
    private val engine = Any()
    private data class CheckedParts(val review: Review, val receipt: AdminLongUploadReceipt, val lease: AdminLongUploadLease)
    @Volatile private var latestCheckedParts: CheckedParts? = null
    private data class CheckedAssembly(val parts: CheckedParts, val receipt: AdminLongAssemblyReceipt)
    @Volatile private var latestAssembly: CheckedAssembly? = null

    val canPrepare: Boolean get() = prepareWire != null && canAssemble && currentPrepareLease()?.let {
        it === currentLease()
    } == true

    val canAssemble: Boolean get() = assemblyWire != null && currentAssemblyLease()?.let {
        it === currentLease()
    } == true

    internal class AssemblyConfirmation internal constructor(
        internal val receipt: AdminLongUploadReceipt,
        private val engine: Any,
        private val lease: AdminLongUploadLease,
    ) {
        private val unused = AtomicBoolean(true)
        internal fun take(engine: Any, lease: AdminLongUploadLease) =
            this.engine === engine && this.lease === lease && unused.compareAndSet(true, false)
    }

    internal class Review internal constructor(
        val draft: AdminUploadDraft,
        val manifest: AdminMultipartManifest,
        private val engine: Any,
        private val lease: AdminLongUploadLease,
    ) {
        internal fun belongsTo(engine: Any, lease: AdminLongUploadLease): Boolean =
            this.engine === engine && this.lease === lease
    }

    internal class Confirmed internal constructor(val review: Review, private val engine: Any) {
        private val unused = AtomicBoolean(true)
        internal fun take(engine: Any) = this.engine === engine && unused.compareAndSet(true, false)
    }

    suspend fun review(draft: AdminUploadDraft): Review = withContext(Dispatchers.IO) {
        val lease = currentLease() ?: throw IOException("Private long upload unavailable")
        // Keep the existing path/lease for every file it already supports.
        if (draft.byteCount <= MAX_STAGE_BYTES.toLong()) throw DraftSizeException()
        val context = currentCoroutineContext()
        val guard = { context.ensureActive(); verifyLease(lease) }
        guard()
        val manifest = store.verifyMultipartManifest(lease.accountKey, draft, guard)
        guard()
        Review(draft, manifest, engine, lease)
    }

    /** Only a deliberate operator action after displaying the exact reviewed draft may call this. */
    fun confirmAfterReview(review: Review): Confirmed {
        val lease = currentLease() ?: throw IOException("Private long upload unavailable")
        if (!review.belongsTo(engine, lease)) throw IOException("Private long upload review expired")
        verifyLease(lease)
        return Confirmed(review, engine)
    }

    /** One explicit attempt. A new operator confirmation is required after an ambiguous failure. */
    suspend fun send(confirmed: Confirmed): AdminLongUploadReceipt = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw IOException("A private long upload is already running")
        try {
            val lease = currentLease() ?: throw IOException("Private long upload unavailable")
            val review = confirmed.review
            val draft = review.draft
            val manifest = review.manifest
            if (!review.belongsTo(engine, lease) || !confirmed.take(engine))
                throw IOException("Private long upload confirmation expired")
            val context = currentCoroutineContext()
            val guard = { context.ensureActive(); verifyLease(lease) }
            guard()
            // Re-read the full private draft before even beginning a reconciliation request.
            if (store.verifyMultipartManifest(lease.accountKey, draft, guard) != manifest)
                throw IOException("Reviewed draft content changed")
            guard()
            var state = validateState(wire.begin(draft.id, manifest, guard), draft, manifest)
            guard()
            while (state.missing.isNotEmpty()) {
                val part = manifest.parts[state.missing.first() - 1]
                val bytes = store.verifiedMultipartPart(lease.accountKey, draft, part, guard)
                try {
                    guard()
                    val updated = validateState(wire.put(draft.id, manifest, part, bytes, guard), draft, manifest)
                    guard()
                    if (part.index !in updated.verified || !updated.verified.containsAll(state.verified))
                        throw IOException("Private long upload progress regressed")
                    state = updated
                } finally {
                    bytes.fill(0)
                }
            }
            if (!state.completed) {
                guard()
                state = validateState(wire.complete(draft.id, manifest, guard), draft, manifest)
                guard()
                if (!state.completed) throw IOException("Private long upload completion unverified")
            }
            guard()
            val receipt = AdminLongUploadReceipt(draft.id, manifest.totalBytes, manifest.wholeSha256)
            latestCheckedParts = CheckedParts(review, receipt, lease)
            receipt
        } finally {
            mutex.unlock()
        }
    }

    /** Separate UI confirmation; no assembly is triggered by upload completion. */
    fun confirmAssembly(receipt: AdminLongUploadReceipt): AssemblyConfirmation {
        val checked = latestCheckedParts ?: throw IOException("Private parts not verified")
        val lease = currentAssemblyLease() ?: throw IOException("Private assembly unavailable")
        if (assemblyWire == null || checked.receipt != receipt || checked.lease !== lease)
            throw IOException("Private assembly receipt expired")
        verifyLease(lease)
        return AssemblyConfirmation(receipt, engine, lease)
    }

    suspend fun assemble(confirmed: AssemblyConfirmation): AdminLongAssemblyReceipt = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw IOException("A private upload is already running")
        try {
            val wire = assemblyWire ?: throw IOException("Private assembly unavailable")
            val lease = currentAssemblyLease() ?: throw IOException("Private assembly unavailable")
            val checked = latestCheckedParts ?: throw IOException("Private parts not verified")
            if (checked.receipt != confirmed.receipt || checked.lease !== lease || !confirmed.take(engine, lease))
                throw IOException("Private assembly receipt expired")
            val context = currentCoroutineContext()
            val guard = {
                context.ensureActive()
                verifyLease(lease)
                if (currentAssemblyLease() !== lease) throw IOException("Private assembly lease changed")
            }
            guard()
            if (store.verifyMultipartManifest(lease.accountKey, checked.review.draft, guard) != checked.review.manifest)
                throw IOException("Local draft changed")
            guard()
            val result = wire.assemble(checked.receipt, guard)
            guard()
            if (result.draftId != checked.receipt.draftId || result.totalBytes != checked.receipt.totalBytes ||
                result.wholeSha256 != checked.receipt.wholeSha256)
                throw IOException("Private assembly proof mismatch")
            latestAssembly = CheckedAssembly(checked, result)
            result
        } finally {
            mutex.unlock()
        }
    }

    internal class PrepareConfirmation internal constructor(
        val assembly: AdminLongAssemblyReceipt,
        val intent: AdminLongPrepareIntent,
        private val engine: Any,
        private val lease: AdminLongUploadLease,
    ) {
        private val unused = AtomicBoolean(true)
        internal fun take(engine: Any, lease: AdminLongUploadLease) =
            this.engine === engine && this.lease === lease && unused.compareAndSet(true, false)
    }

    private fun checkedPreparation(assembly: AdminLongAssemblyReceipt): CheckedAssembly {
        val checked = latestAssembly ?: throw IOException("Private assembly unverified")
        val lease = currentPrepareLease() ?: throw IOException("Private preparation unavailable")
        if (!canPrepare || checked.receipt != assembly || checked.parts.lease !== lease || latestCheckedParts !== checked.parts)
            throw IOException("Private preparation receipt expired")
        verifyLease(lease)
        return checked
    }

    suspend fun savedPreparation(assembly: AdminLongAssemblyReceipt): AdminLongPrepareIntent? = withContext(Dispatchers.IO) {
        val checked = checkedPreparation(assembly)
        store.confirmedLongPreparation(checked.parts.review.draft).also { checkedPreparation(assembly) }
    }

    /** Execute only after the operator reviews the active destination and confirms preparation separately. */
    fun confirmPreparation(assembly: AdminLongAssemblyReceipt, intent: AdminLongPrepareIntent): PrepareConfirmation {
        val checked = checkedPreparation(assembly)
        intent.validate()
        return PrepareConfirmation(checked.receipt, intent, engine, checked.parts.lease)
    }

    suspend fun prepare(confirmed: PrepareConfirmation): AdminLongPreparationReceipt = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw IOException("A private upload is already running")
        try {
            val checked = checkedPreparation(confirmed.assembly)
            val wire = prepareWire ?: throw IOException("Private preparation unavailable")
            if (!confirmed.take(engine, checked.parts.lease)) throw IOException("Private preparation confirmation expired")
            val context = currentCoroutineContext()
            val guard = {
                context.ensureActive()
                if (checkedPreparation(checked.receipt) !== checked) throw IOException("Private preparation review changed")
            }
            guard()
            val draft = checked.parts.review.draft
            if (store.verifyMultipartManifest(checked.parts.lease.accountKey, draft, guard) != checked.parts.review.manifest)
                throw IOException("Local draft changed")
            confirmed.intent.encode(checked.receipt)
            guard()
            store.freezeLongPreparation(draft, confirmed.intent)
            guard()
            val result = wire.prepare(checked.receipt, confirmed.intent, guard)
            guard()
            if (result.draftId != checked.receipt.draftId || result.totalBytes != checked.receipt.totalBytes ||
                result.wholeSha256 != checked.receipt.wholeSha256) throw IOException("Private preparation proof mismatch")
            result
        } finally {
            mutex.unlock()
        }
    }

    private fun verifyLease(expected: AdminLongUploadLease) {
        if (currentLease() !== expected || !store.isForAccount(expected.accountKey))
            throw IOException("Private long upload session changed")
    }
}

private fun validateState(
    state: AdminLongUploadState,
    draft: AdminUploadDraft,
    manifest: AdminMultipartManifest,
): AdminLongUploadState {
    val n = manifest.parts.size
    if (state.protocolVersion != 1 || state.draftId != draft.id ||
        state.totalBytes != manifest.totalBytes || state.wholeSha256 != manifest.wholeSha256 ||
        state.verified.size > n || state.missing.size > n ||
        state.verified.size + state.missing.size != n
    ) throw IOException("Private long upload state mismatch")
    val known = state.verified.toList()
    val missing = state.missing.toList()
    val all = (1..n).toList()
    if (known != known.sorted() || missing != missing.sorted() || (known + missing).sorted() != all ||
        (state.completed && missing.isNotEmpty())
    ) throw IOException("Private long upload progress mismatch")
    return state.copy(verified = known, missing = missing)
}
