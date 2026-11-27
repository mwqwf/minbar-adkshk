package com.ali.menbaradkshk.data.privatesupport

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class PrivateSupportImageTransportTest {
    private val id = "spt_" + "4".repeat(32)
    private val credential = "sil_" + "7".repeat(64)
    private val body = byteArrayOf(-1, -40, 23, -1, -39)
    private val headers = listOf(
        "content-type" to "image/jpeg",
        "Content-Length" to "5",
        "CONTENT-DISPOSITION" to "attachment; filename=\"support-image.jpg\"",
        "Cache-Control" to "private, no-store",
        "X-Content-Type-Options" to "nosniff",
    )

    private class Raw(override val headers: List<Pair<String, String>>, val data: ByteArray,
                      override val status: Int = 200) : PrivateSupportImageRawExchange {
        var opened = 0
        var closed = 0
        override fun openBody() = ByteArrayInputStream(data).also { opened++ }
        override fun close() { closed++ }
    }

    private fun consume(open: (PrivateSupportImageCall) -> PrivateSupportImageRawExchange) =
        fetchPrivateSupportImageBytes(true, true, id, credential, { true }) {
            openPrivateSupportImageExchange(it, open)
        }

    @Test fun specificWirePacketAndPolicyArePresentedExactlyOnce() {
        var calls = 0
        val raw = Raw(headers, body)
        assertArrayEquals(body, consume { call ->
            calls++
            assertEquals("GET", call.method)
            assertEquals("https://minbar-api.mushafak.workers.dev/v2/private-support/threads/$id/image", call.url)
            assertEquals("Bearer $credential", call.requestHeader("authorization"))
            assertEquals("identity", call.requestHeader("Accept-Encoding"))
            assertEquals("image/jpeg", call.requestHeader("Accept"))
            assertEquals("no-store", call.requestHeader("CACHE-CONTROL"))
            assertNull(call.requestHeader("Cookie"))
            assertNull(call.requestHeader("Range"))
            assertNull(call.requestHeader("X-Device-Id"))
            assertFalse(call.profile.followRedirects)
            assertFalse(call.profile.retryFailures)
            assertFalse(call.profile.useCookies)
            assertFalse(call.profile.useCache)
            assertFalse(call.profile.useAuthenticator)
            assertEquals(4000, call.profile.connectionTimeoutMs)
            assertEquals(4000, call.profile.readTimeoutMs)
            assertEquals(12000, call.profile.entireCallTimeoutMs)
            assertFalse(call.toString().contains(credential))
            raw
        })
        assertEquals(1, calls)
        assertEquals(1, raw.opened)
        assertEquals(1, raw.closed)
    }

    @Test fun duplicatesCookiesAmbiguousFramingAndInvalidControlsCloseBeforeReading() {
        val unsafe = listOf(
            "content-LENGTH" to "5", "content-Type" to "image/jpeg",
            "cache-control" to "private", "Transfer-Encoding" to "chunked",
            "Set-Cookie" to "synthetic=k", "Location" to "https://example.invalid/",
            "alpha" to "x\r",
        )
        for (header in unsafe) {
            val raw = Raw(headers + header, body)
            assertNull(consume { raw })
            assertEquals(0, raw.opened)
            assertEquals(1, raw.closed)
        }
    }

    @Test fun redirectsDeniedAndFailuresNeverRetry() {
        for (status in listOf(301, 302, 303, 307, 308, 403)) {
            val raw = Raw(headers, body, status)
            var calls = 0
            assertNull(consume { calls++; raw })
            assertEquals(1, calls)
            assertEquals(0, raw.opened)
            assertEquals(1, raw.closed)
        }
        assertNull(consume { throw IOException("details " + credential) })
        assertNull(consume { throw SecurityException("details " + credential) })
    }

    @Test fun bodyReadAndCloseSecurityErrorsAreContained() {
        for (failOnRead in listOf(true, false)) {
            var closed = false
            val reply = object : PrivateSupportImageRawExchange {
                override val status = 200
                override val headers = this@PrivateSupportImageTransportTest.headers
                override fun openBody(): InputStream = object : ByteArrayInputStream(body) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (failOnRead) throw SecurityException("private $credential")
                        return super.read(bytes, offset, length)
                    }
                    override fun close() {
                        if (!failOnRead) throw SecurityException("private $credential")
                    }
                }
                override fun close() { closed = true }
            }
            assertNull(consume { reply })
            assertTrue(closed)
        }
    }
}
