package com.ali.menbaradkshk.adminsurface

import android.app.Application
import com.ali.menbaradkshk.ui.adminbridge.decodeAdminGrant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminModeratorSuspensionTest {
    private val member = ModeratorRosterItem("x@unit.test","supervisor")
    private val revision = "a".repeat(32)

    @Test fun explicitVerificationAndWriteAreSeparateAndExact() = runBlocking {
        val calls=mutableListOf<String>()
        var live=true
        val access=ModeratorSuspensionAccess({live}) { path,payload ->
            calls+=path
            if (path==MODERATOR_SUSPEND_VERIFY) {
                assertEquals(setOf("email"),payload.keys().asSequence().toSet())
                JSONObject().put("email",member.email).put("role",member.role).put("stamp",revision).put("changedAtMs",42)
            } else {
                assertEquals(setOf("email","expectedRole","expectedStamp","expectedChangedAtMs"),payload.keys().asSequence().toSet())
                assertEquals(revision,payload.getString("expectedStamp"))
                assertEquals(42L,payload.getLong("expectedChangedAtMs"))
                JSONObject().put("email",member.email).put("status","suspended")
            }
        }
        val snap=access.verify(member)
        assertEquals(listOf(MODERATOR_SUSPEND_VERIFY),calls)
        access.suspendVerified(snap)
        assertEquals(listOf(MODERATOR_SUSPEND_VERIFY,MODERATOR_SUSPEND_WRITE),calls)
        live=false
        assertTrue(runCatching { access.suspendVerified(snap) }.exceptionOrNull() is AdminAccessRejected)
        assertEquals(2,calls.size)
    }

    @Test fun malformedUntrustedProjectionOrOwnerCannotBeUsedAndCapabilityIsLiteral() = runBlocking {
        var called=0
        val access=ModeratorSuspensionAccess({true}) { _,_ ->
            called++
            JSONObject().put("email",member.email).put("role",member.role).put("stamp",revision).put("changedAtMs","42")
        }
        assertTrue(runCatching { access.verify(member.copy(role="owner")) }.isFailure)
        assertEquals(0,called)
        assertTrue(runCatching { access.verify(member) }.isFailure)
        assertEquals(1,called)
        val raw=JSONObject().put("who","owner@unit.test").put("role","owner").put("canSuspendModerators",true)
        assertEquals(true,decodeAdminGrant(raw)?.canSuspendModerators)
        assertEquals(false,decodeAdminGrant(raw.put("role","supervisor"))?.canSuspendModerators)
        assertEquals(false,decodeAdminGrant(raw.put("role","owner").put("canSuspendModerators","true"))?.canSuspendModerators)
    }
}
