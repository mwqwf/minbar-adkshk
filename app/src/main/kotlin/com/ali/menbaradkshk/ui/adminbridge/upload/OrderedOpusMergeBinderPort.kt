package com.ali.menbaradkshk.ui.adminbridge.upload

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verified-admin client for the private :opusmerge worker. The account's verified *borrowed*
 * descriptors cross Binder synchronously; this object never stores, duplicates, or reopens a
 * draft by path. Binding happens only in [OrderedOpusReviewSession.Endpoint.open].
 */
internal class OrderedOpusMergeBinderPort(
    context: Context,
    private val store: AdminUploadDraftStore,
    private val lease: LocalDraftConversionLease,
    private val currentLease: () -> LocalDraftConversionLease?,
    private val retirement: Executor,
    private val onWorkerStarted: (Int) -> Unit = {},
) : OrderedOpusReviewSession.Port {
    private val app = context.applicationContext
    private val wire = OrderedOpusMergeWire
    init { require(app.packageName.startsWith("com.ali.menbaradkshk")) }

    override fun prepare(request: DerivedOpusLocalDraft.Job, onExactBinderDeath: () -> Unit):
        OrderedOpusReviewSession.Endpoint = Endpoint(request, onExactBinderDeath)

    private inner class Endpoint(
        private val job: DerivedOpusLocalDraft.Job, private val onExactBinderDeath: () -> Unit,
    ) : OrderedOpusReviewSession.Endpoint, ServiceConnection {
        private val component = ComponentName(app.packageName, wire.SERVICE)
        private val bound = AtomicBoolean()
        private val initialBindSettled = CountDownLatch(1)
        private val retired = AtomicBoolean()
        private val terminal = AtomicBoolean()
        private val connected = CountDownLatch(1)
        private val serialSend = Any()
        // 0 = not requested, 1 = binding initiated, 2 = disarmed before any bind.
        private val phase = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var binder: IBinder? = null
        private val death = IBinder.DeathRecipient { finishTerminal() }
        private val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (!retired.get() && msg.what == wire.STARTED &&
                msg.sendingUid == Process.myUid() && msg.data.getLong(wire.GENERATION) == job.generation) {
                val pid = msg.data.getInt(wire.WORKER_PID)
                if (pid > 0 && pid != Process.myPid()) onWorkerStarted(pid)
            }
            true
        })

        private fun checkLive() {
            if (retired.get() || terminal.get() || currentLease() !== lease)
                throw IOException("Ordered local worker no longer current")
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (name != component || binder != null || terminal.get()) return
            // Store first so a concurrent death or cancellation sees the same exact object.
            binder = service
            try {
                service.linkToDeath(death, 0)
            } catch (_: RemoteException) {
                // linkToDeath itself reports that this exact sender is already dead.
                finishTerminal()
            } finally {
                connected.countDown()
            }
            if (retired.get() && !terminal.get()) scheduleRetire()
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
        override fun onNullBinding(name: ComponentName) {
            if (name == component && binder == null) finishTerminal()
        }

        private fun finishTerminal() {
            if (!terminal.compareAndSet(false, true)) return
            connected.countDown()
            retirement.execute {
                initialBindSettled.await()
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound.compareAndSet(true, false)) runCatching { app.unbindService(this) }
                // Do not admit a new generation while this connection can auto-rebind.
                onExactBinderDeath()
            }
        }

        override fun open(): InputStream {
            check(Looper.myLooper() != Looper.getMainLooper())
            checkLive()
            if (!phase.compareAndSet(0, 1)) throw IOException("Ordered worker never bound")
            try {
                val ok = app.bindService(Intent().setComponent(component), this, Context.BIND_AUTO_CREATE)
                bound.set(ok)
                if (!ok) {
                    finishTerminal()
                    throw IOException("Ordered worker unavailable")
                }
            } finally {
                initialBindSettled.countDown()
            }
            for (unused in 0 until 200) {
                checkLive()
                if (connected.await(50, MILLISECONDS)) break
            }
            checkLive()
            if (connected.count != 0L) throw IOException("Ordered worker bind timed out")
            val worker = binder ?: throw IOException("Ordered worker never connected")
            val pipe = ParcelFileDescriptor.createPipe()
            var passed = false
            try {
                val reader = InterruptiblePipe(pipe[0], ::checkLive)
                store.withOrderedLocalMergeInputs(lease, job.orderedOriginals, currentLease, ::checkLive) { borrows ->
                    synchronized(serialSend) {
                        checkLive()
                        Messenger(worker).send(Message.obtain(null, wire.START).apply {
                            replyTo = reply
                            data.putLong(wire.GENERATION, job.generation)
                            data.putParcelable(wire.OUTPUT_PIPE, pipe[1])
                            data.putParcelableArrayList(wire.FILES, ArrayList(borrows.map { it.descriptor }))
                            data.putLongArray(wire.LENGTHS, borrows.map { it.byteCount }.toLongArray())
                            data.putStringArrayList(wire.DIGESTS, ArrayList(borrows.map { it.sha256 }))
                        })
                    }
                }
                pipe[1].close()
                checkLive()
                passed = true
                return reader
            } finally {
                if (!passed) {
                    runCatching { pipe[1].close() }
                    runCatching { pipe[0].close() }
                }
            }
        }

        override fun requestRetire() {
            if (!retired.compareAndSet(false, true)) return
            if (phase.compareAndSet(0, 2)) {
                // No bind has started, so exactly no worker exists to await or retire.
                initialBindSettled.countDown()
                finishTerminal()
            } else scheduleRetire()
        }

        private fun scheduleRetire() = retirement.execute {
            synchronized(serialSend) {
                val worker = binder ?: return@synchronized
                if (!terminal.get()) runCatching {
                    Messenger(worker).send(Message.obtain(null, wire.FINISH).apply {
                        replyTo = reply
                        data.putLong(wire.GENERATION, job.generation)
                    })
                }
                // FINISH is only a request; a live exact linkToDeath remains mandatory.
            }
        }
    }

    /** Only the I/O lane owns and closes this read descriptor. Cancellation is observed
     * at most one poll interval later without a cross-thread close/fd reuse race. */
    private class InterruptiblePipe(
        private val pipe: ParcelFileDescriptor, private val checkLive: () -> Unit,
    ) : InputStream() {
        private val fd = pipe.fileDescriptor
        init {
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            val descriptor = StructPollfd().apply {
                fd = this@InterruptiblePipe.fd
                events = OsConstants.POLLIN.toShort()
            }
            while (true) {
                checkLive()
                try {
                    val read = Os.read(fd, bytes, offset, length)
                    checkLive()
                    return if (read == 0) -1 else read
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) continue
                    if (error.errno != OsConstants.EAGAIN) throw IOException("Ordered worker pipe failed", error)
                }
                try {
                    Os.poll(arrayOf(descriptor), 50)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EINTR) throw IOException("Ordered worker poll failed", error)
                }
            }
        }
        override fun close() = pipe.close()
    }
}
