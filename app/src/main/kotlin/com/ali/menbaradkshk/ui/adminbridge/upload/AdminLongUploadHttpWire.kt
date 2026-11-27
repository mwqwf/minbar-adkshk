package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_LONG_STAGE_RESPONSE = 8 * 1024
private const val ROOT = "/admin/private-long-drafts"
private val UUID4 = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
private val SHA256 = Regex("[a-f0-9]{64}")
private val JSON_RESPONSE = Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

internal enum class LongUploadMethod { GET, POST, PUT }
internal data class LongUploadRequest(
    val method: LongUploadMethod,
    val path: String,
    val contentType: String?,
    val body: ByteArray?,
    val maxResponseBytes: Int = MAX_LONG_STAGE_RESPONSE,
    val followRedirects: Boolean = false,
) {
    val contentLength: Int get() = body?.size ?: 0
}

internal data class LongUploadResponse(
    val status: Int,
    val contentType: String?,
    val contentLength: Long?,
    val body: InputStream,
)

/** No HTTP implementation or credential here. A future authenticated binder is responsible for a fixed
 * HTTPS origin, honoring the no-redirect flag, and calling beforeOpen at each actual open/send. */
internal fun interface LongUploadTransport {
    suspend fun exchange(request: LongUploadRequest, beforeOpen: () -> Unit): LongUploadResponse
}

internal class AdminLongUploadHttpWire(private val transport: LongUploadTransport) : AdminLongUploadWire, AdminLongAssemblyWire, AdminLongPrepareWire {
    override suspend fun begin(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit) =
        request(LongUploadMethod.POST, path(draftId), draftId, manifest, guard, "application/json", encodeManifest(manifest))

    suspend fun reconcile(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit) =
        request(LongUploadMethod.GET, path(draftId), draftId, manifest, guard)

    override suspend fun put(
        draftId: String, manifest: AdminMultipartManifest, part: AdminMultipartPart, bytes: ByteArray, guard: () -> Unit,
    ): AdminLongUploadState {
        val base = path(draftId)
        if (part.index !in 1..MAX_MULTIPART_PARTS || manifest.parts.getOrNull(part.index - 1) != part ||
            part.bytes != bytes.size || bytes.size !in 1..MULTIPART_CHUNK_BYTES)
            invalidState()
        return request(LongUploadMethod.PUT, "$base/parts/${part.index}", draftId, manifest, guard, "application/octet-stream", bytes)
    }

