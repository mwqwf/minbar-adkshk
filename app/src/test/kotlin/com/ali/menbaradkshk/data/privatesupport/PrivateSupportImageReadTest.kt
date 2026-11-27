package com.ali.menbaradkshk.data.privatesupport

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PrivateSupportImageReadTest {
    private val id = "spt_" + "a".repeat(32)
    private val token = "sil_" + "b".repeat(64)
    private val markerFixture = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 42, 0xff.toByte(), 0xd9.toByte())
    private val headers = mapOf(
        "Content-Length" to "5",
        "Content-Type" to "image/jpeg",
        "Content-Disposition" to "attachment; filename=\"support-image.jpg\"",
        "Cache-Control" to "private, no-store",
        "X-Content-Type-Options" to "nosniff",
    )

    private class Reply(
        override val status: Int,
        val headers: Map<String, String>,
        val bytes: ByteArray,
        val onOpen: () -> Unit = {},
    ) : PrivateSupportImageResponse {
        var opens = 0
        var closes = 0
        override fun header(name: String) = headers[name]
        override fun openBody() = ByteArrayInputStream(bytes).also { opens++; onOpen() }
        override fun close() { closes++ }
    }

    @Test fun exactListenerRequestAndBoundedBody() {
        val response = Reply(200, headers, markerFixture)
        val result = fetchPrivateSupportImageBytes(true, true, id, token, { true }) { req ->
            assertEquals("GET", req.method)
            assertEquals("https://minbar-api.mushafak.workers.dev/v2/private-support/threads/$id/image", req.url)
            assertEquals("Bearer $token", req.authorization)
            assertFalse(req.toString().contains(token))
            assertFalse(req.toString().contains(id))
            response
        }
        assertArrayEquals(markerFixture, result)
        assertEquals(1, response.opens)
        assertEquals(1, response.closes)
    }

    @Test fun markerCannotGrantAccessAndInvalidInputNeverOpensTransport() {
        fun attempt(show: Boolean = true, capability: Boolean = true, scope: Boolean = true,
                    t: String = id, proof: String = token) =
            fetchPrivateSupportImageBytes(capability, show, t, proof, { scope }) { error("unexpected I/O") }
        assertNull(attempt(show = false))
        assertNull(attempt(capability = false))
        assertNull(attempt(scope = false))
        assertNull(attempt(t = "$id?legacy=1"))
        assertNull(attempt(t = "https://example.invalid"))
        assertNull(attempt(proof = "ms_" + "1".repeat(64)))
        assertNull(attempt(proof = "00000000-0000-0000-0000-000000000000"))
        assertNull(attempt(proof = "$token\n"))
    }

    @Test fun denialRedirectAndInvalidHeadersAreNotOpened() {
        val denied = listOf(203, 206, 302, 403, 404, 503).map { Reply(it, headers, markerFixture) }
        val altered = listOf(
            "Content-Length" to "262145", "Content-Length" to "5, 5", "Content-Type" to "image/jpg",
            "Cache-Control" to "public, no-store", "Cache-Control" to "private, max-age=86400",
            "Location" to "https://example.invalid", "Content-Range" to "bytes 0-4/5",
            "Content-Encoding" to "gzip", "Content-Disposition" to "inline",
        ).map { Reply(200, headers + it, markerFixture) } + Reply(200, headers - "Content-Length", markerFixture)
        for (reply in denied + altered) {
            var calls = 0
            assertNull(fetchPrivateSupportImageBytes(true, true, id, token, { true }) { calls++; reply })
            assertEquals(1, calls)
            assertEquals(0, reply.opens)
            assertEquals(1, reply.closes)
        }
    }

    @Test fun truncatedExtraNonJpegAndScopeLossAreRejected() {
        for (value in listOf(markerFixture.copyOf(4), markerFixture.copyOf(6), ByteArray(5))) {
            val reply = Reply(200, headers, value)
            assertNull(fetchPrivateSupportImageBytes(true, true, id, token, { true }) { reply })
            assertEquals(1, reply.closes)
        }
        var current = true
        val reply = Reply(200, headers, markerFixture) { current = false }
        assertNull(fetchPrivateSupportImageBytes(true, true, id, token, { current }) { reply })
        assertEquals(1, reply.closes)
        assertNull(fetchPrivateSupportImageBytes(true, true, id, token, { true }) {
            throw IOException("synthetic details never escape")
        })
    }
}
