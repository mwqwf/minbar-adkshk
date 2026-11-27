package com.ali.menbaradkshk.adminsurface.support

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class GuardedSupportInboxTest {
    private val threadId = "st_1711111111111_112233"
    private val emptyList = JSONObject().put("items", JSONArray()).put("truncated", false)
    private fun message() = JSONObject().put("threadId", threadId).put("truncated", false)
        .put("items", JSONArray().put(JSONObject().put("fromOwner", false).put("text", "رسالة\nمستمع")
            .put("createdAtMs", 1).put("hasAudio", true).put("hasImages", false)))

    @Test fun acceptsOnlyFixedExactPaths() {
        assertTrue(isGuardedSupportPath(SUPPORT_INBOX_PATH))
        assertTrue(isGuardedSupportPath("$SUPPORT_INBOX_PATH/$threadId"))
        for (bad in listOf("../$threadId", "$threadId?foo=bar", "$threadId/reply",
            "st_bad", "%2F", "ST_" + "a".repeat(20))) assertFalse(isGuardedSupportPath("$SUPPORT_INBOX_PATH/$bad"))
    }

    @Test fun decoderFailsClosedForKeysTypesWrongThreadAndHiddenControls() {
        assertEquals("رسالة\nمستمع", decodeSupportMessages(message(), threadId).items.single().text)
        assertEquals(true, decodeSupportMessages(message(), threadId).items.single().hasAudio)
        assertTrue(runCatching { decodeSupportMessages(message().put("threadId", "st_" + "a".repeat(20)), threadId) }.isFailure)
        assertTrue(runCatching { decodeSupportThreads(JSONObject(emptyList.toString()).put("truncated", "false")) }.isFailure)
        for ((key, value) in listOf("audioPath" to "unexpected", "hasAudio" to "true", "text" to "hidden\u202E")) {
            val r = message()
            r.getJSONArray("items").getJSONObject(0).put(key, value)
            assertTrue(runCatching { decodeSupportMessages(r, threadId) }.isFailure)
        }
    }

    @Test fun invalidationDuringReadAndUnsafePathsDoNotExposeData() = runBlocking {
        var available = true
        val requested = mutableListOf<String>()
        val client = GuardedSupportInboxAccess({ available }) { path ->
            requested += path
            if (path == SUPPORT_INBOX_PATH) emptyList else {
                available = false
                message()
            }
        }
        assertTrue(client.threads().items.isEmpty())
        assertTrue(runCatching { client.messages("../$threadId") }.isFailure)
        assertEquals(listOf(SUPPORT_INBOX_PATH), requested)
        assertTrue(runCatching { client.messages(threadId) }.isFailure)
        assertTrue(runCatching { client.threads() }.isFailure)
        assertEquals(listOf(SUPPORT_INBOX_PATH, "$SUPPORT_INBOX_PATH/$threadId"), requested)
    }
}
