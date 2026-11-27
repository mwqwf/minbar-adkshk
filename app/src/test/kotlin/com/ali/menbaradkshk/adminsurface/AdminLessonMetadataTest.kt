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
class AdminLessonMetadataTest {
    private fun lesson(
        id: String = "verified_1", title: String = "العنوان القديم", revision: Long = 15,
        deleted: Long? = null,
    ) = JSONObject().put("id", id).put("title", title).put("updated_at_ms", revision)
        .put("category_id", "base").put("subcategory_id", "baseChild")
        .put("deleted_at_ms", deleted ?: JSONObject.NULL).put("views", 500)
        .put("audio_url", "not-in-the-write")

    private fun liveResponse(row: JSONObject) = JSONObject()
        .put("id", row.getString("id")).put("title", row.getString("title"))
        .put("categoryId", row.getString("category_id")).put("subcategoryId", row.getString("subcategory_id"))
        .put("updatedAtMs", row.getLong("updated_at_ms"))

    private fun entry(id: String, name: String, parent: String? = null, deleted: Long? = null) =
        JSONObject().put("id", id).put("name", name).put("category_id", parent ?: JSONObject.NULL)
            .put("deleted_at_ms", deleted ?: JSONObject.NULL)

    private fun items(vararg raw: JSONObject) = JSONObject().put("items", JSONArray().apply { raw.forEach { put(it) } })

    @Test
    fun renameWritesOnlyTitleWithExactGuardsAndNoLegacyFallback() = runBlocking {
        val row = lesson()
        val from = requireNotNull(liveLessonMetadata(row))
        val paths = mutableListOf<String>()
        val command = prepareLessonMetadataCommand(from, LessonMetadataEdit.Rename(" عنوان جديد "),
            AdminWorkspaceApi { path -> paths += path; liveResponse(row) })
        assertEquals(listOf("/admin/lessons/verified_1/metadata"), paths)
        assertEquals("/admin/lessons/verified_1/metadata", command.path)
        assertEquals(setOf("expectedUpdatedAtMs", "expectedTitle", "expectedCategoryId", "expectedSubcategoryId", "title"),
            command.body.keys().asSequence().toSet())
        assertEquals("عنوان جديد", command.body.getString("title"))
        assertEquals(15L, command.body.getLong("expectedUpdatedAtMs"))
        assertEquals("العنوان القديم", command.body.getString("expectedTitle"))
        command.body.put("title", "غير معتمد")
        assertEquals("عنوان جديد", command.body.getString("title"))
    }

    @Test
    fun oldRevisionSameMillisecondAndSoftDeletedAnyDenyCommand() = runBlocking {
        val from = requireNotNull(liveLessonMetadata(lesson()))
        for (row in listOf(lesson(title = "عدل مشرف آخر"), lesson(revision = 16))) {
            val result = runCatching { prepareLessonMetadataCommand(from, LessonMetadataEdit.Rename("جديد"), AdminWorkspaceApi { liveResponse(row) }) }
            assertTrue(result.exceptionOrNull() is LessonMetadataStale)
        }
        assertNull(liveLessonMetadata(lesson(id = "a%2Fb")))
        assertNull(liveLessonMetadata(lesson(revision = -1)))
        assertNull(liveLessonMetadata(lesson(deleted = 20)))
        assertNotNull(liveLessonMetadata(lesson(revision = 0)))
        assertNull(liveLessonMetadata(lesson().apply { remove("deleted_at_ms") }))
    }

    @Test
    fun serverComparisonPreservesRawLegacyValuesExactly() = runBlocking {
        val row = lesson(title = "  عنوان قديم ")
            .put("category_id", "").put("subcategory_id", "")
        val from = requireNotNull(liveLessonMetadata(row))
        val command = prepareLessonMetadataCommand(from, LessonMetadataEdit.Rename("جديد"),
            AdminWorkspaceApi { liveResponse(row) })
        assertEquals("  عنوان قديم ", command.body.getString("expectedTitle"))
        assertEquals("", command.body.getString("expectedCategoryId"))
    }

    @Test
    fun moveUsesLiveParentAndChildNamesAndNoTitleOrReorder() = runBlocking {
        val row = lesson()
        val from = requireNotNull(liveLessonMetadata(row))
        val api = AdminWorkspaceApi { path ->
            when (path) {
                "/admin/lessons/verified_1/metadata" -> liveResponse(row)
                "/admin/categories" -> items(entry("to", "قسم"))
                "/admin/subcategories" -> items(entry("toChild", "فرع", "to"))
                else -> error("Unexpected path")
            }
        }
        val destination = fetchLessonDestinations(api).single()
        val command = prepareLessonMetadataCommand(from, LessonMetadataEdit.Move(destination), api)
        assertEquals("to", command.body.getString("categoryId"))
        assertEquals("toChild", command.body.getString("subcategoryId"))
        assertEquals("قسم", command.body.getString("expectedNewCategoryName"))
        assertEquals("فرع", command.body.getString("expectedNewSubcategoryName"))
        assertFalse(command.body.has("title"))
        assertFalse(command.body.has("sort"))
        assertFalse(command.body.has("createdAtMs"))
    }

    @Test
    fun moveCannotTargetDeletedWrongParentOrRenamedDestination() = runBlocking {
        val row = lesson()
        val from = requireNotNull(liveLessonMetadata(row))
        val selection = LiveLessonDestination("to", "قسم", "toChild", "فرع")
        for (child in listOf(entry("toChild", "فرع", "to", 14), entry("toChild", "فرع", "other"), entry("toChild", "اسم آخر", "to"))) {
            val api = AdminWorkspaceApi { path ->
                when (path) {
                    "/admin/lessons/verified_1/metadata" -> liveResponse(row)
                    "/admin/categories" -> items(entry("to", "قسم"))
                    "/admin/subcategories" -> items(child)
                    else -> error("Unexpected path")
                }
            }
            assertTrue(runCatching { prepareLessonMetadataCommand(from, LessonMetadataEdit.Move(selection), api) }.exceptionOrNull() is LessonMetadataStale)
        }
    }
}
