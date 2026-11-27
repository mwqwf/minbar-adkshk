package com.ali.menbaradkshk.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LibraryPackStoreTest {
    private fun reopen(context: Context): LocalStore {
        LocalStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        return LocalStore.get(context)
    }

    @Test fun modePersistsIndependentlyOfTheCdnAutoFlags() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val initial = reopen(ctx)
        initial.setAutoDownloadEnabled(false)
        initial.setSmartDownloadEnabled(true)
        assertEquals(LibraryPackMode.AUTOMATIC, initial.libraryPackMode())
        assertTrue(initial.setLibraryPackMode(LibraryPackMode.PAUSED))
        val paused = reopen(ctx)
        assertEquals(LibraryPackMode.PAUSED, paused.libraryPackMode())
        assertTrue(paused.setLibraryPackMode(LibraryPackMode.DISABLED))
        val disabled = reopen(ctx)
        assertEquals(LibraryPackMode.DISABLED, disabled.libraryPackMode())
        assertFalse(disabled.autoDownloadEnabled())
        assertTrue(disabled.smartDownloadEnabled())
    }

    @Test fun seedingNeverReplacesAnIntactIndependentDownload() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = reopen(ctx)
        val old = File(ctx.cacheDir, "manual.ogg").apply { writeText("existing manual") }
        val pack = File(ctx.cacheDir, "serving/${"b".repeat(64)}.ogg").apply {
            parentFile!!.mkdirs()
            writeText("new pack")
        }
        val manualSha = "a".repeat(64)
        val packSha = "b".repeat(64)
        assertTrue(store.publishDownload("manual", old, manualSha))
        val result = store.seedBundledDownloads(mapOf(
            "manual" to Triple(pack.absolutePath, packSha, pack.length()),
            "unseeded" to Triple(pack.absolutePath, packSha, pack.length()),
        ))
        assertEquals(1, result)
        assertEquals(old.absolutePath, store.localAudioPath("manual"))
        assertEquals("existing manual", old.readText())
        assertEquals(manualSha, store.downloadMeta("manual")?.optString("sha"))
        assertEquals("manual", store.downloadMeta("manual")?.optString("src"))
        assertEquals(pack.absolutePath, store.playableAudioSnapshot { listOf(pack.parentFile!!) }("unseeded")?.path)
        assertEquals("bundle", store.downloadMeta("unseeded")?.optString("src"))
    }
    @Test fun removalBlocksSeedingAcrossReopenUntilExplicitlyRequested() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val initial = reopen(ctx)
        val file = File(ctx.cacheDir, "serving/${"c".repeat(64)}.ogg").apply {
            parentFile!!.mkdirs()
            writeText("Pack still physically present")
        }
        val candidate = mapOf("new" to Triple(file.absolutePath, "c".repeat(64), file.length()))
        assertTrue(initial.setLibraryPackMode(LibraryPackMode.DISABLED))
        // Disabling alone still preserves available packs.
        assertEquals(1, initial.seedBundledDownloads(candidate))
        val manual = File(ctx.cacheDir, "manual-spared-by-pack-removal.ogg").apply { writeText("Manual file") }
        assertTrue(initial.publishDownload("manual", manual, "d".repeat(64)))
        assertTrue(initial.beginLibraryPackRemoval())
        assertNull(initial.localAudioPath("new"))
        assertEquals(manual.absolutePath, initial.localAudioPath("manual"))
        // Race: a catalog scan captured the file before the removal started.
        assertEquals(0, initial.seedBundledDownloads(candidate))
        val reopened = reopen(ctx)
        assertEquals(LibraryPackMode.DISABLED, reopened.libraryPackMode())
        assertEquals(0, reopened.seedBundledDownloads(candidate))
        assertTrue(reopened.allowManualLibraryPackSeeding())
        assertEquals(LibraryPackMode.DISABLED, reopened.libraryPackMode())
        assertEquals(1, reopened.seedBundledDownloads(candidate))
        // A late independent cleanup must not erase the newly explicit/reseeded pack entry.
        assertEquals(1, reopened.pruneDownloads(emptySet(), spareBundled = true))
        assertEquals(file.absolutePath, reopened.playableAudioSnapshot { listOf(file.parentFile!!) }("new")?.path)
        assertNull(reopened.localAudioPath("manual"))
        reopened.removeDownload("new")
        assertTrue(reopened.beginLibraryPackRemoval())
        assertTrue(reopened.setLibraryPackMode(LibraryPackMode.AUTOMATIC))
        assertEquals(1, reopened.seedBundledDownloads(candidate))
    }
    @Test fun removalRetrySurvivesResetWhileExistingManualPartialsFollowTheCallerPolicy() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = reopen(ctx)
        val directory = File(ctx.filesDir, "lessons").apply { mkdirs() }
        val queued = File(directory, "queued.ogg.part").apply { writeText("resume") }
        val queueMarker = File(directory, "queued.ogg.part.src").apply { writeText("source") }
        val orphan = File(directory, "other.ogg.part").apply { writeText("orphan") }
        assertTrue(store.beginLibraryPackRemoval())
        store.pruneDownloads(emptySet(), sparePartialsOf = setOf("queued"), spareBundled = true)
        assertEquals("resume", queued.readText())
        assertEquals("source", queueMarker.readText())
        assertFalse(orphan.exists())
        assertTrue(reopen(ctx).hasUnconfirmedPackRemoval())
        store.pruneDownloads(emptySet(), spareBundled = true)
        store.clearPersonalData()
        assertFalse(queued.exists())
        assertFalse(queueMarker.exists())
        assertTrue(reopen(ctx).hasUnconfirmedPackRemoval())
        store.confirmLibraryPackRemoval()
        assertFalse(reopen(ctx).hasUnconfirmedPackRemoval())
        assertEquals(LibraryPackMode.DISABLED, store.libraryPackMode())
        assertTrue(store.beginLibraryPackRemoval())
        assertTrue(store.allowManualLibraryPackSeeding())
        assertFalse(reopen(ctx).hasUnconfirmedPackRemoval())
    }

}
