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
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WarmCatalogUpgradeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LocalStore

    @Before fun isolateFixturePreferences() {
        for (name in listOf("minbar_native_preferences", "minbar_content_cache", "content_repo_v1", "FlutterSharedPreferences")) {
            check(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        LocalStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        store = LocalStore.get(ctx)
    }
    private val category = Category("c", "before category", 10)
    private val subcategory = Subcategory("s", "before subcategory", "c", 11)
    private val same = row("shared-manual", "a")
    private val deleted = row("shared-user-deleted", "b")
    private val withdrawn = row("previous-only", "c")
    private val added = row("successor-only", "d")
    private val before = listOf(same, deleted, withdrawn)
    private val after = listOf(same, deleted, added)
    private fun row(id: String, mark: String) = Lesson(id, "title $id", "c", "s",
        "https://example.invalid/$id.ogg", 101, sha256 = mark.repeat(64), sizeBytes = 4L)
    private fun stamp(value: Int = 20, count: Int = 3) = JSONObject()
        .put("categoriesCount", 1).put("subcategoriesCount", 1).put("lessonsCount", count)
        .put("categoriesUpdatedAtMs", value).put("subcategoriesUpdatedAtMs", value).put("lessonsUpdatedAtMs", value)
    private fun online(snapshot: List<Lesson>, t: Int, renamed: Boolean = false) = JSONObject()
        .put("counts", JSONObject().put("categories", 1).put("subcategories", 1).put("lessons", snapshot.size))
        .put("categories", JSONArray().put(JSONObject().put("id", category.id)
            .put("name", if (renamed) "after category" else category.name)
            .put("createdAt", category.createdAtMs).put("updatedAtMs", t)))
        .put("subcategories", JSONArray().put(JSONObject().put("id", subcategory.id)
            .put("name", subcategory.name).put("categoryId", category.id)
            .put("createdAt", subcategory.createdAtMs).put("updatedAtMs", t)))
        .put("lessons", JSONArray(snapshot.map { it.toJson().put("id", it.id).put("updatedAtMs", t) }))
    private fun compress(j: JSONObject) = ByteArrayOutputStream().also { buffer ->
        GZIPOutputStream(buffer).use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
    }.toByteArray()
    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assets(
        next: JSONObject = online(after, 30, renamed = true),
        lineage: Boolean = true,
        corrupt: Boolean = false,
    ): Map<String, ByteArray> {
        val old = compress(online(before, 20))
        val new = compress(next)
        return buildMap {
            put("catalog/snapshot.jz", new)
            if (lineage) {
                put("catalog/upgrade-predecessor.jz", old)
                put("catalog/upgrade-lineage.json", JSONObject().put("format", "minbar-catalog-successor-v1")
                    .put("predecessorSha256", hash(old))
                    .put("successorSha256", if (corrupt) "a".repeat(64) else hash(new))
                    .toString().toByteArray(Charsets.UTF_8))
            }
        }
    }
    private fun warm(rows: List<Lesson> = before, mark: Int? = 20, currentCategory: Category = category, fields: Int? = 2) {
        store.setCategories(listOf(currentCategory))
        store.setSubcategories(listOf(subcategory))
        store.setLessons(rows.reversed()) // ordering of equivalent persisted models is not lineage.
        store.setLastSyncMs(99L)
        if (mark != null) ctx.getSharedPreferences("content_repo_v1", Context.MODE_PRIVATE).edit()
            .putInt("mark_categories", 1).putInt("mark_subcategories", 1).putInt("mark_lessons", rows.size)
            .putLong("mark_updated_ms", mark.toLong()).putLong("mark_categories_updated_ms", mark.toLong())
            .putLong("mark_subcategories_updated_ms", mark.toLong())
            .apply { if (fields != null) putInt("cache_fields_version", fields) }.commit()
    }
    private fun repo(
        packaged: Map<String, ByteArray>,
        probe: suspend () -> JSONObject = { throw IOException("no signal") },
        online: suspend () -> JSONObject = { throw IOException("no signal") },
        seed: suspend (List<Lesson>) -> Unit = {},
    ) = ContentRepository(ctx, CatalogBootIo(
        asset = { (packaged[it] ?: throw IOException("fixture asset absent")).inputStream() },
        probe = probe, online = online, seed = seed,
    ))
    private suspend fun ready(repo: ContentRepository) = withTimeout(6_000L) {
        repo.state.first { !it.loading }
    }
    private fun File.serving(lesson: Lesson): File = File(this, "${lesson.sha256}.ogg").apply { writeText("OGG!") }

    @Test fun matchedOfflineUpgradeShowsNewCatalogThenFindsLivePackWithoutDamagingLibrary() = runBlocking {
        warm()
        val oldRoot = File(ctx.cacheDir, "old/serving").apply { mkdirs() }
        val newRoot = File(ctx.cacheDir, "new/serving").apply { mkdirs() }
        val oldFile = oldRoot.serving(withdrawn)
        val manualFile = File(ctx.filesDir, "manual.ogg").apply { writeText("HOME") }
        assertTrue(store.publishDownload(same.id, manualFile, same.sha256))
        store.seedBundledDownloads(mapOf(withdrawn.id to Triple(oldFile.absolutePath, withdrawn.sha256, 4L)))
        store.markUserDeletedDownload(deleted.id)
        store.setPosition(withdrawn.id, 1500L)
        store.addBookmark(withdrawn.id, 111L, "keep")
        store.toggleFavorite(withdrawn.id)
        val noOnline = AtomicInteger()
        var roots = emptyList<File>()
        val opened = repo(assets(), online = { noOnline.incrementAndGet(); throw IOException("offline") },
            seed = { AssetPackSeeder.seedAvailable(store, it, roots) })
        val initial = ready(opened)
        assertTrue(initial.offline && initial.bundledUpgrade)
        assertEquals("after category", initial.categories.single().name)
        assertEquals(after.map(Lesson::id).toSet(), initial.lessonById.keys)
        opened.refresh(force = true, deep = true)
        assertEquals(0, noOnline.get()) // never moved to an unverified stale CDN body
        assertTrue(opened.state.value.bundledUpgrade)
        assertNull(store.playableAudioSnapshot { roots }(withdrawn.id))
        assertNull(store.playableAudioSnapshot { roots }(added.id))

        newRoot.serving(same); newRoot.serving(deleted); val next = newRoot.serving(added)
        roots = listOf(newRoot)
        val reopened = repo(assets(), seed = { AssetPackSeeder.seedAvailable(store, it, roots) })
        assertEquals(after.map(Lesson::id).toSet(), ready(reopened).lessonById.keys)
        withTimeout(6_000L) {
            while (store.playableAudioSnapshot { roots }(added.id)?.path != next.absolutePath) {
                kotlinx.coroutines.delay(20)
            }
        }
        assertEquals(manualFile.absolutePath, store.playableAudioSnapshot { roots }(same.id)?.path)
        assertEquals("HOME", manualFile.readText())
        assertNull(store.playableAudioSnapshot { roots }(deleted.id))
        assertTrue(oldFile.isFile) // Play owns this stale absolute path; don't delete it.
        assertTrue(store.favoriteIds().contains(withdrawn.id))
        assertEquals(1500L, store.positions()[withdrawn.id])
        assertEquals("keep", store.bookmarks(withdrawn.id).single().note)
        assertEquals(99L, store.lastSyncMs())
        assertEquals(before.map(Lesson::id).toSet(), store.lessons().map(Lesson::id).toSet())
    }

    @Test fun newerSavedOnlineCatalogNeverLosesToEmbeddedOne() = runBlocking {
        val newer = after + row("newer-online", "f")
        warm(newer, 55)
        val selected = ready(repo(assets()))
        assertFalse(selected.bundledUpgrade)
        assertEquals(newer.map(Lesson::id).toSet(), selected.lessonById.keys)
    }

    @Test fun newerMarksOrUnmatchedRowsNeverQualifyEvenWhenCountsCoincide() = runBlocking {
        warm(before, 55)
        assertFalse(ready(repo(assets())).bundledUpgrade)
        warm(before, 20, category.copy(name = "edited online title"))
        assertFalse(ready(repo(assets())).bundledUpgrade)
        warm(before.dropLast(1) + row("unknown-row", "f"), 20)
        assertFalse(ready(repo(assets())).bundledUpgrade)
    }

    @Test fun missingLineageWrongDigestAndRollbackFailClosed() = runBlocking {
        warm()
        assertFalse(ready(repo(assets(lineage = false))).bundledUpgrade)
        assertFalse(ready(repo(assets(corrupt = true))).bundledUpgrade)
        assertFalse(ready(repo(assets(next = online(after, 19)))).bundledUpgrade)
    }

    @Test fun firstInstallStillReadsOrdinaryBundleWithNoLineage() = runBlocking {
        val selected = ready(repo(assets(lineage = false)))
        assertFalse(selected.bundledUpgrade)
        assertTrue(selected.offline)
        assertEquals(after.map(Lesson::id).toSet(), selected.lessonById.keys)
    }

    @Test fun previewRejectsStaleBodyAndReconcilesOnlyToMatchingFreshProbe() = runBlocking {
        warm()
        val newer = after + row("verified-online", "e")
        var body = online(before, 20)
        val r = repo(assets(), probe = { stamp(40, newer.size) }, online = { body })
        assertTrue(ready(r).bundledUpgrade)
        r.refresh(force = true, deep = true)
        assertTrue(r.state.value.bundledUpgrade)
        assertTrue(store.lessons().any { it.id == withdrawn.id })
        body = online(newer, 40, renamed = true)
        r.refresh(force = true, deep = true)
        assertFalse(r.state.value.bundledUpgrade)
        assertFalse(r.state.value.offline)
        assertEquals(newer.map(Lesson::id).toSet(), store.lessons().map(Lesson::id).toSet())
    }

    @Test fun legacyCacheWithoutGainFieldsRefetchesOnceEvenWhenProbeMatches() = runBlocking {
        warm(fields = null) // a v40-era cache: marks match the server but rows were saved without gainDb
        val gained = before.map { it.copy(gainDb = -6.0) }
        val fetches = AtomicInteger()
        val r = repo(assets(lineage = false), probe = { stamp(20, before.size) },
            online = { fetches.incrementAndGet(); online(gained, 20) })
        ready(r)
        r.refresh(force = true)
        assertEquals(1, fetches.get())
        assertTrue(store.lessons().all { it.gainDb == -6.0 })
        assertEquals(ContentRepository.CACHE_FIELDS_VERSION,
            ctx.getSharedPreferences("content_repo_v1", Context.MODE_PRIVATE).getInt("cache_fields_version", 0))
    }

    @Test fun currentCacheWithMatchingProbeDoesNotRefetch() = runBlocking {
        warm()
        val fetches = AtomicInteger()
        val r = repo(assets(lineage = false), probe = { stamp(20, before.size) },
            online = { fetches.incrementAndGet(); online(before, 20) })
        ready(r)
        r.refresh(force = true)
        assertEquals(0, fetches.get())
    }
}
