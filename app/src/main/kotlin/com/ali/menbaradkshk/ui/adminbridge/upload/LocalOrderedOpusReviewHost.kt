package com.ali.menbaradkshk.ui.adminbridge.upload

import android.content.Context
import com.ali.menbaradkshk.media.OrdinaryAudioPause
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Production host for the admin "merge saved recordings" preview. One instance per verified
 * session route: it owns the in-memory [LocalDraftConversionLease], which is current only while
 * this host is open AND [allowed] (the server-verified session) still holds. Closing the route,
 * signing out or a server rejection ends the lease, which every worker/IO step re-checks.
 */
internal class LocalOrderedOpusReviewHost(
    context: Context,
    accountKey: String,
    private val allowed: () -> Boolean,
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("opus-review-timer")),
    private val retirement: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(daemon("opus-review-retire")),
) : Closeable {
    private val app = context.applicationContext
    val lease = LocalDraftConversionLease(accountKey)
    @Volatile private var closed = false
    private val scratch = DormantMergeScratchStore(app.noBackupFilesDir, DormantMergeScratchStore.Layout.OrderedOpus)

    init {
        // Recover any attempt abandoned by a killed process, off the main thread.
        retirement.execute { runCatching { scratch.recoverAbandoned() } }
    }

    fun current(): LocalDraftConversionLease? = if (!closed && allowed()) lease else null

    fun checkLease() {
        if (current() !== lease) throw IOException("Private review lease ended")
    }

    fun backend(store: AdminUploadDraftStore): LocalOrderedOpusReviewBackend {
        checkLease()
        require(store.isForAccount(lease.accountKey))
        val review = OrderedOpusReviewSession(store, lease.accountKey, app.noBackupFilesDir, timer, ::checkLease,
            privateScratch = scratch)
        val port = OrderedOpusMergeBinderPort(app, store, lease, ::current, retirement)
        return StagedOrderedOpusReviewBackend(store, lease, ::current, review, port,
            pauseListener = OrdinaryAudioPause::pauseNow, verifyAccountRoute = ::checkLease)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Let in-flight retirement (FINISH/unbind) drain; the timer only arms expirations.
        timer.shutdown()
        retirement.shutdown()
    }

    private companion object {
        fun daemon(name: String) = java.util.concurrent.ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
