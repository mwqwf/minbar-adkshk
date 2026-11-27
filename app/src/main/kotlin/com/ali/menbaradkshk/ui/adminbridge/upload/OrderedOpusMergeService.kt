package com.ali.menbaradkshk.ui.adminbridge.upload

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Private, non-exported ordered Opus merge worker. Declared with android:exported="false" and
 * android:process=":opusmerge"; it refuses to bind in any other process, accepts messages only
 * from this app's UID, takes 2–4 borrowed read-only descriptors, and always ends its own process
 * (after FINISH, on any failure, or at a fixed lifetime). The same-UID process is a failure
 * boundary for codec hangs/crashes, not a privilege boundary. No network, URI or upload here.
 */
class OrderedOpusMergeService : Service() {
    private val wire = OrderedOpusMergeWire
    private val terminator = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "opusmerge-terminator") }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "opusmerge-codec") }
    private val mailbox = Messenger(Handler(Looper.getMainLooper(), ::handle))
    private var permittedProcess = false
    private var job = 0L // main looper only
    private var client: IBinder? = null
    @Volatile private var idleKill: java.util.concurrent.ScheduledFuture<*>? = null

    override fun onCreate() {
        super.onCreate()
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else legacySelfName()
        permittedProcess = wire.permittedProcess(packageName, name)
        if (permittedProcess) {
            terminator.schedule(::endWorkerProcess, wire.PROCESS_LIFETIME_MS, MILLISECONDS)
            idleKill = terminator.schedule(::endWorkerProcess, wire.IDLE_BIND_MS, MILLISECONDS)
        } else Log.w(TAG, "refused: wrong process")
    }

    override fun onBind(intent: Intent?): IBinder? = if (permittedProcess) mailbox.binder else null

    override fun onUnbind(intent: Intent?): Boolean {
        // The client unbinding is a retirement signal as well; never linger with codec state.
        if (permittedProcess) terminator.execute(::endWorkerProcess)
        return false
    }

    private fun handle(message: Message): Boolean {
        if (!permittedProcess || message.sendingUid != Process.myUid()) return true
        val generation = message.data.getLong(wire.GENERATION)
        val reply = message.replyTo
        when (message.what) {
            wire.START -> {
                @Suppress("DEPRECATION")
                val output = message.data.getParcelable<ParcelFileDescriptor>(wire.OUTPUT_PIPE) ?: return true
                @Suppress("DEPRECATION")
                val selected = message.data.getParcelableArrayList<ParcelFileDescriptor>(wire.FILES) ?: arrayListOf()
                val lengths = message.data.getLongArray(wire.LENGTHS)
                val digests = message.data.getStringArrayList(wire.DIGESTS)
                if (job != 0L || generation <= 0L || reply == null || !wire.validStart(selected.size, lengths, digests)) {
                    output.close()
                    selected.forEach { runCatching { it.close() } }
                    terminator.execute(::endWorkerProcess)
                    return true
                }
                job = generation
                client = reply.binder
                idleKill?.cancel(false)
                worker.execute { run(generation, reply, output, selected, lengths!!, digests!!) }
            }
            wire.FINISH -> if (generation > 0 && reply != null && (job == 0L || generation == job && client == reply.binder)) {
                terminator.execute(::endWorkerProcess)
            }
        }
        return true
    }

    private fun run(
        generation: Long, reply: Messenger, output: ParcelFileDescriptor,
        selected: List<ParcelFileDescriptor>, lengths: LongArray, digests: List<String>,
    ) {
        val scratch = Files.createTempDirectory(cacheDir.toPath(), "ordered-opus-").toFile()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { sink ->
                reply.send(Message.obtain(null, wire.STARTED).apply {
                    data.putLong(wire.GENERATION, generation)
                    data.putInt(wire.WORKER_PID, Process.myPid())
                })
                val budget = OpusCodecBudget(wire.CODEC_BUDGET_MS)
                val clips = try {
                    selected.mapIndexed { index, descriptor ->
                        File(scratch, "clip-$index").also {
                            OrderedOpusMergeJoin.copyVerified(descriptor, lengths[index], digests[index], it, budget::check)
                        }
                    }
                } finally {
                    selected.forEach { runCatching { it.close() } }
                }
                val merged = File(scratch, "merged.ogg")
                OrderedOpusMergeJoin.encode(clips, merged, MAX_ORIGINAL_BYTES, budget, SecureRandom().nextInt())
                clips.forEach { it.delete() }
                val digest = MessageDigest.getInstance("SHA-256")
                merged.inputStream().buffered().use { input ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                }
                DataOutputStream(sink.buffered()).apply {
                    writeInt(OpusWorkerResultPipe.MAGIC)
                    writeLong(generation)
                    writeLong(merged.length())
                    write(digest.digest())
                    merged.inputStream().buffered().use { it.copyTo(this, 64 * 1024) }
                    flush()
                }
            }
        } catch (failure: Throwable) {
            selected.forEach { runCatching { it.close() } }
            runCatching { output.close() }
            Log.e(TAG, "merge refused", failure)
            terminator.execute(::endWorkerProcess)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun endWorkerProcess() {
        // Never a client-provided PID; only this verified private process ends itself.
        if (permittedProcess) {
            Log.i(TAG, "worker exiting pid=${Process.myPid()}")
            Process.killProcess(Process.myPid())
        }
    }

    private fun legacySelfName(): String? = runCatching {
        FileInputStream("/proc/self/cmdline").use { input ->
            val bytes = ByteArray(256)
            var i = 0
            while (i < bytes.size) {
                val next = input.read()
                if (next < 0) return@use null
                if (next == 0) return@use bytes.decodeToString(0, i, throwOnInvalidSequence = true)
                bytes[i++] = next.toByte()
            }
            null
        }
    }.getOrNull()

    private companion object { const val TAG = "OpusMergeWorker" }
}