    override suspend fun complete(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState {
        val state = request(LongUploadMethod.POST, "${path(draftId)}/complete", draftId, manifest, guard)
        if (!state.completed) invalidState()
        return state
    }

    override suspend fun assemble(receipt: AdminLongUploadReceipt, guard: () -> Unit): AdminLongAssemblyReceipt {
        val route = "${path(receipt.draftId)}/assemble"
        if (receipt.totalBytes !in 1..MAX_ORIGINAL_BYTES || !SHA256.matches(receipt.wholeSha256)) invalidState()
        val bytes = exchange(LongUploadRequest(LongUploadMethod.POST, route, null, null), guard)
        val result = decodeLongAssembly(bytes, receipt)
        guard()
        return result
    }

    override suspend fun prepare(
        assembly: AdminLongAssemblyReceipt, intent: AdminLongPrepareIntent, guard: () -> Unit,
    ): AdminLongPreparationReceipt {
        val route = "${path(assembly.draftId)}/prepare"
        val expected = AdminLongUploadReceipt(assembly.draftId, assembly.totalBytes, assembly.wholeSha256)
        if (expected.totalBytes !in 1..MAX_ORIGINAL_BYTES || !SHA256.matches(expected.wholeSha256)) invalidState()
        val bytes = exchange(LongUploadRequest(LongUploadMethod.POST, route, "application/json", intent.encode(assembly)), guard)
        val result = decodeLongProof(bytes, expected, "prepared")
        guard()
        return AdminLongPreparationReceipt(result.draftId, result.totalBytes, result.wholeSha256)
    }

    private suspend fun request(
        method: LongUploadMethod,
        route: String,
        draftId: String,
        manifest: AdminMultipartManifest,
        guard: () -> Unit,
        contentType: String? = null,
        body: ByteArray? = null,
    ): AdminLongUploadState {
        validateManifest(manifest)
        val bytes = exchange(LongUploadRequest(method, route, contentType, body), guard)
        val state = decodeLongUploadState(bytes, draftId, manifest)
        guard()
        return state
    }

    private suspend fun exchange(request: LongUploadRequest, guard: () -> Unit): ByteArray {
        guard()
        val response = transport.exchange(request, guard)
        return response.body.use { input ->
            guard()
            if (response.status != 200 || !JSON_RESPONSE.matches(response.contentType?.trim().orEmpty()) ||
                response.contentLength?.let { it !in 1L..MAX_LONG_STAGE_RESPONSE.toLong() } == true)
                invalidState()
            val bytes = boundedResponse(input, guard)
            if (response.contentLength != null && bytes.size.toLong() != response.contentLength) invalidState()
            guard()
            bytes
        }
    }
}

private fun path(draftId: String): String {
    if (!UUID4.matches(draftId)) invalidState()
    return "$ROOT/$draftId"
}

private fun validateManifest(manifest: AdminMultipartManifest) {
    val total = manifest.totalBytes
    if (total !in 1..MAX_ORIGINAL_BYTES || !SHA256.matches(manifest.wholeSha256)) invalidState()
    val count = ((total + MULTIPART_CHUNK_BYTES - 1) / MULTIPART_CHUNK_BYTES).toInt()
    if (manifest.parts.size != count || count !in 1..MAX_MULTIPART_PARTS) invalidState()
    for ((i, part) in manifest.parts.withIndex()) {
        val remaining = total - i.toLong() * MULTIPART_CHUNK_BYTES
        if (part.index != i + 1 || part.bytes.toLong() != minOf(remaining, MULTIPART_CHUNK_BYTES.toLong()) ||
            !SHA256.matches(part.sha256)) invalidState()
    }
}

internal fun encodeManifest(manifest: AdminMultipartManifest): ByteArray {
    validateManifest(manifest)
    // Every interpolated text value is SHA-256 hex; no user metadata or path is passed through.
    return buildString {
        append("{\"totalBytes\":${manifest.totalBytes},\"wholeSha256\":\"${manifest.wholeSha256}\",\"parts\":[")
        manifest.parts.forEachIndexed { index, p ->
            if (index > 0) append(',')
            append("{\"index\":${p.index},\"bytes\":${p.bytes},\"sha256\":\"${p.sha256}\"}")
        }
        append("]}")
    }.encodeToByteArray()
}

private fun boundedResponse(input: InputStream, guard: () -> Unit): ByteArray {
    val buffer = ByteArray(1024)
    val out = ByteArrayOutputStream()
    while (out.size() < MAX_LONG_STAGE_RESPONSE) {
        guard()
        val max = minOf(buffer.size, MAX_LONG_STAGE_RESPONSE - out.size())
        val n = input.read(buffer, 0, max)
        if (n == -1) return out.toByteArray()
        if (n !in 1..max) invalidState()
        out.write(buffer, 0, n)
    }
    guard()
    if (input.read() != -1) invalidState()
    return out.toByteArray()
}

/** Version one accepts only the bounded, canonical ASCII strings emitted by the pinned Worker.
 * JSON escape aliases, duplicate fields, unrecognized fields, number coercion and trailing input fail. */
internal fun decodeLongUploadState(bytes: ByteArray, expectedId: String, manifest: AdminMultipartManifest): AdminLongUploadState {
    if (bytes.size !in 1..MAX_LONG_STAGE_RESPONSE || !UUID4.matches(expectedId) ||
        bytes.any { (it.toInt() and 0xff) >= 128 }) invalidState()
    validateManifest(manifest)
    val parser = LongStateParser(bytes.decodeToString())
    val state = parser.parse()
    val combined = state.verified + state.missing
    if (state.protocolVersion != 1 || state.draftId != expectedId ||
        state.totalBytes != manifest.totalBytes || state.wholeSha256 != manifest.wholeSha256 ||
        state.verified != state.verified.sorted() || state.missing != state.missing.sorted() ||
        combined.sorted() != (1..manifest.parts.size).toList() ||
        (state.completed && state.missing.isNotEmpty())) invalidState()
    return state
}

internal fun decodeLongAssembly(bytes: ByteArray, expected: AdminLongUploadReceipt): AdminLongAssemblyReceipt =
    decodeLongProof(bytes, expected, "assembled")

internal fun decodeLongProof(bytes: ByteArray, expected: AdminLongUploadReceipt, resultField: String): AdminLongAssemblyReceipt {
    require(resultField == "assembled" || resultField == "prepared")
    if (bytes.size !in 1..MAX_LONG_STAGE_RESPONSE || !UUID4.matches(expected.draftId) ||
        !SHA256.matches(expected.wholeSha256) || expected.totalBytes !in 1..MAX_ORIGINAL_BYTES ||
        bytes.any { (it.toInt() and 0xff) >= 128 }) invalidState()
    val result = LongStateParser(bytes.decodeToString()).parseProof(resultField)
    if (result.draftId != expected.draftId || result.totalBytes != expected.totalBytes ||
        result.wholeSha256 != expected.wholeSha256) invalidState()
    return result
}

private class LongStateParser(private val input: String) {
    private var offset = 0

