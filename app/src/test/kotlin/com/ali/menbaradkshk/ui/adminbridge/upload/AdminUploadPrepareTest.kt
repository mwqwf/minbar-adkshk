package com.ali.menbaradkshk.ui.adminbridge.upload

import android.app.Application
import com.ali.menbaradkshk.ui.adminbridge.decodeAdminGrant
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminUploadPrepareTest {
    private val draft = AdminUploadDraft("3ef43c85-cbe7-470e-a503-35b376c5ee11", "test.wav", 3, "a".repeat(64))
    private val proof = UploadStageReceipt(draft.id, draft.sha256, draft.byteCount)
    private val id = "93651248-256c-4dd2-9f0f-4b436d448eb3"

    private fun response() = JSONObject().put("ok", true).put("draftId", draft.id)
        .put("sourceSha256", draft.sha256).put("sourceBytes", draft.byteCount).put("sourceVerified", true)
    private fun committed() = response().put("state", "committed_draft").put("revision", 2)
    private fun prepared() = response().put("state", "prepared").put("revision", 1)
        .put("draftRevision", 2).put("preparationId", id)

    @Test fun exactCapabilityBooleanOnly() {
        val grant = JSONObject().put("who", "owner@example.test").put("role", "owner")
        assertFalse(decodeAdminGrant(grant)!!.canAdoptStagedLesson)
        assertFalse(decodeAdminGrant(grant.put("canAdoptStagedLesson", "true"))!!.canAdoptStagedLesson)
        assertTrue(decodeAdminGrant(grant.put("canAdoptStagedLesson", true))!!.canAdoptStagedLesson)
    }

    @Test fun separateProofAndOnlyExactCommand() = runBlocking {
        val calls = mutableListOf<String>()
        val result = prepareVerifiedDraft(draft, proof, {
            calls += "get"; committed()
        }, {
            calls += "post"
            assertEquals(3, it.length())
            assertEquals(2, it.getInt("expectedRevision"))
            assertEquals(draft.sha256, it.getString("sourceSha256"))
            assertEquals(draft.byteCount, it.getLong("sourceBytes"))
            prepared()
        })
        assertEquals(listOf("get", "post"), calls)
        assertEquals(PrivateLessonPreparation(proof, id), result)
    }

    @Test fun staleOrCoercedGetNeverPosts() = runBlocking {
        for (invalid in listOf(committed().put("sourceVerified", "true"), committed().put("revision", 1),
            committed().put("sourceBytes", "3"), committed().put("draftId", "other"))) {
            var posted = false
            assertTrue(runCatching {
                prepareVerifiedDraft(draft, proof, { invalid }, { posted = true; prepared() })
            }.exceptionOrNull() is IOException)
            assertFalse(posted)
        }
    }

    @Test fun mismatchedReceiptNeverConfirmsOrRetries() = runBlocking {
        for (invalid in listOf(prepared().put("preparationId", "not-a-uuid"), prepared().put("draftRevision", 1),
            prepared().put("state", "published"), prepared().put("revision", "1"), prepared().put("sourceSha256", "b".repeat(64)))) {
            var calls = 0
            assertTrue(runCatching {
                prepareVerifiedDraft(draft, proof, { committed() }, { calls++; invalid })
            }.exceptionOrNull() is IOException)
            assertEquals(1, calls)
        }
    }
}
