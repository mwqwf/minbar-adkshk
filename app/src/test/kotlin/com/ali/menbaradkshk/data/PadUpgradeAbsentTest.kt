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
class PadUpgradeAbsentTest {
    @Test fun indexedPackPathAloneCannotCertifyOfflinePlayback() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore.get(ctx)
        val sha = "a".repeat(64)
        val old = File(ctx.cacheDir, "old/serving/$sha.ogg").apply {
            parentFile!!.mkdirs()
            writeText("present but not in a live Play pack")
        }
        assertEquals(1, store.seedBundledDownloads(mapOf("lesson" to Triple(old.path, sha, old.length()))))
        assertTrue(old.isFile)
        assertNull(store.localAudioPath("lesson"))
        assertEquals(old.path, store.downloads()["lesson"])
    }
}
