package com.ali.menbaradkshk.util

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

internal enum class SharedTranscriptImageType(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp"),
}

internal class InvalidSharedTranscriptImage : IOException("Unsupported or incomplete shared image")

/** Only successful, checked images return a selectable filename. Never rename an unknown type to JPEG. */
internal fun stageSharedTranscriptImage(
    directory: File,
    maxBytes: Long,
    open: () -> InputStream,
    checkActive: () -> Unit,
    canPreview: (File, SharedTranscriptImageType) -> Boolean,
): File {
    require(maxBytes > 0)
    checkActive()
    val temp = File.createTempFile("transcript_", ".part", directory)
    try {
        val size = open().use { source ->
            temp.outputStream().use { output -> copyWithinContributionCap(source, output, maxBytes, checkActive) }
        }
        if (size <= 0) throw InvalidSharedTranscriptImage()
        checkActive()
        val type = identifySharedTranscriptImage(temp) ?: throw InvalidSharedTranscriptImage()
        if (!canPreview(temp, type)) throw InvalidSharedTranscriptImage()
        checkActive()
        val verified = File(directory, temp.nameWithoutExtension + "." + type.extension)
        if (verified.exists() || !temp.renameTo(verified)) throw IOException("Cannot stage complete shared image")
        return verified
    } finally {
        runCatching { temp.delete() }
    }
}

/** Only use type inference for newly verified app-owned share cache paths, never arbitrary picked URIs. */
internal fun isOwnedSharedTranscriptImage(directory: File, candidate: File): Boolean =
    candidate.absoluteFile == candidate.canonicalFile && candidate.parentFile == directory.canonicalFile &&
        candidate.isFile && candidate.nameWithoutExtension.matches(Regex("transcript_-?[0-9]+"))

internal fun ownedSharedTranscriptImageType(directory: File, candidate: File): SharedTranscriptImageType? {
    if (!isOwnedSharedTranscriptImage(directory, candidate)) return null
    val type = identifySharedTranscriptImage(candidate)
    return type?.takeIf { it.extension == candidate.extension }
}

private fun identifySharedTranscriptImage(file: File): SharedTranscriptImageType? =
    RandomAccessFile(file, "r").use { source ->
        val size = source.length()
        if (size < 12) return null
        val header = ByteArray(12)
        source.readFully(header)
        fun starts(offset: Int, marker: ByteArray): Boolean = marker.indices.all { header[offset + it] == marker[it] }
        when {
            starts(0, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> {
                source.seek(size - 2)
                if (source.readUnsignedShort() == 0xffd9) SharedTranscriptImageType.JPEG else null
            }
            size >= 45 && starts(0, PNG_SIGNATURE) -> {
                source.seek(8)
                if (source.readInt() != 13 || source.readInt() != 0x49484452) return null
                source.seek(size - 12)
                val end = ByteArray(12)
                source.readFully(end)
                if (end.contentEquals(PNG_END)) SharedTranscriptImageType.PNG else null
            }
            size >= 20 && starts(0, "RIFF".encodeToByteArray()) && starts(8, "WEBP".encodeToByteArray()) -> {
                val declared = (0..3).sumOf { ((header[4 + it].toInt() and 255).toLong() shl (8 * it)) }
                val chunk = source.readInt()
                if (declared + 8 == size && chunk in setOf(0x56503820, 0x5650384c, 0x56503858))
                    SharedTranscriptImageType.WEBP else null
            }
            else -> null
        }
    }

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val PNG_END = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
