package com.ali.menbaradkshk.media

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import com.ali.menbaradkshk.data.AssetPackSeeder
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LocalAudio
import com.ali.menbaradkshk.data.LocalStore
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(markerClass = [UnstableApi::class])
class PadUpgradePlaybackTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LocalStore

    @org.junit.Before fun reopenStore() {
        LocalStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        store = LocalStore.get(ctx)
    }
    private val body = "identical verified serving bytes"
    private val sha = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val id = "lesson-1"
    private fun audio(group: String, bytes: String = body) =
        File(ctx.cacheDir, "$group/serving/$sha.ogg").apply { parentFile!!.mkdirs(); writeText(bytes) }

    private fun lesson(id: String = this.id, bytes: Long = body.length.toLong()) = Lesson(
        id, "عنوان", "cat", "sub", "https://example.invalid/serving/$sha.ogg", 0,
        sha256 = sha, sizeBytes = bytes,
    )

    private fun index(id: String, file: File) {
        assertEquals(1, store.seedBundledDownloads(mapOf(id to Triple(file.path, sha, file.length()))))
    }

    @Test fun absentThenRelocatedSameHashRebindsTheIndexedPath() {
        val old = audio("old")
        val next = audio("new")
        index(id, old)
        var roots = emptyList<File>()
        assertNull(store.playableAudioSnapshot { roots }(id))
        assertTrue(old.isFile)
        assertEquals(0, AssetPackSeeder.seedAvailable(store, listOf(lesson()), roots))
        assertEquals(old.path, store.downloads()[id])
        roots = listOf(next.parentFile!!)
        // Even before the foreground seed callback, the same indexed identity resolves to B.
        assertEquals(next.path, store.playableAudioSnapshot { roots }(id)?.path)
        assertEquals(1, AssetPackSeeder.seedAvailable(store, listOf(lesson()), roots))
        assertEquals(next.path, store.downloads()[id])
        assertEquals(0, AssetPackSeeder.seedAvailable(store, listOf(lesson()), roots))
        assertTrue(old.isFile)
    }

    @Test fun reseedPreservesManualFilesAndDeletionAndRejectsWrongSizes() {
        val manual = File(ctx.cacheDir, "manual").apply { writeText(body) }
        assertTrue(store.publishDownload("manual", manual, sha))
        val old = audio("old")
        index("deleted", old)
        store.markUserDeletedDownload("deleted")
        val wrong = audio("wrong", "short")
        val good = audio("good")
        var lookups = 0
        val lookup = store.playableAudioSnapshot { lookups++; listOf(good.parentFile!!) }
        assertEquals(LocalAudio(manual.path), lookup("manual"))
        assertNull(lookup("deleted"))
        assertEquals(0, lookups)
        assertEquals(0, AssetPackSeeder.seedAvailable(store, listOf(lesson()), listOf(wrong.parentFile!!)))
        assertEquals(1, AssetPackSeeder.seedAvailable(store,
            listOf(lesson(), lesson("manual"), lesson("deleted")), listOf(good.parentFile!!)))
        assertEquals(manual.path, store.downloads()["manual"])
        assertEquals("manual", store.downloadMeta("manual")?.optString("src"))
        assertTrue("deleted" in store.userDeletedDownloadIds())
        assertEquals(old.path, store.downloads()["deleted"])
        assertNull(store.playableAudioSnapshot { listOf(good.parentFile!!) }("deleted"))
    }

    @Test fun playbackResolvesFreshAtMedia3OpenWithoutPinningAbsolutePath() {
        val old = audio("old")
        val next = audio("new")
        index(id, old)
        var roots = listOf(old.parentFile!!)
        val factory = PlayPackAudioSource.factory(DefaultDataSource.Factory(ctx)) {
            store.playableAudioSnapshot { roots }(it)
        }
        val local = store.playableAudioSnapshot { roots }(id)
        val prepared = PlaybackController.mediaItemFor(lesson(), local).localConfiguration!!
        assertTrue(PlayPackAudioSource.handles(prepared.uri))
        assertFalse(prepared.uri.toString().contains(old.parent.orEmpty()))
        assertNull(prepared.customCacheKey)
        roots = emptyList()
        // Old file still on disk is not playable; no path/HTTP fallback from a guarded open.
        val failure = assertThrows(DataSourceException::class.java) { read(factory, prepared.uri) }
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, failure.reason)
        assertTrue(old.isFile)
        assertTrue(old.delete())
        assertEquals(0, store.pruneDownloads(setOf(id)))
        assertEquals(old.path, store.downloads()[id])
        roots = listOf(next.parentFile!!)
        assertEquals(body, read(factory, prepared.uri))
        assertEquals(old.path, store.downloads()[id])
        store.markUserDeletedDownload(id)
        assertThrows(DataSourceException::class.java) { read(factory, prepared.uri) }
    }

    @Test fun onlyAnAbsolutePlayDirectoryCanServeAudio() {
        assertNull(AssetPackSeeder.servingDirectory(null))
        assertNull(AssetPackSeeder.servingDirectory(""))
        assertNull(AssetPackSeeder.servingDirectory("local-or-apk"))
        assertEquals(File(ctx.cacheDir, "serving"), AssetPackSeeder.servingDirectory(ctx.cacheDir.path))
    }

    @Test fun playlistUsesOneLookupUntilActualOpen() {
        val old = audio("old")
        index("alias-1", old)
        index("alias-2", old)
        var calls = 0
        val lookup = store.playableAudioSnapshot { calls++; listOf(old.parentFile!!) }
        assertNotNull(lookup("alias-1"))
        assertNotNull(lookup("alias-2"))
        assertEquals(1, calls)
    }

    @Test fun ordinaryManualAndNetworkSelectionKeepSeparateRoutes() {
        val manual = File(ctx.cacheDir, "manual").apply { writeText(body) }
        assertTrue(store.publishDownload(id, manual, sha))
        val local = store.playableAudioSnapshot { error("no Play lookup for manual") }(id)
        val manualItem = PlaybackController.mediaItemFor(lesson(), local).localConfiguration!!
        val remote = PlaybackController.mediaItemFor(lesson(), null).localConfiguration!!
        val factory = PlayPackAudioSource.factory(DefaultDataSource.Factory(ctx)) { error("not PAD") }
        assertEquals("file", manualItem.uri.scheme)
        assertEquals(body, read(factory, manualItem.uri))
        assertEquals("https", remote.uri.scheme)
        assertEquals(sha, remote.customCacheKey)
        assertEquals("ordinary", read(factory, Uri.parse("data:text/plain,ordinary")))
    }

    @Test fun missingWrongLengthAndChangedIdentityFailClosed() {
        val old = audio("old")
        val wrong = audio("wrong", "tiny")
        index(id, old)
        assertNull(store.playableAudioSnapshot { listOf(wrong.parentFile!!) }(id))
        val prepared = PlayPackAudioSource.uri(id, LocalAudio(old.path, sha))
        val factory = PlayPackAudioSource.factory(DefaultDataSource.Factory(ctx)) {
            LocalAudio(old.path, "b".repeat(64))
        }
        assertThrows(DataSourceException::class.java) { read(factory, prepared) }
        assertThrows(DataSourceException::class.java) {
            read(factory, Uri.parse("minbar-pad://lesson/$id/../../bogus"))
        }
    }

    private fun read(factory: DataSource.Factory, uri: Uri): String {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(uri))
            val buffer = ByteArray(200)
            val count = source.read(buffer, 0, buffer.size)
            return buffer.copyOf(count).decodeToString()
        } finally { source.close() }
    }
}
