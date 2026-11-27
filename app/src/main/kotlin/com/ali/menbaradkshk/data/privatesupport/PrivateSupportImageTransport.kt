package com.ali.menbaradkshk.data.privatesupport

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

// Immutable profile consumed by a dedicated native engine. No shared legacy
// client must implement this interface. No native engine is selected here.
internal object PrivateSupportImageTlsProfile {
    const val followRedirects = false
    const val retryFailures = false
    const val useCookies = false
    const val useCache = false
    const val useAuthenticator = false
    const val connectionTimeoutMs = 4_000
    const val readTimeoutMs = 4_000
    const val entireCallTimeoutMs = 12_000
}

internal class PrivateSupportImageCall internal constructor(
    request: PrivateSupportImageGet,
) {
    val method: String = request.method
    val url: String = request.url
    val profile = PrivateSupportImageTlsProfile
    fun requestHeader(name: String): String? = when {
        name.equals("Authorization", true) -> authorization
        name.equals("Accept", true) -> "image/jpeg"
        name.equals("Accept-Encoding", true) -> "identity"
        name.equals("Cache-Control", true) -> "no-store"
        else -> null
    }
    private val authorization = request.authorization
    override fun toString() = "PrivateSupportImageCall(redacted)"
}

internal interface PrivateSupportImageRawExchange : AutoCloseable {
    val status: Int
    /** Every actual header line, before normalization or collapsing duplicates. */
    val headers: List<Pair<String, String>>
    fun openBody(): InputStream
}

private val unambiguousPrivateHeaders = setOf(
    "content-type", "content-length", "content-disposition", "content-encoding",
    "cache-control", "x-content-type-options", "location", "content-range",
    "transfer-encoding", "set-cookie", "set-cookie2",
)

private fun validateHeaders(values: List<Pair<String, String>>): Map<String, String>? {
    if (values.size > 96) return null
    val checked = mutableMapOf<String, String>()
    for ((wireName, wireValue) in values) {
        if (wireName.length !in 1..128 || wireValue.length > 4096 ||
            wireName.any { it <= ' ' || it >= 127.toChar() || it == ':' } ||
            wireValue.any { (it < ' ' && it != '\t') || it == 127.toChar() }
        ) return null
        val name = wireName.lowercase()
        if (name in unambiguousPrivateHeaders &&
            (name == "set-cookie" || name == "set-cookie2" ||
                checked.put(name, wireValue) != null)
        ) return null
    }
    // Ambiguous framing is never passed to the envelope or body reader.
    if ("transfer-encoding" in checked && "content-length" in checked) return null
    return checked
}

/** Wired only to offline fakes so far; the native transport must honor the full profile. */
internal fun openPrivateSupportImageExchange(
    request: PrivateSupportImageGet,
    openTlsOnce: (PrivateSupportImageCall) -> PrivateSupportImageRawExchange,
): PrivateSupportImageResponse {
    var exchange: PrivateSupportImageRawExchange? = null
    try {
        val raw = openTlsOnce(PrivateSupportImageCall(request))
        exchange = raw
        val status = raw.status
        val headers = validateHeaders(raw.headers) ?: throw IOException()
        return object : PrivateSupportImageResponse {
            override val status = status
            override fun header(name: String) = headers[name.lowercase()]
            override fun openBody(): InputStream = privateIo {
                object : FilterInputStream(raw.openBody()) {
                    override fun read(): Int = privateIo { super.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        privateIo { super.read(bytes, offset, length) }
                    override fun close(): Unit = privateIo { super.close() }
                }
            }
            override fun close() {
                try { raw.close() }
                catch (_: IOException) { throw IOException("Private image close failed") }
                catch (_: SecurityException) { throw IOException("Private image close failed") }
            }
        }
    } catch (_: IOException) {
        runCatching { exchange?.close() }
        throw IOException("Private image response unavailable")
    } catch (_: SecurityException) {
        runCatching { exchange?.close() }
        throw IOException("Private image response unavailable")
    }
}

private inline fun <T> privateIo(block: () -> T): T = try {
    block()
} catch (_: IOException) {
    throw IOException("Private image stream unavailable")
} catch (_: SecurityException) {
    throw IOException("Private image stream unavailable")
}
