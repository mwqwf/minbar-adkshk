package com.ali.menbaradkshk.adminsurface

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminWorkspaceDataTest {
    @Test
    fun supervisorCannotAttemptOwnerDestination() = runBlocking {
        var calls = 0
        val fake = AdminWorkspaceApi { calls++; JSONObject() }
        for (page in listOf(Page.SUSPICIONS, Page.AUDIT)) {
            val result = runCatching { loadRows(page, AdminWorkspaceRole.SUPERVISOR, fake) }
            assertTrue(result.exceptionOrNull() is AdminAccessRejected)
        }
        assertEquals(0, calls)
    }

    @Test
    fun submissionsUseRealEndpointAndDoNotExposeCredentialsFromResponse() = runBlocking {
        var requested = ""
        val fake = AdminWorkspaceApi { path ->
            requested = path
            JSONObject("""{"items":[
                {"title":"منشور","status":"approved","created_at_ms":900},
                {"title":"معلّق","status":"pending","created_at_ms":500,"fcm_token":"test-do-not-display"}
            ]}""")
        }
        val result = loadRows(Page.SUBMISSIONS, AdminWorkspaceRole.SUPERVISOR, fake)
        assertEquals("/admin/submissions?status=all&limit=500", requested)
        assertEquals(listOf("معلّق", "منشور"), result.map { it.title })
        assertEquals(listOf("بانتظار المراجعة", "مقبول"), result.map { it.description })
        assertTrue(result.none { "test-do-not-display" in it.toString() })
    }

    @Test
    fun missingStatsDoesNotPretendToBeZero() = runBlocking {
        val incomplete = AdminWorkspaceApi { JSONObject("""{"lessons":2}""") }
        val result = runCatching { loadRows(Page.STATS, AdminWorkspaceRole.OWNER, incomplete) }
        assertTrue(result.isFailure)
    }

    @Test
    fun genuineEmptyListAndMalformedListAreDistinct() = runBlocking {
        val role = AdminWorkspaceRole.SUPERVISOR
        val empty = loadRows(Page.LESSONS, role, AdminWorkspaceApi { JSONObject("""{"items":[]}""") })
        assertTrue(empty.isEmpty())
        val missing = runCatching { loadRows(Page.LESSONS, role, AdminWorkspaceApi { JSONObject() }) }
        assertTrue(missing.isFailure)
    }
}
