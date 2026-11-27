package com.ali.menbaradkshk.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class LocalBackupTest {
    private lateinit var store: LocalStore
    @Before fun init() {
        LocalStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        store = LocalStore.get(ApplicationProvider.getApplicationContext<Context>())
    }
    private fun backup(fields: JSONObject) = JSONObject().put("app", "menbaradkshk").put("version", 1)
        .put("data", fields).toString()

    @Test fun ownHistoricalExportRoundTripsAndCanonicalListArraysAlsoRestore() {
        store.toggleFavorite("lesson-a")
        store.setQuranLastAyah(25)
        val exported = store.exportBackup()
        assertEquals("[\"lesson-a\"]", JSONObject(exported).getJSONObject("data").get(LocalStore.KEY_FAVORITES))
        store.clearPersonalData()
        assertEquals(emptyList<String>(), store.favoriteIds())
        assertEquals(2, store.importBackup(exported))
        assertEquals(listOf("lesson-a"), store.favoriteIds())
        assertEquals(25, store.quranLastAyah())
        assertEquals(1, store.importBackup(backup(JSONObject().put(LocalStore.KEY_FAVORITES, JSONArray(listOf("lesson-b")))
            .put("not_a_backup_preference", JSONArray(listOf("ignored"))))))
        assertEquals(listOf("lesson-b"), store.favoriteIds())
        assertEquals(25, store.quranLastAyah())
    }

    @Test fun anyMalformedKnownFieldRejectsAllOtherwiseValidWrites() {
        store.toggleFavorite("original")
        store.setQuranLastAyah(25)
        for (bad in listOf(JSONArray(listOf(12)), "", JSONObject(), JSONObject.NULL)) {
            assertEquals(-1, store.importBackup(backup(JSONObject().put(LocalStore.KEY_FAVORITES, bad)
                .put(LocalStore.KEY_QURAN_LAST, 33))))
            assertEquals(listOf("original"), store.favoriteIds())
            assertEquals(25, store.quranLastAyah())
        }
        assertEquals(-1, store.importBackup(backup(JSONObject().put(LocalStore.KEY_FAVORITES, JSONArray(listOf("other")))
            .put(LocalStore.KEY_QURAN_LAST, JSONArray(listOf("wrong-key-type"))))))
        assertEquals(listOf("original"), store.favoriteIds())
    }
}
