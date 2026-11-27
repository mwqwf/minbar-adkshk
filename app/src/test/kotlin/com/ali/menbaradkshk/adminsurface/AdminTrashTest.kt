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
class AdminTrashTest {
    private fun lesson(
        id: String = "lesson_1", title: String = " درس محفوظ ", deleted: Long = 76,
        updated: Long = 80,
    ) = JSONObject().put("id", id).put("title", title)
        .put("deleted_at_ms", deleted).put("updated_at_ms", updated)
        .put("category_id", "main").put("subcategory_id", "child")
        .put("audio_url", "secret-media-reference")

    private fun array(vararg rows: JSONObject) = JSONArray().apply { rows.forEach { put(it) } }

    private fun archive(vararg lessons: JSONObject) = JSONObject().put("lessons", array(*lessons))
        .put("categories", array()).put("subcategories", array())

    private fun section(id: String, parent: String? = null, deleted: Long? = null) = JSONObject()
        .put("id", id).put("name", id).put("category_id", parent ?: JSONObject.NULL)
        .put("deleted_at_ms", deleted ?: JSONObject.NULL)

    @Test
    fun archiveReadsThreeBucketsWithoutExposingMediaOrEnablingSectionRestores() = runBlocking {
        val response = archive(lesson())
            .put("categories", array(section("main", deleted = 105)))
            .put("subcategories", array(section("child", parent = "main", deleted = 95)))
        val rows = loadRows(Page.TRASH, AdminWorkspaceRole.SUPERVISOR,
            AdminWorkspaceApi { path -> assertEquals("/admin/trash", path); response })
        assertEquals(listOf("main", "child", "درس محفوظ"), rows.map { it.title })
        assertTrue(rows.none { "secret-media-reference" in it.toString() })
        assertEquals(listOf(null, null, "lesson_1"), rows.map { it.archivedLesson?.id })
        assertTrue(readArchive(archive()).isEmpty())
        assertTrue(runCatching { readArchive(JSONObject()) }.isFailure)
    }

    @Test
    fun restoreCommandUsesOnlyGuardedSingleLessonEndpointAndExactArchiveGeneration() = runBlocking {
        val archive = archive(lesson())
        val row = requireNotNull(readArchive(archive).single().archivedLesson)
        val requested = mutableListOf<String>()
        val api = AdminWorkspaceApi { path ->
            requested += path
            when (path) {
                AdminTrashPath -> archive
                "/admin/categories" -> JSONObject().put("items", array(section("main")))
                "/admin/subcategories" -> JSONObject().put("items", array(section("child", parent = "main")))
                else -> error("Unexpected path")
            }
        }
        val result = prepareArchiveRestore(row, api)
        assertEquals(listOf(AdminTrashPath, "/admin/categories", "/admin/subcategories"), requested)
        assertEquals("/admin/lessons/lesson_1/restore-guarded", result.path)
        assertEquals(setOf("expectedDeletedAtMs", "expectedUpdatedAtMs", "expectedTitle",
            "expectedCategoryId", "expectedSubcategoryId"), result.body.keys().asSequence().toSet())
        assertEquals(76L, result.body.getLong("expectedDeletedAtMs"))
        assertEquals(80L, result.body.getLong("expectedUpdatedAtMs"))
        assertEquals(" درس محفوظ ", result.body.getString("expectedTitle"))
        result.body.put("expectedTitle", "unsafe mutation")
        assertEquals(" درس محفوظ ", result.body.getString("expectedTitle"))
    }

    @Test
    fun missingReDeletedDuplicatedOrChangedLessonCannotRestore() = runBlocking {
        val row = requireNotNull(readArchive(archive(lesson())).single().archivedLesson)
        for (changed in listOf(archive(), archive(lesson(deleted = 90, updated = 90)),
            archive(lesson(title = "اسم مختلف")), archive(lesson(), lesson()))) {
            val api = AdminWorkspaceApi { path -> assertEquals(AdminTrashPath, path); changed }
            assertTrue(runCatching { prepareArchiveRestore(row, api) }.exceptionOrNull() is ArchiveChanged)
        }
        assertNull(readArchive(archive(lesson(id = "../categories"))).single().archivedLesson)
    }

    @Test
    fun archivedOrMismatchedParentsDoNotAllowEvenSingleLessonRestore() = runBlocking {
        val archive = archive(lesson())
        val row = requireNotNull(readArchive(archive).single().archivedLesson)
        val combinations = listOf(
            section("main", deleted = 100) to section("child", parent = "main"),
            section("main") to section("child", parent = "other"),
            section("main") to section("child", parent = "main", deleted = 104),
        )
        for ((main, child) in combinations) {
            val api = AdminWorkspaceApi { path ->
                when (path) {
                    AdminTrashPath -> archive
                    "/admin/categories" -> JSONObject().put("items", array(main))
                    "/admin/subcategories" -> JSONObject().put("items", array(child))
                    else -> error("Unexpected")
                }
            }
            assertTrue(runCatching { prepareArchiveRestore(row, api) }.exceptionOrNull() is ArchiveParentsUnavailable)
        }
    }
}
