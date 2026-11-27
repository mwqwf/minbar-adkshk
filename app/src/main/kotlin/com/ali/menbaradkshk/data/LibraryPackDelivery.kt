package com.ali.menbaradkshk.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.ali.menbaradkshk.BuildConfig
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryPackMode {
    AUTOMATIC, PAUSED, DISABLED;

    companion object {
        fun restore(raw: String): LibraryPackMode = entries.firstOrNull { it.name == raw }
            ?: if (raw.isBlank()) AUTOMATIC else DISABLED
    }
}

internal object LibraryPackPolicy {
    fun pending(status: Int): Boolean = status in setOf(
        AssetPackStatus.PENDING, AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING,
        AssetPackStatus.WAITING_FOR_WIFI, AssetPackStatus.REQUIRES_USER_CONFIRMATION,
    )

    fun canFetch(mode: LibraryPackMode, foreground: Boolean, explicit: Boolean): Boolean =
        foreground && (mode == LibraryPackMode.AUTOMATIC || explicit)

    fun canCancelManual(mode: LibraryPackMode, starting: Boolean, statuses: Collection<Int>): Boolean =
        mode != LibraryPackMode.AUTOMATIC && (starting || statuses.any(::pending))

    // The bundled snapshot is a union, not a per-pack index. A partial or missing
    // status response cannot justify suppressing ordinary downloads for the entire archive.
    fun deferSnapshot(mode: LibraryPackMode, statuses: Collection<Int?>): Boolean =
        mode != LibraryPackMode.AUTOMATIC ||
            (statuses.isNotEmpty() && statuses.all { it != null && pending(it) })
}

/** Main-thread probe/fetch bookkeeping; a manual action can promote the still-current probe. */
internal class LibraryPackRequestGate {
    private data class Request(val id: Long, var explicit: Boolean, var fetching: Boolean = false)
    private var nextId = 0L
    private var active: Request? = null

    val startingManual: Boolean get() = active?.explicit == true
    fun request(explicit: Boolean): Long? {
        val running = active
        if (running != null) {
            if (explicit && !running.fetching) running.explicit = true
            return null
        }
        return (++nextId).also { active = Request(it, explicit) }
    }

    fun explicitForProbe(id: Long): Boolean? = active?.takeIf { it.id == id && !it.fetching }?.explicit
    fun isCurrent(id: Long): Boolean = active?.id == id
    fun startFetch(id: Long): Boolean {
        val request = active?.takeIf { it.id == id && !it.fetching } ?: return false
        request.fetching = true
        return true
    }
    fun finish(id: Long) {
        if (isCurrent(id)) active = null
    }
    fun invalidate() { active = null }
}

internal fun canceledOptimisticLibraryFetch(
    current: Map<String, Int>, requested: List<String>,
): Map<String, Int> = current + requested.filter { current[it] == AssetPackStatus.PENDING }
    .associateWith { AssetPackStatus.CANCELED }

