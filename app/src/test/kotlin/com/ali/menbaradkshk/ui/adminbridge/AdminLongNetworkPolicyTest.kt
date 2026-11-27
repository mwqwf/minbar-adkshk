package com.ali.menbaradkshk.ui.adminbridge

import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class AdminLongNetworkPolicyTest {
    @Test fun exactRoutesDoNotAllowAlternateOriginOrMediaPolicy() {
        val path = "/admin/private-long-drafts/01d60c03-a988-41a5-956e-8ebd9a0ed0a5"
        val begin = LongUploadRequest(LongUploadMethod.POST, path, "application/json", byteArrayOf(1))
        val part = LongUploadRequest(LongUploadMethod.PUT, "$path/parts/12", "application/octet-stream", byteArrayOf(1))
        val complete = LongUploadRequest(LongUploadMethod.POST, "$path/complete", null, null)
        checkLongNetworkRequest(begin)
        checkLongNetworkRequest(part)
        checkLongNetworkRequest(complete)
        for (request in listOf(
            begin.copy(path = "https://example.test$path"),
            begin.copy(path = "$path?external=1"),
            begin.copy(followRedirects = true),
            begin.copy(maxResponseBytes = 8193),
            part.copy(path = "$path/parts/13"),
            part.copy(contentType = "application/json"),
            complete.copy(body = byteArrayOf()),
        )) assertThrows(IOException::class.java) { checkLongNetworkRequest(request) }
    }

    @Test fun closingSessionCancelsEveryRegisteredCallAndForbidsRegistration() {
        val calls = AdminLongNetworkCalls()
        val req = Request.Builder().url("https://example.test/never-opened").build()
        val client = OkHttpClient()
        val first = client.newCall(req)
        val second = client.newCall(req)
        assertTrue(calls.add(first))
        assertTrue(calls.add(second))
        calls.remove(second)
        calls.close()
        assertTrue(first.isCanceled())
        assertFalse(second.isCanceled())
        assertFalse(calls.add(client.newCall(req)))
    }

    @Test fun disabledBuildFailsBeforeGuardOrConnection() = runBlocking {
        val path = "/admin/private-long-drafts/01d60c03-a988-41a5-956e-8ebd9a0ed0a5"
        val request = LongUploadRequest(LongUploadMethod.GET, path, null, null)
        var beforeOpen = 0
        val result = runCatching {
            AdminLongNetwork.exchange(request, "ms_" + "f".repeat(64), AdminLongNetworkCalls()) {
                beforeOpen++
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(0, beforeOpen)
    }
}