    fun parse(): AdminLongUploadState {
        take('{')
        val fields = mutableSetOf<String>()
        var version: Long? = null
        var id: String? = null
        var size: Long? = null
        var hash: String? = null
        var verified: List<Int>? = null
        var missing: List<Int>? = null
        var complete: Boolean? = null
        do {
            val key = text()
            if (!fields.add(key)) invalidState()
            take(':')
            when (key) {
                "protocolVersion" -> version = number()
                "draftId" -> id = text()
                "totalBytes" -> size = number()
                "wholeSha256" -> hash = text()
                "verified" -> verified = indices()
                "missing" -> missing = indices()
                "completed" -> complete = boolean()
                else -> invalidState()
            }
        } while (optional(','))
        take('}')
        spaces()
        if (offset != input.length || fields.size != 7 || version != 1L) invalidState()
        return AdminLongUploadState(1, id ?: invalidState(), size ?: invalidState(), hash ?: invalidState(),
            verified ?: invalidState(), missing ?: invalidState(), complete ?: invalidState())
    }

    fun parseProof(resultField: String): AdminLongAssemblyReceipt {
        take('{')
        val fields = mutableSetOf<String>()
        var id: String? = null
        var size: Long? = null
        var hash: String? = null
        var assembled = false
        do {
            val key = text()
            if (!fields.add(key)) invalidState()
            take(':')
            when (key) {
                "draftId" -> id = text()
                "totalBytes" -> size = number()
                "wholeSha256" -> hash = text()
                resultField -> assembled = boolean()
                else -> invalidState()
            }
        } while (optional(','))
        take('}')
        spaces()
        if (offset != input.length || fields.size != 4 || !assembled) invalidState()
        return AdminLongAssemblyReceipt(id ?: invalidState(), size ?: invalidState(), hash ?: invalidState())
    }

    private fun spaces() {
        while (offset < input.length && input[offset] in " \t\r\n") offset++
    }

    private fun optional(expected: Char): Boolean {
        spaces()
        if (input.getOrNull(offset) != expected) return false
        offset++
        return true
    }

    private fun take(expected: Char) {
        if (!optional(expected)) invalidState()
    }

    private fun text(): String {
        take('"')
        val start = offset
        while (offset < input.length && input[offset] != '"') {
            val c = input[offset++]
            if (c !in 'A'..'Z' && c !in 'a'..'z' && c !in '0'..'9' && c != '-' && c != '_') invalidState()
        }
        val text = input.substring(start, offset)
        if (input.getOrNull(offset++) != '"') invalidState()
        return text
    }

    private fun number(): Long {
        spaces()
        val start = offset
        while (input.getOrNull(offset)?.let { it in '0'..'9' } == true) offset++
        if (offset == start || (offset - start > 1 && input[start] == '0')) invalidState()
        return input.substring(start, offset).toLongOrNull() ?: invalidState()
    }

    private fun boolean(): Boolean {
        spaces()
        if (input.startsWith("true", offset)) {
            offset += 4
            return true
        }
        if (input.startsWith("false", offset)) {
            offset += 5
            return false
        }
        invalidState()
    }

    private fun indices(): List<Int> {
        take('[')
        if (optional(']')) return emptyList()
        val result = ArrayList<Int>()
        do {
            val value = number()
            if (value !in 1L..MAX_MULTIPART_PARTS.toLong() || result.size >= MAX_MULTIPART_PARTS) invalidState()
            result += value.toInt()
        } while (optional(','))
        take(']')
        return result
    }
}

private fun invalidState(): Nothing = throw IOException("Invalid private long upload protocol")
