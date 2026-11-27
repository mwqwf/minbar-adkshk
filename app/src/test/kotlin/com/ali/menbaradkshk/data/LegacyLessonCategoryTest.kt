package com.ali.menbaradkshk.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LegacyLessonCategoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LocalStore
    private val category = Category("parent", "Known parent", 34L)
    private val explicit = Category("explicit", "Explicit category", 35L)
    private val main = Subcategory("main", "Known series", category.id, 36L)
    private val dangling = Subcategory("dangling", "Dangling series", "absent", 37L)
    private val source = listOf(
        row("borrow-parent", "", main.id),
        row("preserve-explicit", explicit.id, main.id),
        row("do-not-rewrite-unknown-explicit", "external", main.id),
        row("no-subcategory", "", "missing"),
        row("no-published-parent", "", dangling.id),
    )

    @Before fun resetLocalStore() {
        for (name in listOf("minbar_native_preferences", "minbar_content_cache", "content_repo_v1", "FlutterSharedPreferences")) {
            check(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        LocalStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        store = LocalStore.get(ctx)
    }
    private fun row(id: String, category: String, subcategory: String) =
        Lesson(id, id, category, subcategory, "https://example.invalid/$id", 1L,
            sha256 = "a".repeat(64), sizeBytes = 3L)

    private fun rawDocument() = JSONObject()
        .put("counts", JSONObject().put("categories", 2).put("subcategories", 2).put("lessons", source.size))
        .put("categories", JSONArray(listOf(category, explicit).map { it.toJson().put("id", it.id) }))
        .put("subcategories", JSONArray(listOf(main, dangling).map { it.toJson().put("id", it.id) }))
        .put("lessons", JSONArray(source.map { it.toJson().put("id", it.id) }))

    private fun repo(packaged: Boolean = false) = ContentRepository(ctx, CatalogBootIo(
        asset = {
            if (!packaged || it != "catalog/snapshot.jz") throw IOException("fixture asset absent")
            ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { gz -> gz.write(rawDocument().toString().toByteArray(Charsets.UTF_8)) }
            }.toByteArray().inputStream()
        },
        probe = { throw IOException("offline fixture") }, online = { rawDocument() }, seed = {},
    ))

    private suspend fun assertNavigation(opened: ContentRepository) {
        val state = withTimeout(6_000L) { opened.state.first { !it.loading } }
        assertEquals(listOf("borrow-parent"), opened.lessonsForCategory(category.id).map(Lesson::id))
        val resolved = state.lessonById.getValue("borrow-parent")
        assertEquals(category.id, resolved.categoryId) // Names, colors and saved-only category visibility use this.
        assertEquals(source.first().copy(categoryId = category.id), resolved)
        assertEquals(source.drop(1), state.lessons.drop(1))
        assertEquals(3, opened.lessonsForSubcategory(main.id).size)
    }

    @Test fun offlineFirstInstallCanBulkDownloadLegacyLessonsFromTheirOwnCategory() = runBlocking {
        assertNavigation(repo(packaged = true))
        assertTrue(store.lessons().isEmpty())
    }

    @Test fun warmOfflineCacheGetsSameNavigationWithoutChangingStoredCatalogRows() = runBlocking {
        store.setCategories(listOf(category, explicit))
        store.setSubcategories(listOf(main, dangling))
        store.setLessons(source)
        assertNavigation(repo())
        assertEquals(source, store.lessons())
    }

    @Test fun completedFullSyncPublishesNavigationButPersistsUnmodifiedServerCategories() = runBlocking {
        val opened = repo()
        opened.refresh(force = true, deep = true)
        assertNavigation(opened)
        assertEquals(source, store.lessons())
    }
}
