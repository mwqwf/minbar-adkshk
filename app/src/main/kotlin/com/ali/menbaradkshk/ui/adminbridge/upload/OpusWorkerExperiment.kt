package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Test-only result pipe: big-endian magic, generation, payload length, digest, payload, EOF.
 * The reader runs on an I/O thread, not the independent deadline monitor. The sink is exclusive
 * private scratch for this generation, never a published file; the caller discards it on failure.
 * This bounds decoded transport work, not codec time, and never treats a digest as codec validation.
 */
internal object OpusWorkerResultPipe {
    const val MAGIC = 0x4f575231 // OWR1
    private const val MAX_PIPE_BYTES = 256L * 1024 * 1024

    fun stage(
        pipe: InputStream, generation: Long, byteLimit: Long, scratch: OutputStream,
        checkLive: () -> Unit,
    ): Long {
        require(generation > 0L && byteLimit in 1L..MAX_PIPE_BYTES)
        val source = DataInputStream(pipe)
        checkLive()
        if (source.readInt() != MAGIC || source.readLong() != generation) throw IOException("Stale or invalid Opus frame")
        val size = source.readLong()
        if (size !in 1L..byteLimit) throw IOException("Out-of-bound Opus frame")
        val expected = ByteArray(32).also(source::readFully)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(minOf(16L * 1024, size).toInt())
        var remaining = size
        while (remaining > 0) {
            checkLive()
            val amount = minOf(buffer.size.toLong(), remaining).toInt()
            source.readFully(buffer, 0, amount)
            checkLive()
            scratch.write(buffer, 0, amount)
            digest.update(buffer, 0, amount)
            remaining -= amount
        }
        checkLive()
        // A valid dedicated pipe is closed by the sender; waiting for this EOF is deadline-bound
        // externally. Extra bytes are a framing violation even when the leading digest matches.
        if (source.read() != -1) throw IOException("Unexpected Opus trailing data")
        if (!MessageDigest.isEqual(expected, digest.digest())) throw IOException("Opus digest mismatch")
        checkLive()
        return size
    }
}

/**
 * One in-flight generation per supervisor. State methods perform no I/O or app callbacks.
 * Use with one supervisor per client and one dedicated endpoint per generation. The failure caller
 * closes the pipes and requests worker death outside this lock. A watchdog may act while I/O or
 * validation is blocked. No retry after failure until that generation's endpoint death is confirmed.
 */
internal class OpusWorkerExperiment(private val nanoTime: () -> Long = System::nanoTime) {
    class Job internal constructor(val generation: Long)
    enum class Stop { TIMEOUT, CANCELLED, MALFORMED, DEAD }
    private data class Active(val job: Job, val started: Long, val budgetNs: Long)
    private var sequence = 0L
    private var active: Active? = null
    private var quarantine: Job? = null

    @Synchronized fun begin(budgetNs: Long): Job? {
        require(budgetNs in 1L..(10L * 60 * 1_000_000_000))
        if (active != null || quarantine != null || sequence == Long.MAX_VALUE) return null
        val job = Job(++sequence)
        active = Active(job, nanoTime(), budgetNs)
        return job
    }

    @Synchronized fun live(job: Job): Boolean = active?.let { it.job == job && !expired(it) } ?: false

    @Synchronized fun watchdog(job: Job): Stop? {
        val current = active ?: return null
        if (current.job != job || !expired(current)) return null
        return quarantine(current, Stop.TIMEOUT)
    }

    @Synchronized fun cancel(job: Job): Stop? = active?.takeIf { it.job == job }?.let {
        quarantine(it, if (expired(it)) Stop.TIMEOUT else Stop.CANCELLED)
    }

    // Only the client calls this after independent staging validation and its last lease check.
    // A late or stale completion cannot authorize handing the generation's candidate to the app.
    @Synchronized fun complete(job: Job, validated: Boolean): Boolean {
        val current = active?.takeIf { it.job == job } ?: return false
        if (expired(current)) { quarantine(current, Stop.TIMEOUT); return false }
        if (!validated) { quarantine(current, Stop.MALFORMED); return false }
        // Even a successful sender must be gone before admitting another worker.
        quarantine = current.job
        active = null
        return true
    }

    // Timer/death notifications of old endpoints cannot release current quarantine.
    @Synchronized fun confirmEndpointDeath(job: Job): Stop? {
        val current = active
        if (current?.job == job) {
            active = null
            return if (expired(current)) Stop.TIMEOUT else Stop.DEAD
        }
        if (quarantine == job) quarantine = null
        return null
    }

    @Synchronized fun requiresEndpointDeath(job: Job): Boolean = quarantine == job

    private fun expired(entry: Active) = nanoTime() - entry.started >= entry.budgetNs
    private fun quarantine(entry: Active, reason: Stop): Stop {
        active = null
        quarantine = entry.job
        return reason
    }
}
