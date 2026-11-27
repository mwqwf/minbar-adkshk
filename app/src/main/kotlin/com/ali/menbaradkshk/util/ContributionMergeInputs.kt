package com.ali.menbaradkshk.util

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal class ContributionMergeSizeExceeded : IOException("file_too_large")
internal class ContributionMergeInput(val name: String, val open: () -> InputStream)
internal data class StagedContributionMerge(val directory: File, val parts: List<File>)

/** Read provider streams once without trusting their declared sizes. Only this attempt owns its files. */
internal fun stageContributionMerge(
    cache: File,
    inputs: List<ContributionMergeInput>,
    maxBytes: Long,
    checkActive: () -> Unit,
): StagedContributionMerge {
    require(inputs.isNotEmpty() && maxBytes > 0)
    checkActive()
    val directory = File(cache, "merge_${UUID.randomUUID()}")
    if (!directory.mkdir()) throw IOException("Cannot create merge cache")
    var staged = false
    var remaining = maxBytes
    try {
        val parts = inputs.mapIndexed { index, request ->
            checkActive()
            val target = File(directory, "part_$index.${inputExtension(request.name)}")
            request.open().use { source ->
                target.outputStream().use { output ->
                    remaining -= copyWithinContributionCap(source, output, remaining, checkActive)
                }
            }
            target
        }
        checkActive()
        staged = true
        return StagedContributionMerge(directory, parts)
    } finally {
        if (!staged) runCatching { directory.deleteRecursively() }
    }
}


/** A successful share is the only outcome returning a file to the owner. */
internal fun stageSharedContributionAudio(
    directory: File,
    request: ContributionMergeInput,
    maxBytes: Long,
    checkActive: () -> Unit,
): File {
    require(maxBytes > 0)
    checkActive()
    val target = File.createTempFile("shared_", ".${inputExtension(request.name)}", directory)
    var complete = false
    try {
        request.open().use { source ->
            target.outputStream().use { output -> copyWithinContributionCap(source, output, maxBytes, checkActive) }
        }
        checkActive()
        complete = true
        return target
    } finally {
        if (!complete) runCatching { target.delete() }
    }
}

private fun inputExtension(name: String) = name.substringAfterLast('.', "bin").lowercase()
    .takeIf { it.length in 1..6 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "bin"

internal fun copyWithinContributionCap(
    source: InputStream,
    output: OutputStream,
    maxBytes: Long,
    checkActive: () -> Unit,
): Long {
    val buffer = ByteArray(64 * 1024)
    var remaining = maxBytes
    try {
        while (true) {
            checkActive()
            if (remaining == 0L) {
                // Probe for overflow with no extra disk write, including an exact-cap EOF.
                if (source.read() != -1) throw ContributionMergeSizeExceeded()
                return maxBytes
            }
            val requestBytes = minOf(remaining, buffer.size.toLong()).toInt()
            val count = source.read(buffer, 0, requestBytes)
            if (count == -1) return maxBytes - remaining
            if (count !in 1..requestBytes) throw IOException("Cannot read contribution source")
            checkActive()
            output.write(buffer, 0, count)
            remaining -= count
        }
    } finally { buffer.fill(0) }
}
