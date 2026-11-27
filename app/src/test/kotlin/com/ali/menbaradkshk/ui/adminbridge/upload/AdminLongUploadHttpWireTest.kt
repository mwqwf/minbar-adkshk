package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdminLongUploadHttpWireTest {
    @Test fun acceptsSharedGoldenStatesAndEncodesExactManifest() {
        val path = System.getProperty("minbar.longGoldenV1")
        if (!path.isNullOrBlank()) {
            for (line in File(path).readLines()) {
                val (classification, name, payload) = line.split('\t', limit = 3)
                when (classification) {
                    "manifest" -> assertEquals(payload, encodeManifest(MANIFEST).decodeToString())
                    "valid" -> assertEquals(ID, decode(payload).draftId)
                    "reject" -> assertThrows(name, IOException::class.java) { decode(payload) }
                    else -> fail(name)
                }
            }
        }
        assertEquals(0, decode(state()).verified.size)
        assertEquals(listOf(1, 2), decode(state("[1,2]", "[]", "true")).verified)
        assertTrue(decode(state("[1,2]", "[]", "true")).completed)
        val invalid = listOf(
            state().replace("\"protocolVersion\":1", "\"protocolVersion\":1.0"),
            state().replace("\"protocolVersion\":1", "\"protocolVersion\":1e0"),
            state().replace("\"protocolVersion\":1", "\"protocolVersion\":01"),
            state().replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\""),
            state().replace("false", "\"false\""),
            state().replace("\"completed\"", "\"complet\\u0065d\""),
            state().replace("\"completed\":false", "\"completed\":false,\"completed\":true"),
            state().replace("\"completed\":false", "\"Completed\":false"),
            state().replace("\"completed\":false", "\"completed\":false,\"publicUrl\":\"ignored\""),
            state().replace(ID, "10290c03-a988-41a5-956e-8ebd9a0ee0a6"),
            state().replace(WHOLE, "a".repeat(64)),
            state("[]", "[1,2]", "true"),
            state("[1,1]", "[2]"),
            state("[]", "[2,1]"),
            state("[1]", "[1,2]"),
            state("[1]", "[]"),
            state() + " {}",
            state() + "é",
        )
        for (payload in invalid) assertThrows(IOException::class.java) { decode(payload) }
    }

    @Test fun buildsExactOriginRelativeRoutesAndIgnoresNoFailedSecurityChecks() = runBlocking {
        val calls = mutableListOf<LongUploadRequest>()
        val root = "/admin/private-long-drafts/$ID"
        var guards = 0
        val guard: () -> Unit = { guards++ }
        val wire = AdminLongUploadHttpWire { request, beforeOpen ->
            beforeOpen()
            calls += request
            val body = when {
                request.path.endsWith("/complete") -> state("[1,2]", "[]", "true")
                request.path.endsWith("/parts/2") -> state("[1,2]", "[]")
                else -> state("[1]", "[2]")
            }.encodeToByteArray()
            LongUploadResponse(200, "Application/JSON; charset=\"UTF-8\"", body.size.toLong(), body.inputStream())
        }
        assertEquals(listOf(2), wire.begin(ID, MANIFEST, guard).missing)
        assertEquals(listOf(2), wire.reconcile(ID, MANIFEST, guard).missing)
        assertEquals(listOf(1, 2), wire.put(ID, MANIFEST, MANIFEST.parts.last(), TAIL, guard).verified)
        assertTrue(wire.complete(ID, MANIFEST, guard).completed)
        assertEquals(listOf(root, root, "$root/parts/2", "$root/complete"), calls.map { it.path })
        assertEquals(listOf(LongUploadMethod.POST, LongUploadMethod.GET, LongUploadMethod.PUT, LongUploadMethod.POST),
            calls.map { it.method })
        assertEquals(listOf("application/json", null, "application/octet-stream", null), calls.map { it.contentType })
        assertEquals(listOf(encodeManifest(MANIFEST).size, 0, TAIL.size, 0), calls.map { it.contentLength })
        assertTrue(calls.all { !it.followRedirects && it.maxResponseBytes == 8192 })
        assertTrue(guards > calls.size * 2)
        fails { wire.begin("$ID/../elsewhere", MANIFEST, guard) }
        fails { wire.begin(ID, MANIFEST.copy(parts = MANIFEST.parts.reversed()), guard) }
        fails { wire.put(ID, MANIFEST, MANIFEST.parts.last(), byteArrayOf(), guard) }
        fails { wire.begin(ID, MANIFEST) { throw IOException("unverified lease") } }
        assertEquals(4, calls.size)
    }

    @Test fun boundsStreamingResponseAndNeverRetriesBadOriginStatusOrMetadata() = runBlocking {
        var calls = 0
        var payload = state().encodeToByteArray()
        var length: Long? = null
        var status = 200
        var type: String? = "application/json"
        var observed = 0
        val wire = AdminLongUploadHttpWire { _, beforeOpen ->
            beforeOpen()
            calls++
            val body = object : ByteArrayInputStream(payload) {
                override fun read(): Int = super.read().also { if (it != -1) observed++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) observed += it }
            }
            LongUploadResponse(status, type, length, body)
        }
        status = 302
        fails { wire.begin(ID, MANIFEST) {} }
        status = 200
        type = "text/html"
        fails { wire.begin(ID, MANIFEST) {} }
        type = "application/json"
        length = 8193
        fails { wire.begin(ID, MANIFEST) {} }
        assertEquals(0, observed)
        length = null
        payload = ByteArray(90_000) { ' '.code.toByte() }
        fails { wire.begin(ID, MANIFEST) {} }
        assertEquals(8193, observed)
        payload = state().encodeToByteArray()
        length = payload.size.toLong() + 1
        fails { wire.begin(ID, MANIFEST) {} }
        length = null
        fails { wire.complete(ID, MANIFEST) {} }
        assertEquals(6, calls)
    }

    private suspend fun fails(action: suspend () -> Unit) {
        assertTrue(runCatching { action() }.exceptionOrNull() is IOException)
    }

    private fun decode(payload: String) = decodeLongUploadState(payload.encodeToByteArray(), ID, MANIFEST)

    private fun state(verified: String = "[]", missing: String = "[1,2]", completed: String = "false") =
        "{\"protocolVersion\":1,\"draftId\":\"$ID\",\"totalBytes\":8388643,\"wholeSha256\":\"$WHOLE\"," +
            "\"verified\":$verified,\"missing\":$missing,\"completed\":$completed}"

    private companion object {
        const val ID = "01d60c03-a988-41a5-956e-8ebd9a0ed0a5"
        const val WHOLE = "3cccba09913a37a18d6e845bd937266f05b943fc0579eb57030e251f848cfbe9"
        val TAIL = "synthetic-minbar-transport-fixture\n".encodeToByteArray()
        val MANIFEST = AdminMultipartManifest(8388643, WHOLE, listOf(
            AdminMultipartPart(1, 8388608, "b63446a770bf575bb6247db059d6a4a2463b9d02170507937fbd459778c37acd"),
            AdminMultipartPart(2, 35, "b3a613aed42861292ae84dcc3bbb54761e9d800768255f27134c8c0e49e3e778"),
        ))
    }
}
