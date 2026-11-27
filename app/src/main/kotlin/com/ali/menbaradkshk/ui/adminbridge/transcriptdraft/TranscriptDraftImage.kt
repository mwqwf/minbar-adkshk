package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal const val MAX_DRAFT_JPEG_BYTES = 256 * 1024
private const val maxSide = 2048
private const val maxPixels = 2_000_000
private val opShape = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
private val lessonShape = Regex("[A-Za-z0-9_-]{1,100}")
private const val fixedDisposition = "attachment; filename=\"transcript-draft.jpg\""

internal data class DraftImageTarget(val lessonId: String, val operationId: String) {
    init { require(lessonShape.matches(lessonId) && opShape.matches(operationId)) }
    val path = "/admin/guarded-transcript-drafts/$lessonId/$operationId/image"
}

internal fun isDraftImagePath(path: String) =
    Regex("/admin/guarded-transcript-drafts/([A-Za-z0-9_-]{1,100})/([a-f0-9-]{36})/image")
        .matchEntire(path)?.groupValues?.get(2)?.let(opShape::matches) == true

internal fun validDraftImageShape(width: Int, height: Int): Boolean =
    width in 1..maxSide && height in 1..maxSide && width.toLong() * height in 1..maxPixels.toLong()

private fun ByteArray.n(p: Int) = this[p].toInt() and 255
private fun ByteArray.u16(p: Int) = (n(p) shl 8) or n(p + 1)

/** Validate the deliberately narrow fresh JPEG envelope. Returns decoded frame dimensions. */
internal fun draftJpegShape(data: ByteArray): Pair<Int, Int>? {
    if (data.size !in 8..MAX_DRAFT_JPEG_BYTES || data.u16(0) != 0xffd8 ||
        data.u16(data.size - 2) != 0xffd9) return null
    var current = 2
    val frame = mutableSetOf<Int>()
    var shape: Pair<Int, Int>? = null
    repeat(512) { idx ->
        if (current + 4 >= data.size || data.n(current++) != 255) return null
        val kind = data.n(current++)
        val size = data.u16(current)
        val end = current + size
        if (size < 2 || end > data.size - 2) return null
        when (kind) {
            0xe0 -> {
                if (idx != 0 || size != 16 || (0..4).any { data.n(current + 2 + it) != listOf(74,70,73,70,0)[it] } ||
                    data.n(current + 7) != 1 || data.n(current + 8) > 2 || data.n(current + 9) > 2 ||
                    data.u16(current + 14) != 0) return null
            }
            0xdb, 0xc4 -> Unit
            0xc0 -> {
                if (shape != null || size < 11 || data.n(current + 2) != 8) return null
                val h = data.u16(current + 3)
                val w = data.u16(current + 5)
                val count = data.n(current + 7)
                if (!validDraftImageShape(w, h) || count !in listOf(1, 3) || size != 8 + 3 * count) return null
                repeat(count) { i ->
                    val at = current + 8 + i * 3
                    val samp = data.n(at + 1)
                    if (!frame.add(data.n(at)) || samp shr 4 !in 1..4 || samp and 15 !in 1..4 || data.n(at + 2) > 3) return null
                }
                shape = w to h
            }
            0xda -> {
                if (shape == null || size < 8 || end >= data.size - 2) return null
                val count = data.n(current + 2)
                if (count != frame.size || size != 6 + 2 * count || data.n(end - 3) != 0 ||
                    data.n(end - 2) != 63 || data.n(end - 1) != 0) return null
                val scan = mutableSetOf<Int>()
                repeat(count) { i ->
                    val at = current + 3 + i * 2
                    val tables = data.n(at + 1)
                    if (data.n(at) !in frame || !scan.add(data.n(at)) || tables shr 4 > 3 || tables and 15 > 3) return null
                }
                var k = end
                while (k < data.size - 2) {
                    if (data.n(k++) == 255) {
                        if (k >= data.size - 2 || data.n(k++) != 0) return null
                    }
                }
                return shape
            }
            else -> return null
        }
        current = end
    }
    return null
}

/** Only accepts the output of the app's own new pixel encode, not provider bytes. */
internal fun stripFreshJpegMetadata(encoded: ByteArray): ByteArray {
    require(encoded.size in 8..2 * MAX_DRAFT_JPEG_BYTES && encoded.u16(0) == 0xffd8)
    val output = ByteArrayOutputStream(encoded.size)
    output.write(encoded, 0, 2)
    var p = 2
    repeat(512) {
        require(p + 4 < encoded.size && encoded.n(p) == 255)
        val kind = encoded.n(p + 1)
        if (kind == 0xda) {
            output.write(encoded, p, encoded.size - p)
            return output.toByteArray().also { require(draftJpegShape(it) != null) }
        }
        val end = p + 2 + encoded.u16(p + 2)
        require(end >= p + 4 && end < encoded.size)
        when (kind) {
            in 0xe0..0xef, 0xfe -> Unit
            0xdb, 0xc4, 0xc0 -> output.write(encoded, p, end - p)
            else -> error("Unexpected fresh JPEG frame")
        }
        p = end
    }
    error("Missing scan")
}

internal class OwnedDraftJpeg(encoded: ByteArray) {
    private val jpeg = encoded.copyOf()
    val shape = requireNotNull(draftJpegShape(jpeg))
    val size = jpeg.size
    val digest = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun copyBytes() = jpeg.copyOf()
}

internal data class DraftImageFetch(
    val bytes: ByteArray, val mediaType: String?, val length: String?, val cacheControl: String?,
    val noSniff: String?, val disposition: String?,
) {
    fun verified(): OwnedDraftJpeg {
        require(mediaType == "image/jpeg" && length == bytes.size.toString() && noSniff == "nosniff" &&
            disposition == fixedDisposition)
        val cache = cacheControl.orEmpty().split(',').map { it.trim().lowercase() }
        require("private" in cache && "no-store" in cache)
        return OwnedDraftJpeg(bytes)
    }
}

internal class TranscriptDraftImageClient(
    private val active: () -> Boolean,
    private val put: suspend (String, ByteArray) -> Map<String, Any?>,
    private val get: suspend (String) -> DraftImageFetch,
) {
    fun available() = active()

    suspend fun save(target: DraftImageTarget, confirmed: OwnedDraftJpeg) {
        check(active())
        val response = put(target.path, confirmed.copyBytes())
        check(active())
        require(response.keys == setOf("operationId", "lessonId", "sha256", "sizeBytes") &&
            response["operationId"] == target.operationId && response["lessonId"] == target.lessonId &&
            response["sha256"] == confirmed.digest)
        val size = response["sizeBytes"]
        require(size == confirmed.size || size == confirmed.size.toLong())
    }

    suspend fun read(target: DraftImageTarget): OwnedDraftJpeg {
        check(active())
        val response = get(target.path)
        check(active())
        return response.verified()
    }
}
