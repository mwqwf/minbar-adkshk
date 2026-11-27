package com.ali.menbaradkshk.ui.adminbridge

import android.app.Application
import com.ali.menbaradkshk.ui.adminbridge.upload.MAX_STAGE_BYTES
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminSessionCapabilityBindingTest {
    private val token = "ms_" + "a".repeat(64)

    @Test fun oneLiteralGrantCannotEnableAnyOtherActionAndClosesWithLease() {
        for (only in AdminServerCapability.entries) {
            val whoami = who("owner").put(only.wireName, true)
            val session = open(whoami)
            for (probe in AdminServerCapability.entries) {
                assertEquals("Grant $only must not enable $probe", only == probe, session.hasServerCapability(probe))
            }
            session.close()
            for (probe in AdminServerCapability.entries) assertFalse(session.hasServerCapability(probe))
        }
    }

    @Test fun missingFalseAndCoercedFlagsRemainDisallowed() {
        for (invalid in listOf("true", 1, false, JSONObject.NULL)) {
            val whoami = who("owner")
            AdminServerCapability.entries.forEach { whoami.put(it.wireName, invalid) }
            val session = open(whoami)
            for (cap in AdminServerCapability.entries) assertFalse(session.hasServerCapability(cap))
            assertNull(session.privateUploadMaxBytes)
        }
        val unavailable = open(who("owner"))
        assertNull(unavailable.transcriptPreviewReader())
        assertNull(unavailable.moderatorRosterAccess())
        assertNull(unavailable.moderatorSuspensionAccess())
        val oversized = open(who("owner").put("canStageLessonUploads", true)
            .put("guardedLessonUploadMaxBytes", MAX_STAGE_BYTES + 1))
        assertFalse(oversized.hasServerCapability(AdminServerCapability.StageSmall))
    }

    @Test fun readPanelAndRejectionStayIndependentAndOwnerOnlyCapsDoNotFollowSupervisor() {
        val assemblyOnly = open(who("owner").put("canAssembleLongLessonUploads", true))
        assertNull(assemblyOnly.transcriptPreviewReader())
        assertNull(assemblyOnly.moderatorRosterAccess())
        val readerOnly = open(who("owner").put("canReadPendingTranscriptReview", true))
        val reader = requireNotNull(readerOnly.transcriptPreviewReader())
        assertNotNull(reader)
        assertNull(readerOnly.transcriptRejection(reader))
        assertFalse(readerOnly.hasServerCapability(AdminServerCapability.AssembleLong))
        val supervisor = open(who("supervisor").put("canIssueInvites", true).put("canRenameSections", true)
            .put("canReadModeratorRoster", true).put("canSuspendModerators", true))
        for (cap in listOf(AdminServerCapability.Invite, AdminServerCapability.RenameSection, AdminServerCapability.ReadRoster, AdminServerCapability.SuspendModerator)) {
            assertFalse(supervisor.hasServerCapability(cap))
        }
    }

    private fun who(role: String) = JSONObject().put("who", "admin@example.invalid").put("role", role)
        .put("guardedLessonUploadMaxBytes", MAX_STAGE_BYTES)
    private fun open(whoami: JSONObject) = requireNotNull(decodeAdminGrant(whoami)).openVerifiedSession(token) {
        error("No network or auth update in this regression")
    }
}