/** Our app can gate new on-demand requests. Play owns active cancellation and servicing. */
object LibraryPackDelivery {
    private val packs = BuildConfig.LIBRARY_ASSET_PACKS.split(',').filter { it in setOf("core", "rest", "more") }
    private val _statuses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val statuses = _statuses.asStateFlow()
    private val _startingManual = MutableStateFlow(false)
    val startingManual = _startingManual.asStateFlow()
    private var foregroundOwner: Any? = null
    private var packRemovalInProgress = false
    private val requests = LibraryPackRequestGate()
    private var listener: AssetPackStateUpdateListener? = null
    private val queueWake = PlayQueueWakeGate()
    private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        Log.d("LibraryPackDelivery", "Reconciliation wake unavailable", failure)
    })

    private fun wakeForTerminalPacks(app: Context, terminal: Set<String>) {
        if (terminal.isEmpty()) return
        wakeScope.launch {
            val store = LocalStore.get(app)
            if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC ||
                store.downloadQueuePaused() || store.downloadQueue().isEmpty()) return@launch
            val content = ContentRepository.get(app)
            val membership = readMembership(app, content.bundledShas(app)) ?: return@launch
            val current = withTimeoutOrNull(3_000L) {
                content.state.first { it.lessons.isNotEmpty() || !it.loading }
            } ?: return@launch
            if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC ||
                store.downloadQueuePaused()) return@launch
            val autonomous = store.autoDownloadQueuedIds()
            if (store.downloadQueue().any { id ->
                    id in autonomous && membership[current.lessonById[id]?.sha256] in terminal
                }) {
                DownloadScheduler.enqueuePlayReconciliation(app)
            }
        }
    }

    fun available(context: Context): Boolean = packs.isNotEmpty() && isPlayInstalled(context)

    internal fun isPlayInstalled(context: Context): Boolean = PadLocalTesting.ENABLED || runCatching {
        val app = context.applicationContext
        val pm = app.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(app.packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    // Lifecycle and settings callbacks run on the UI thread. Task listeners also use that thread.
    fun foreground(context: Context, owner: Any) {
        if (!available(context) || foregroundOwner === owner) return
        if (foregroundOwner != null) background(context, checkNotNull(foregroundOwner))
        val app = context.applicationContext
        val manager = runCatching { AssetPackManagerFactory.getInstance(app) }.getOrNull() ?: return
        foregroundOwner = owner
        val updates = AssetPackStateUpdateListener { state ->
            if (foregroundOwner !== owner || state.name() !in packs) return@AssetPackStateUpdateListener
            _statuses.value = _statuses.value + (state.name() to state.status())
            wakeForTerminalPacks(app, queueWake.live(state.name(), state.status()))
            if (state.status() == AssetPackStatus.COMPLETED) {
                ContentRepository.get(app).seedInstalledLibraryPacks()
            }
        }
        listener = updates
        runCatching { manager.registerListener(updates) }
        checkAndFetch(app, explicit = false)
    }

    fun background(context: Context, owner: Any) {
        if (foregroundOwner !== owner) return
        foregroundOwner = null
        requests.invalidate()
        syncManualState()
        listener?.let {
            runCatching { AssetPackManagerFactory.getInstance(context.applicationContext).unregisterListener(it) }
        }
        listener = null
    }

    fun changeMode(context: Context, mode: LibraryPackMode) {
        val app = context.applicationContext
        if (packRemovalInProgress || AssetPackSeeder.hasOutstandingPackRemoval() || !available(app)) return
        val store = LocalStore.get(app)
        if (store.libraryPackMode() == mode) return
        if (!store.setLibraryPackMode(mode)) return
        if (mode == LibraryPackMode.AUTOMATIC) {
            requests.invalidate()
            syncManualState()
            checkAndFetch(app, explicit = false)
        } else {
            cancelOutstandingRequest(app)
        }
    }

    /**
     * Called on the main thread immediately before asking Play to remove packs.
     * A previously approved one-shot probe must be revoked even when auto was already off.
     */
    internal fun stopForPackRemoval(context: Context): Boolean {
        val app = context.applicationContext
        if (packRemovalInProgress || !LocalStore.get(app).beginLibraryPackRemoval()) return false
        packRemovalInProgress = true
        cancelOutstandingRequest(app)
        return true
    }

    internal fun finishPackRemoval() { packRemovalInProgress = false }

    /** Stop an explicit request without turning automatic downloads back on. */
    fun cancelManualRequest(context: Context) {
        val app = context.applicationContext
        if (!available(app) || !LibraryPackPolicy.canCancelManual(
                LocalStore.get(app).libraryPackMode(), _startingManual.value, _statuses.value.values,
            )) return
        cancelOutstandingRequest(app)
    }

    private fun cancelOutstandingRequest(app: Context) {
        requests.invalidate() // A pending probe may not fetch after the user cancels.
        syncManualState()
        // Play may finish a pack concurrently. This action never removes any pack.
        runCatching {
            val updated = AssetPackManagerFactory.getInstance(app).cancel(if (packRemovalInProgress) AssetPackSeeder.PACKS else packs)
            _statuses.value = _statuses.value + updated.packStates().mapValues { it.value.status() }
        }.onFailure { Log.d("LibraryPackDelivery", "Play cancel unavailable", it) }
    }

    private fun syncManualState() {
        _startingManual.value = requests.startingManual
    }

    private fun finishRequest(ticket: Long) {
        requests.finish(ticket)
        syncManualState()
    }

    /** One-shot while disabled remains disabled across this request and future app launches. */
    fun requestOnce(context: Context) {
        val app = context.applicationContext
        if (packRemovalInProgress || AssetPackSeeder.hasOutstandingPackRemoval() || foregroundOwner == null || !available(app)) return
        if (!LocalStore.get(app).allowManualLibraryPackSeeding()) return
        checkAndFetch(app, explicit = true)
    }

    suspend fun deferredAutomaticShas(context: Context, content: ContentRepository): Set<String> {
        if (!available(context)) return emptySet()
        val store = LocalStore.get(context)
        if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC) return content.bundledShas(context)
        // Query Play rather than retaining a pending status after a background completion.
        val observed = query(context)
        if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC) return content.bundledShas(context)
        if (packs.none { observed[it]?.let(LibraryPackPolicy::pending) == true }) return emptySet()
        val catalog = content.bundledShas(context)
        val membership = readMembership(context, catalog)
        return if (membership != null) {
            membership.filterValues { observed[it]?.let(LibraryPackPolicy::pending) == true }.keys
        } else if (LibraryPackPolicy.deferSnapshot(LibraryPackMode.AUTOMATIC, packs.map { observed[it] })) {
            catalog
        } else emptySet()
    }

    private suspend fun query(context: Context): Map<String, Int> = withTimeoutOrNull(2_000L) {
        suspendCancellableCoroutine<Map<String, Int>?> { result ->
            runCatching {
                AssetPackManagerFactory.getInstance(context.applicationContext).getPackStates(packs)
                    .addOnSuccessListener { state ->
                        if (result.isActive) {
                            result.resume(state.packStates().mapValues { it.value.status() })
                        }
                    }.addOnFailureListener {
                        if (result.isActive) result.resume(null)
                    }.addOnCanceledListener {
                        if (result.isActive) result.resume(null)
                    }
            }.onFailure { if (result.isActive) result.resume(null) }
        }
    }.orEmpty()

    private fun readMembership(context: Context, catalog: Set<String>) = runCatching {
        context.applicationContext.assets.open(LibraryPackMembership.PATH).use {
            LibraryPackMembership.read(it, packs, catalog)
        }
    }.getOrNull()

    // Queue-only: no aggregate legacy hold. Look again after a long earlier transfer;
    // coalesce nearby reads to avoid one Play request per archive item.
    internal fun queuedPlayCheck(
        context: Context, content: ContentRepository,
    ): suspend (Lesson) -> Boolean {
        val members by lazy {
            if (available(context)) readMembership(context, content.bundledShas(context)) else null
        }
        var observed = emptyMap<String, Int>()
        var queriedAt: Long? = null
        val attemptedSeeds = mutableSetOf<Pair<String, String>>()
        return check@{ lesson ->
            val store = LocalStore.get(context)
            if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC) return@check false
            val pack = members?.get(lesson.sha256) ?: return@check false
            val now = android.os.SystemClock.elapsedRealtime()
            if (queriedAt?.let { now - it < 1_000L } != true) {
                observed = query(context)
                queriedAt = android.os.SystemClock.elapsedRealtime()
            }
            if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC) return@check false
            val status = observed[pack] ?: return@check false
            if (status == AssetPackStatus.COMPLETED &&
                !store.downloadQueuePaused() && lesson.id in store.downloadQueue() &&
                store.isAutoDownloadQueued(lesson.id) &&
                content.state.value.lessonById[lesson.id]?.sha256 == lesson.sha256 &&
                (lesson.id to lesson.sha256) !in attemptedSeeds) {
                // Seed the eligible rows in one store write, not once per archive lesson.
                val current = content.state.value.lessonById
                val autonomous = store.autoDownloadQueuedIds()
                val expected = store.downloadQueue().mapNotNull { id ->
                    val row = current[id] ?: return@mapNotNull null
                    (id to row.sha256).takeIf {
                        id in autonomous && members?.get(row.sha256) == pack && it !in attemptedSeeds
                    }
                }.toMap()
                attemptedSeeds += expected.toList()
                // Await exact-path seeding before the queue decides to open ordinary transport.
                AssetPackSeeder.seedExpected(context, pack, expected)
            }
            LibraryPackPolicy.pending(status)
        }
    }

    fun suppressQueuedArchive(context: Context, store: LocalStore, content: ContentRepository, sha: String): Boolean =
        store.libraryPackMode() != LibraryPackMode.AUTOMATIC && available(context) &&
            sha.isNotBlank() && sha in content.bundledShas(context)

    private fun checkAndFetch(context: Context, explicit: Boolean) {
        if (packRemovalInProgress || AssetPackSeeder.hasOutstandingPackRemoval() || foregroundOwner == null || !available(context)) return
        val ticket = requests.request(explicit)
        syncManualState()
        if (ticket == null) return
        val store = LocalStore.get(context)
        val manager = runCatching { AssetPackManagerFactory.getInstance(context) }.getOrNull()
        if (manager == null) {
            finishRequest(ticket)
            return
        }
        val probe = queueWake.probe()
        runCatching {
            manager.getPackStates(packs).addOnSuccessListener { current ->
                if (!requests.isCurrent(ticket)) return@addOnSuccessListener
                val observed = queueWake.reconcile(
                    probe, _statuses.value,
                    current.packStates().filterKeys { it in packs }.mapValues { it.value.status() },
                    replace = true,
                )
                _statuses.value = observed.visible
                val terminal = observed.terminal
                if (observed.accepted.values.any { it == AssetPackStatus.COMPLETED }) {
                    ContentRepository.get(context).seedInstalledLibraryPacks()
                }
                val missing = packs.filter { pack ->
                    runCatching { manager.getPackLocation(pack) == null }.getOrDefault(false) &&
                        observed.accepted[pack] in setOf(
                            AssetPackStatus.NOT_INSTALLED, AssetPackStatus.CANCELED, AssetPackStatus.FAILED,
                        )
                }
                val reviewedExplicit = requests.explicitForProbe(ticket) ?: return@addOnSuccessListener
                if (missing.isEmpty() || !LibraryPackPolicy.canFetch(
                        store.libraryPackMode(), foregroundOwner != null, reviewedExplicit,
                    )) {
                    wakeForTerminalPacks(context, terminal)
                    finishRequest(ticket)
                    return@addOnSuccessListener
                }
                wakeForTerminalPacks(context, terminal - missing.toSet())
                if (!requests.startFetch(ticket)) return@addOnSuccessListener
                _statuses.value = _statuses.value + missing.associateWith { AssetPackStatus.PENDING }
                missing.forEach { queueWake.live(it, AssetPackStatus.PENDING) }
                val fetchProbe = queueWake.probe()
                fun applyFetchResult(statuses: Map<String, Int>) {
                    val observed = queueWake.reconcile(
                        fetchProbe, _statuses.value, statuses.filterKeys { it in missing },
                    )
                    _statuses.value = observed.visible
                    wakeForTerminalPacks(context, observed.terminal)
                }
                runCatching {
                    manager.fetch(missing).addOnSuccessListener { result ->
                        if (requests.isCurrent(ticket)) {
                            val updates = result.packStates().mapValues { it.value.status() }
                            applyFetchResult(updates)
                        }
                    }.addOnFailureListener {
                        if (requests.isCurrent(ticket)) {
                            val failed = missing.associateWith { AssetPackStatus.FAILED }
                            applyFetchResult(failed)
                        }
                    }.addOnCanceledListener {
                        if (requests.isCurrent(ticket)) {
                            val canceled = canceledOptimisticLibraryFetch(_statuses.value, missing)
                            applyFetchResult(canceled)
                        }
                    }.addOnCompleteListener { finishRequest(ticket) }
                }.onFailure {
                    if (requests.isCurrent(ticket)) {
                        val failed = missing.associateWith { AssetPackStatus.FAILED }
                        applyFetchResult(failed)
                    }
                    finishRequest(ticket)
                }
            }.addOnFailureListener {
                finishRequest(ticket)
            }.addOnCanceledListener {
                finishRequest(ticket)
            }
        }.onFailure { finishRequest(ticket) }
    }
}
