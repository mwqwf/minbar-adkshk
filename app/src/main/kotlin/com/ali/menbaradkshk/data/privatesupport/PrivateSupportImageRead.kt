package com.ali.menbaradkshk.data.privatesupport

import java.io.IOException
import java.io.InputStream

private const val WORKER = "https://minbar-api.mushafak.workers.dev"
private const val MAX_IMAGE = 262_144
private val threadPattern = Regex("spt_[a-f0-9]{32}")
private val credentialPattern = Regex("sil_[a-f0-9]{64}")
private val lengthPattern = Regex("[1-9][0-9]{0,5}")

/** Wire packet: do not log or persist it. Only a dedicated transport without redirects or cache may consume it. */
internal class PrivateSupportImageGet private constructor(
    val url: String,
    val authorization: String,
) {
    val method get() = "GET"
    override fun toString() = "PrivateSupportImageGet(redacted)"

    companion object {
        fun forListener(threadId: String, credential: String): PrivateSupportImageGet? {
            if (!threadPattern.matches(threadId) || !credentialPattern.matches(credential)) return null
            return PrivateSupportImageGet("$WORKER/v2/private-support/threads/$threadId/image", "Bearer $credential")
        }
    }
}

internal interface PrivateSupportImageResponse : AutoCloseable {
    val status: Int
    fun header(name: String): String?
    /** No bytes are opened on denial or rejected headers. */
    fun openBody(): InputStream
}

/**
 * Narrow, dormant protocol boundary, not an app entry point. The caller must supply a fresh
 * explicit view action and an exact installation+thread lease. The transport is not implemented
 * here and must never redirect, retry, use cookies/shared HTTP cache, or permit a public fallback.
 * The result is bounded memory for a future checked decoder, not verified display-safe pixels.
 */
internal fun fetchPrivateSupportImageBytes(
    capabilityEnabled: Boolean,
    explicitView: Boolean,
    threadId: String,
    installationCredential: String,
    isSelectionCurrent: () -> Boolean,
    openExactGet: (PrivateSupportImageGet) -> PrivateSupportImageResponse,
): ByteArray? {
    if (!capabilityEnabled || !explicitView || !isSelectionCurrent()) return null
    val request = PrivateSupportImageGet.forListener(threadId, installationCredential) ?: return null
    var temporary: ByteArray? = null
    var accepted = false
    try {
        if (!isSelectionCurrent()) return null
        openExactGet(request).use { response ->
            if (response.status != 200 || !isSelectionCurrent()) return null
            val size = response.header("Content-Length")
                ?.takeIf(lengthPattern::matches)?.toIntOrNull()?.takeIf { it in 4..MAX_IMAGE } ?: return null
            val cache = response.header("Cache-Control")?.split(',')?.map { it.trim().lowercase() }.orEmpty()
            val coding = response.header("Content-Encoding")
            if (response.header("Content-Type") != "image/jpeg" ||
                response.header("X-Content-Type-Options") != "nosniff" ||
                response.header("Content-Disposition") != "attachment; filename=\"support-image.jpg\"" ||
                "private" !in cache || "no-store" !in cache || "public" in cache ||
                (coding != null && !coding.equals("identity", ignoreCase = true)) ||
                response.header("Location") != null || response.header("Content-Range") != null ||
                !isSelectionCurrent()
            ) return null
            val data = ByteArray(size)
            temporary = data
            response.openBody().use { body ->
                var offset = 0
                while (offset < size) {
                    if (!isSelectionCurrent()) return null
                    val n = body.read(data, offset, minOf(size - offset, 8192))
                    if (n <= 0) return null
                    offset += n
                }
                if (!isSelectionCurrent() || body.read() != -1) return null
            }
            if (data[0] != 0xff.toByte() || data[1] != 0xd8.toByte() ||
                data[size - 2] != 0xff.toByte() || data[size - 1] != 0xd9.toByte()) return null
        }
        if (!isSelectionCurrent()) return null
        accepted = true
        return temporary
    } catch (_: IOException) {
        // No propagation of transport exceptions that could include request metadata.
        return null
    } finally {
        if (!accepted) temporary?.fill(0)
    }
}
