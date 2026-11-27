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
class AdminUploadStageClientTest {
    private val data = "abc".toByteArray()
    private val draft = AdminUploadDraft("3ef43c85-cbe7-470e-a503-35b376c5ee11", "audio.ogg", 3L,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private val intent = UploadStageIntent("title", "", "", "c1", "s1", "cat", "sub", "audio/ogg")
    private fun response(revision: Int = 1, verified: Boolean? = null) =
        JSONObject().put("ok", true).put("draftId", draft.id).put("sourceSha256", draft.sha256)
            .put("sourceBytes", 3).put("revision", revision)
            .put("state", if (revision == 1) "reserved" else "committed_draft")
            .also { if (verified != null) it.put("sourceVerified", verified) }

    @Test fun exactCapabilityAndBoundAreRequired() {
        val grant = JSONObject().put("who", "fixture@example.test").put("role", "supervisor")
        assertNull(decodeAdminGrant(grant)?.stageUploadMaxBytes)
        grant.put("canStageLessonUploads", true).put("guardedLessonUploadMaxBytes", MAX_STAGE_BYTES)
        assertEquals(MAX_STAGE_BYTES, decodeAdminGrant(grant)?.stageUploadMaxBytes ?: -1)
        grant.put("guardedLessonUploadMaxBytes", MAX_STAGE_BYTES + 1)
        assertNull(decodeAdminGrant(grant)?.stageUploadMaxBytes)
        grant.put("guardedLessonUploadMaxBytes", MAX_STAGE_BYTES.toString())
        assertNull(decodeAdminGrant(grant)?.stageUploadMaxBytes)
        grant.put("guardedLessonUploadMaxBytes", MAX_STAGE_BYTES).put("canStageLessonUploads", "true")
        assertNull(decodeAdminGrant(grant)?.stageUploadMaxBytes)
    }

    @Test fun freshAttemptHasOnlyFourNarrowRequests() { runBlocking {
        val seen = mutableListOf<UploadStageStep>()
        val client = AdminUploadStageClient { step, id, body, mime ->
            assertEquals(draft.id, id)
            seen += step
            when (step) {
                UploadStageStep.Reserve -> {
                    val j = JSONObject(body!!.decodeToString())
                    assertEquals(10, j.length())
                    assertEquals("s1", j.getString("subcategoryId"))
                    assertEquals(draft.sha256, j.getString("sourceSha256"))
                    response()
                }
                UploadStageStep.Verify -> response(1, false)
                UploadStageStep.Source -> {
                    assertArrayEquals(data, body)
                    assertEquals("audio/ogg", mime)
                    response(1, true)
                }
                UploadStageStep.Commit -> {
                    val j = JSONObject(body!!.decodeToString())
                    assertEquals(3, j.length())
                    assertEquals(1, j.getInt("expectedRevision"))
                    response(2, true)
                }
            }
        }
        assertEquals(UploadStageReceipt(draft.id, draft.sha256, 3), client.submit(intent, draft, data))
        assertEquals(UploadStageStep.entries, seen)
    } }

    @Test fun resumesOnlyAfterMatchingReserveAndNeverResendsProvenSource() { runBlocking {
        val seen = mutableListOf<UploadStageStep>()
        val client = AdminUploadStageClient { step, _, _, _ ->
            seen += step
            when (step) {
                UploadStageStep.Reserve -> response()
                UploadStageStep.Verify -> response(1, true)
                UploadStageStep.Commit -> response(2, true)
                else -> error("Must not resend source")
            }
        }
        client.submit(intent, draft, data)
        assertEquals(listOf(UploadStageStep.Reserve, UploadStageStep.Verify, UploadStageStep.Commit), seen)
    } }

    @Test fun alreadyCommittedDoesNotSendOrCommitAgain() { runBlocking {
        val seen = mutableListOf<UploadStageStep>()
        val client = AdminUploadStageClient { step, _, _, _ ->
            seen += step
            when (step) {
                UploadStageStep.Reserve -> response(2)
                UploadStageStep.Verify -> response(2, true)
                else -> error("Must not mutate committed draft")
            }
        }
        client.submit(intent, draft, data)
        assertEquals(listOf(UploadStageStep.Reserve, UploadStageStep.Verify), seen)
    } }

    @Test fun forgedCoercedReceiptStopsBeforeSource() { runBlocking {
        var calls = 0
        val client = AdminUploadStageClient { step, _, _, _ ->
            calls++
            if (step == UploadStageStep.Reserve) response() else response(1, false).put("sourceBytes", "3")
        }
        assertTrue(runCatching { client.submit(intent, draft, data) }.exceptionOrNull() is IOException)
        assertEquals(2, calls)
    } }

    @Test fun tamperNeverCallsWireAndClientNeverRetriesFailure() { runBlocking {
        var calls = 0
        val client = AdminUploadStageClient { _, _, _, _ -> calls++; throw IOException("failure") }
        assertTrue(runCatching { client.submit(intent, draft, "xyz".toByteArray()) }.isFailure)
        assertEquals(0, calls)
        assertTrue(runCatching { client.submit(intent, draft, data) }.isFailure)
        assertEquals(1, calls)
    } }

    @Test fun snapshotRejectsSameSizeTamperingAndHonorsAccountAndServerCap() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val account = "a".repeat(64)
            val store = AdminUploadDraftStore(dir, account)
            val local = store.prepare("a.ogg", 3L) { data.inputStream() }
            assertTrue(store.isForAccount(account))
            assertFalse(store.isForAccount("b".repeat(64)))
            assertArrayEquals(data, store.stageSnapshot(local.id, 3).second)
            assertTrue(runCatching { store.stageSnapshot(local.id, 2) }.exceptionOrNull() is DraftSizeException)
            val file = dir.resolve("admin-upload-drafts-v1/$account/${local.id}/original")
            file.writeBytes("xyz".toByteArray())
            assertTrue(runCatching { store.stageSnapshot(local.id, 3) }.exceptionOrNull() is IOException)
        } finally { dir.deleteRecursively() }
    }
}
