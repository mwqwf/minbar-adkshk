package com.ali.menbaradkshk.adminsurface.review

import android.app.Application
import com.ali.menbaradkshk.adminsurface.AdminAccessRejected
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceRole
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
class AdminReviewFeedTest {
    private val viewer = "viewer@example.test"
    private fun rows(vararg raw: JSONObject) =
        JSONObject().put("items", JSONArray().apply { raw.forEach { put(it) } })

    private fun alert(type: String, time: Long, email: String = "", exclude: String = "", read: Boolean = false) =
        JSONObject().put("type", type).put("createdAtMs", time).put("email", email).put("excludeEmail", exclude)
            .put("title", "hidden-account").put("body", "private-code ABCD1234").put("refId", "hidden-reference")
            .put("readBy", JSONArray().put(if (read) viewer.uppercase() else "someone@example.test"))

    @Test
    fun feedbackCannotEvenRequestDataForSupervisor() = runBlocking {
        var calls = 0
        val attempted = runCatching {
            readFeedbackReview(AdminWorkspaceRole.SUPERVISOR, AdminWorkspaceApi { calls++; rows() })
        }
        assertTrue(attempted.exceptionOrNull() is AdminAccessRejected)
        assertEquals(0, calls)
    }

    @Test
    fun ownerFeedbackPreservesDefinedKindsAndOnlyRedactedNotes() = runBlocking {
        val older = JSONObject().put("kind", "copyright").put("created_at_ms", 100)
            .put("text", "الاقتباس بلا نسبة").put("device_id", "hidden-device")
        val newer = JSONObject().put("kind", "audio_issue").put("created_at_ms", 200)
            .put("text", "الصوت متقطع؛ mail@example.test ورقم +213 555 123 456 وهذا https://example.test/private")
            .put("contact", "hidden-contact").put("lesson_id", "hidden-lesson")
        val result = readFeedbackReview(AdminWorkspaceRole.OWNER, AdminWorkspaceApi { path ->
            assertEquals("/admin/feedback", path)
            rows(older, newer)
        })
        assertEquals(listOf("مشكلة صوت", "حقوق نشر"), result.map { it.title })
        val resultText = result.toString()
        assertTrue("الصوت متقطع" in resultText && "الاقتباس بلا نسبة" in resultText)
        for (unwanted in listOf("hidden-", "mail@", "555", "example.test", "/private")) assertFalse(unwanted in resultText)
    }

    @Test
    fun alertsEnforceAudienceGroundedStatusesAndOmitCredentialsAndRawFields() = runBlocking {
        val response = rows(
            alert("transcript", 100, viewer, read = true),
            alert("submission", 110),
            alert("submission", 200, email = "different@example.test"),
            alert("submission", 210, exclude = viewer.uppercase()),
            alert("owner_code", 220, viewer),
            alert("support", 230),
            alert("suspicious_scan", 240),
        ).put("me", viewer)
        val api = AdminWorkspaceApi { path -> assertEquals("/admin/alerts", path); response }
        val supervisor = readAdminAlerts(AdminWorkspaceRole.SUPERVISOR, api)
        assertEquals(listOf("مساهمة صوتية", "نصّ مشروح مقترَح"), supervisor.map { it.title })
        assertTrue(supervisor[0].description!!.startsWith("غير مقروء"))
        assertTrue(supervisor[1].description!!.startsWith("مقروء"))
        val owner = readAdminAlerts(AdminWorkspaceRole.OWNER, api)
        assertEquals(listOf("مراجعة الدروس المشتبه بها", "رسالة دعم", "مساهمة صوتية", "نصّ مشروح مقترَح"),
            owner.map { it.title })
        for (unwanted in listOf("example.test", "ABCD1234", "hidden-", "private-code")) assertFalse(unwanted in owner.toString())
    }

    @Test
    fun filtersOnlyAlreadyVisibleAlertsAndKeepsOrderWithoutAdditionalReads() = runBlocking {
        var reads = 0
        val result = readAdminAlerts(AdminWorkspaceRole.SUPERVISOR, AdminWorkspaceApi {
            assertEquals(AdminAlertsPath, it)
            reads++
            rows(alert("submission", 100), alert("transcript", 110, read = true),
                alert("engagement", 120), alert("support", 130), alert("owner_code", 140),
                alert("milestone", 150, email = "other@example.test")).put("me", viewer)
        })
        assertEquals(listOf("تفاعل المستمعين", "نصّ مشروح مقترَح", "مساهمة صوتية"),
            result.matchingAlertReadFilter(AlertReadFilter.All).map { it.title })
        assertEquals(listOf("تفاعل المستمعين", "مساهمة صوتية"),
            result.matchingAlertReadFilter(AlertReadFilter.Unread).map { it.title })
        assertEquals(listOf("نصّ مشروح مقترَح"),
            result.matchingAlertReadFilter(AlertReadFilter.Read).map { it.title })
        assertTrue(result.matchingAlertReadFilter(AlertReadFilter.Read)
            .matchingAlertReadFilter(AlertReadFilter.Unread).isEmpty())
        assertEquals(1, reads)
    }

    @Test
    fun genuinelyEmptyFeedAndMalformedAuthorizationEnvelopeAreDistinct() = runBlocking {
        val role = AdminWorkspaceRole.OWNER
        assertTrue(readAdminAlerts(role, AdminWorkspaceApi { rows().put("me", viewer) }).isEmpty())
        assertTrue(runCatching { readAdminAlerts(role, AdminWorkspaceApi { rows() }) }.isFailure)
        assertTrue(runCatching { readFeedbackReview(role, AdminWorkspaceApi { JSONObject() }) }.isFailure)
        assertTrue(runCatching {
            readAdminAlerts(role, AdminWorkspaceApi { rows(alert("submission", 100).apply { remove("readBy") }).put("me", viewer) })
        }.isFailure)
    }
}
