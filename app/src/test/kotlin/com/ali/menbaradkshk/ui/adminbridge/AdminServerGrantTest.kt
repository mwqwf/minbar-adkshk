package com.ali.menbaradkshk.ui.adminbridge

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminServerGrantTest {
    private fun response(role: String) = JSONObject().put("who", "fixture@example.test").put("role", role)

    @Test fun privateLongCapabilityMustBeLiteralAndNeverEnablesDefaultBuild() {
        val account = "a".repeat(64)
        val token = "ms_" + "b".repeat(64)
        assertFalse(PRIVATE_LONG_ANDROID_ON)
        val blank = response("owner")
        assertFalse(requireNotNull(decodeAdminGrant(blank)).canStageLongLessonUploads)
        for (wrong in listOf("true", 1, JSONObject.NULL)) {
            assertFalse(requireNotNull(decodeAdminGrant(blank.put("canStageLongLessonUploads", wrong))).canStageLongLessonUploads)
        }
        val grant = requireNotNull(decodeAdminGrant(blank.put("canStageLongLessonUploads", true)))
        assertTrue(grant.canStageLongLessonUploads)
        assertFalse(privateLongUploadAllowed(PRIVATE_LONG_ANDROID_ON, grant.canStageLongLessonUploads, token, account))
        assertFalse(privateLongUploadAllowed(true, false, token, account))
        assertFalse(privateLongUploadAllowed(true, true, "other_$token", account))
        assertFalse(privateLongUploadAllowed(true, true, token, "bad"))
        assertTrue(privateLongUploadAllowed(true, true, token, account))
    }

    @Test fun imagePreviewRequiresLiteralAndRemainsOffInThisBuild() {
        val token = "ms_" + "b".repeat(64)
        val cap = "canPreviewPendingTranscriptImages"
        val blank = response("supervisor").put("canReadPendingTranscriptReview", true)
        for (wrong in listOf("true", 1, JSONObject.NULL)) {
            assertFalse(requireNotNull(decodeAdminGrant(blank.put(cap, wrong))).canPreviewPendingTranscriptImages)
        }
        val session = requireNotNull(decodeAdminGrant(blank.put(cap, true))).openVerifiedSession(token) {}
        assertTrue(session.hasServerCapability(AdminServerCapability.PreviewTranscriptImages))
        assertNull(session.transcriptImagePreview())
    }

    @Test fun publicationRequiresLiteralDesignatedOwnerGrantAndIsDefaultOff() {
        val token = "ms_" + "b".repeat(64)
        val cap = "canPublishGuardedTranscript"
        assertFalse(requireNotNull(decodeAdminGrant(response("supervisor").put(cap, true))).canPublishGuardedTranscript)
        assertFalse(requireNotNull(decodeAdminGrant(response("owner").put(cap, "true"))).canPublishGuardedTranscript)
        val owner = requireNotNull(decodeAdminGrant(response("owner").put(cap, true))).openVerifiedSession(token) {}
        assertTrue(owner.hasServerCapability(AdminServerCapability.PublishTranscript))
        assertFalse(owner.hasServerCapability(AdminServerCapability.AcceptTranscript))
        assertNull(owner.transcriptPublication())
    }

    private fun grantOnly(json: JSONObject) = decodeAdminGrant(json)?.copy(accountKey = "")

    @Test fun onlyServerBooleanCapabilityPlusOwnerEnablesInvites() {
        assertEquals(AdminGrant(AdminRole.Owner, false), grantOnly(response("owner")))
        assertEquals(AdminGrant(AdminRole.Owner, false),
            grantOnly(response("owner").put("canIssueInvites", "true")))
        assertEquals(AdminGrant(AdminRole.Owner, true),
            grantOnly(response("owner").put("canIssueInvites", true)))
        assertEquals(AdminGrant(AdminRole.Supervisor, false),
            grantOnly(response("supervisor").put("canIssueInvites", true)))
        assertEquals(AdminGrant(AdminRole.Supervisor, false), grantOnly(response("admin")))
        assertEquals(AdminGrant(AdminRole.Supervisor, false, true),
            grantOnly(response("admin").put("canEditLessonMetadata", true)))
        assertEquals(AdminGrant(AdminRole.Owner, false, false),
            grantOnly(response("owner").put("canEditLessonMetadata", "true")))
        assertEquals(AdminGrant(AdminRole.Supervisor, false, false, true),
            grantOnly(response("supervisor").put("canRestoreArchivedLesson", true)))
        assertEquals(AdminGrant(AdminRole.Owner, false),
            grantOnly(response("owner").put("canRestoreArchivedLesson", "true")))
    }

    @Test fun verifiedAccountStorageKeysAreStableAndDistinctAcrossActors() {
        val a = requireNotNull(decodeAdminGrant(response("owner")))
        val same = requireNotNull(decodeAdminGrant(response("owner").put("who", "FIXTURE@example.test")))
        val other = requireNotNull(decodeAdminGrant(response("owner").put("who", "other@example.test")))
        assertTrue(a.accountKey.matches(Regex("[a-f0-9]{64}")))
        assertEquals(a.accountKey, same.accountKey)
        assertNotEquals(a.accountKey, other.accountKey)
    }

    @Test fun suspendedOrUnknownAndInvalidIdentityCannotMountTheWorkspace() {
        assertNull(decodeAdminGrant(response("blocked").put("canIssueInvites", true)))
        assertNull(decodeAdminGrant(response("unknown")))
        assertNull(decodeAdminGrant(response("owner").put("who", "").put("canIssueInvites", true)))
    }
}
