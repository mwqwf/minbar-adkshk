package com.ali.menbaradkshk.adminsurface

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
class AdminSubmissionModerationTest {
    private fun entry(
        status: String = "pending", title: String = "درس", id: String = "test-id_1",
        audio: String = "private-recording-reference", rights: Int = 1,
    ) = JSONObject().put("id", id).put("title", title).put("status", status)
        .put("category_id", "c").put("subcategory_id", "s").put("audio_key", audio)
        .put("rights_confirmed", rights)

    private fun items(vararg rows: JSONObject) = JSONObject().put("items", JSONArray().apply {
        rows.forEach { put(it) }
    })

    @Test
    fun approvalUsesConfirmedOriginalValuesAndCurrentCategoryNames() = runBlocking {
        val row = entry()
        val snapshot = requireNotNull(parsePendingSubmission(row))
        assertFalse("private-recording-reference" in snapshot.toString())
        val paths = mutableListOf<String>()
        val command = prepareSubmissionCommand(snapshot, ModerationDecision.Approve,
            AdminWorkspaceApi { path ->
                paths += path
                when (path) {
                    SubmissionListPath -> items(row)
                    "/admin/categories" -> items(JSONObject().put("id", "c").put("name", "الفقه"))
                    "/admin/subcategories" -> items(JSONObject().put("id", "s").put("category_id", "c").put("name", "الصلاة"))
                    else -> error("Unexpected path")
                }
            })
        assertEquals(listOf(SubmissionListPath, "/admin/categories", "/admin/subcategories"), paths)
        assertEquals("/admin/submissions/test-id_1/approve", command.path)
        assertEquals("درس", command.body.getString("title"))
        assertEquals("c", command.body.getString("categoryId"))
        assertEquals("s", command.body.getString("subcategoryId"))
        assertEquals("الفقه", command.body.getString("categoryName"))
        assertEquals("الصلاة", command.body.getString("subcategoryName"))
        assertFalse(command.body.getBoolean("edited"))
        val copy = command.body
        copy.put("edited", true)
        assertFalse(command.body.getBoolean("edited"))
    }

    @Test
    fun reviewIsDisabledForUnsafePathsAndNoMediaAndRequiresRightsForApprove() {
        assertNull(parsePendingSubmission(entry(id = "../sessions")))
        assertNull(parsePendingSubmission(entry(id = "x%2Fa")))
        assertNull(parsePendingSubmission(entry(audio = "")))
        assertNull(parsePendingSubmission(entry(status = "approved")))
        assertFalse(requireNotNull(parsePendingSubmission(entry(rights = 0))).canApprove)
    }

    @Test
    fun staleStatusOrMediaStopsBeforePublishing() = runBlocking {
        val snapshot = requireNotNull(parsePendingSubmission(entry()))
        for (newRow in listOf(entry(status = "approved"), entry(audio = "new"), entry(title = "عنوان مختلف"))) {
            var reads = 0
            val result = runCatching { prepareSubmissionCommand(snapshot, ModerationDecision.Approve,
                AdminWorkspaceApi { path -> assertEquals(SubmissionListPath, path); reads++; items(newRow) }) }
            assertTrue(result.exceptionOrNull() is StaleSubmission)
            assertEquals(1, reads)
        }
    }

    @Test
    fun rejectUsesLiteralSingleEndpointAndBoundedReason() = runBlocking {
        val row = entry()
        val snapshot = requireNotNull(parsePendingSubmission(row))
        val fake = AdminWorkspaceApi { path -> assertEquals(SubmissionListPath, path); items(row) }
        val command = prepareSubmissionCommand(snapshot, ModerationDecision.Reject("  سبب واضح "), fake)
        assertEquals("/admin/submissions/test-id_1/reject", command.path)
        assertEquals(setOf("reason"), command.body.keys().asSequence().toSet())
        assertEquals("سبب واضح", command.body.getString("reason"))
        assertTrue(runCatching { prepareSubmissionCommand(snapshot, ModerationDecision.Reject(" "), fake) }.isFailure)
    }

    @Test
    fun orphanSectionCannotBeApproved() = runBlocking {
        val row = entry()
        val snapshot = requireNotNull(parsePendingSubmission(row))
        val result = runCatching {
            prepareSubmissionCommand(snapshot, ModerationDecision.Approve, AdminWorkspaceApi { path ->
                when (path) {
                    SubmissionListPath -> items(row)
                    "/admin/categories" -> items(JSONObject().put("id", "c").put("name", "الفقه"))
                    "/admin/subcategories" -> items(JSONObject().put("id", "s").put("category_id", "other").put("name", "فرع خاطئ"))
                    else -> error("Unexpected")
                }
            })
        }
        assertTrue(result.exceptionOrNull() is StaleSubmission)
    }
}
