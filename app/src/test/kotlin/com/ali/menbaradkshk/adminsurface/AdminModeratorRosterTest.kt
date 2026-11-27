package com.ali.menbaradkshk.adminsurface

import android.app.Application
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
class AdminModeratorRosterTest {
    private fun member(email: String = "a@unit.test",role: String = "owner") = JSONObject()
        .put("email",email).put("role",role)
    private fun res(vararg members: JSONObject) = JSONObject().put("truncated",false)
        .put("items",JSONArray().apply { members.forEach { put(it) } })

    @Test fun exactProjectionAndLiteralOwnerCapability() {
        val roster = parseModeratorRoster(res(member(),member("b@unit.test","removed")))
        assertEquals(listOf("owner","removed"),roster.items.map { it.role })
        for (r in listOf(res(member().put("presence",true)),res(member(),member()),res(member("bad@test\u202e")),
            res(member(role="wild")),res(member()).put("truncated","false"),
            res(*(0..50).map { member("a$it@unit.test") }.toTypedArray()))) {
            assertTrue(runCatching { parseModeratorRoster(r) }.isFailure)
        }
        for (role in listOf("owner","supervisor")) {
            val json=JSONObject().put("role",role).put("who","a@unit.test").put("canReadModeratorRoster",true)
            assertEquals(role=="owner",decodeAdminGrant(json)?.canReadModeratorRoster)
            assertEquals(false,decodeAdminGrant(json.put("canReadModeratorRoster","true"))?.canReadModeratorRoster)
        }
    }

    @Test fun revokingSessionPreventsRequestAndDelivery() = runBlocking {
        var live=false
        var calls=0
        val access=ModeratorRosterAccess({live}) {
            calls++
            live=false
            res(member())
        }
        assertTrue(runCatching { access.request() }.exceptionOrNull() is AdminAccessRejected)
        assertEquals(0,calls)
        live=true
        assertTrue(runCatching { access.request() }.exceptionOrNull() is AdminAccessRejected)
        assertEquals(1,calls)
    }
}
