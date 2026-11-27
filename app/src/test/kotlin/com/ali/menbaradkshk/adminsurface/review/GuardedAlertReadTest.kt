package com.ali.menbaradkshk.adminsurface.review

import android.app.Application
import com.ali.menbaradkshk.adminsurface.AdminAccessRejected
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceRole
import com.ali.menbaradkshk.ui.adminbridge.decodeAdminGrant
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
class GuardedAlertReadTest {
    private val id = "al_" + "0".repeat(19) + "1"
    private val at = 1795355400000L
    private fun row(kind:String="submission", read:Boolean=false)=JSONObject().put("id",id)
        .put("createdAtMs",at).put("kind",kind).put("read",read)
    private fun feed(item:JSONObject)=JSONObject().put("items",JSONArray().put(item))

    @Test fun onlyExplicitChosenScopedSnapshotCanBeSent() = runBlocking {
        val seen=mutableListOf<String>()
        val api=GuardedAlertReadAccess(AdminWorkspaceRole.SUPERVISOR,{true}) { path,body ->
            seen+=path
            if(path==GUARDED_ALERT_FEED) { assertNull(body); feed(row()) }
            else {
                assertEquals(setOf("id","kind","createdAtMs"),requireNotNull(body).keys().asSequence().toSet())
                assertEquals(id,body.getString("id"))
                assertEquals(at,body.getLong("createdAtMs"))
                row(read=true)
            }
        }
        val item=api.rows().single()
        assertEquals("مساهمة صوتية",item.title)
        assertEquals(listOf(GUARDED_ALERT_FEED),seen)
        api.mark(requireNotNull(item.guardedAlert))
        assertEquals(listOf(GUARDED_ALERT_FEED,GUARDED_ALERT_MARK),seen)
    }

    @Test fun hiddenOrExtraPayloadAndRevokedLeaseFailClosed() = runBlocking {
        val notOwner=GuardedAlertReadAccess(AdminWorkspaceRole.SUPERVISOR,{true}) { _,_ -> feed(row("support")) }
        assertTrue(notOwner.rows().isEmpty())
        val privateLeak=GuardedAlertReadAccess(AdminWorkspaceRole.OWNER,{true}) { _,_ -> feed(row().put("readBy","private")) }
        assertTrue(runCatching{ privateLeak.rows() }.isFailure)
        var live=true
        val revoked=GuardedAlertReadAccess(AdminWorkspaceRole.OWNER,{live}) { _,_ -> live=false; feed(row()) }
        assertTrue(runCatching{ revoked.rows() }.exceptionOrNull() is AdminAccessRejected)
        val raw=JSONObject().put("who","a@unit.test").put("role","supervisor").put("canMarkGuardedAlertRead","true")
        assertEquals(false,decodeAdminGrant(raw)?.canMarkGuardedAlertRead)
        assertEquals(true,decodeAdminGrant(raw.put("canMarkGuardedAlertRead",true))?.canMarkGuardedAlertRead)
    }
}
