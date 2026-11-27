package com.ali.menbaradkshk.ui.adminbridge.upload

/** Messenger contract between the verified admin route and the private :opusmerge process. */
internal object OrderedOpusMergeWire {
    const val SERVICE = "com.ali.menbaradkshk.ui.adminbridge.upload.OrderedOpusMergeService"
    const val PROCESS = ":opusmerge"
    const val START = 1
    const val FINISH = 2
    const val STARTED = 3
    const val GENERATION = "generation"
    const val OUTPUT_PIPE = "output_pipe"
    const val FILES = "files"
    const val LENGTHS = "lengths"
    const val DIGESTS = "digests"
    const val WORKER_PID = "worker_pid"
    const val MIN_INPUTS = 2
    const val MAX_INPUTS = MAX_LOCAL_MERGE_INPUTS

    /** Codec work budget inside the worker; shorter than the process terminator below. */
    const val CODEC_BUDGET_MS = 170_000L
    /** Outermost bound: the worker process ends itself after this, START or not. */
    const val PROCESS_LIFETIME_MS = 200_000L
    /** A bind that never sends START is ended much earlier. */
    const val IDLE_BIND_MS = 30_000L

    fun validStart(count: Int, lengths: LongArray?, digests: List<String>?, maxBytes: Long = MAX_ORIGINAL_BYTES): Boolean {
        if (count !in MIN_INPUTS..MAX_INPUTS || lengths == null || digests == null) return false
        if (lengths.size != count || digests.size != count) return false
        var total = 0L
        for (i in 0 until count) {
            if (lengths[i] !in 1..maxBytes) return false
            if (!digests[i].matches(SHA)) return false
            total += lengths[i]
        }
        return total <= MAX_LOCAL_MERGE_BYTES && digests.toSet().size == count
    }

    /** Process name must be exactly "<applicationId>:opusmerge"; anything else refuses to bind. */
    fun permittedProcess(packageName: String, processName: String?): Boolean =
        packageName.startsWith("com.ali.menbaradkshk") && processName == packageName + PROCESS

    private val SHA = Regex("[0-9a-f]{64}")
}
