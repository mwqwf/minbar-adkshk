package com.ali.menbaradkshk.ui.adminbridge.upload

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AdminOriginalPickerRuntimeTest {
    private class FixtureRegistry : ActivityResultRegistry() {
        val codes = mutableListOf<Int>()
        var inline: Intent? = null
        var fail = false
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            if (fail) throw IllegalStateException("No picker")
            val intent = input as Intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertEquals("audio/*", intent.type)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            codes += requestCode
            inline?.let { dispatchResult(requestCode, Activity.RESULT_OK, it) }
        }
        fun result(code: Int, data: Intent?, resultCode: Int = Activity.RESULT_OK) = dispatchResult(code, resultCode, data)
    }

    private val first = Uri.parse("content://minbar.fixture/first")
    private val second = Uri.parse("content://minbar.fixture/second")
    private fun offer(uri: Uri, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) = Intent().apply {
        data = uri
        addFlags(flags)
    }

    @Test fun freshRequestDrainsOldDuplicateWithoutTouchingNewSelection() {
        val registry = FixtureRegistry()
        val picker = AdminOriginalPicker(registry)
        val picked = mutableListOf<Pair<String, Uri>>()
        var route = "owner-a"
        fun launch() = picker.launch(route, { route }, {}, { k, u -> picked += k to u })
        assertEquals(AdminOriginalPicker.Launch.STARTED, launch())
        picker.revokeRoute()
        route = "owner-b"
        assertEquals(AdminOriginalPicker.Launch.BUSY, launch())
        val old = registry.codes.single()
        registry.result(old, offer(first))
        assertTrue(picked.isEmpty())
        assertEquals(AdminOriginalPicker.Launch.STARTED, launch())
        val fresh = registry.codes.last()
        assertNotEquals(old, fresh)
        registry.result(old, offer(first))
        assertTrue(picked.isEmpty())
        registry.result(fresh, offer(second))
        assertEquals(listOf("owner-b" to second), picked)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(registry.result(old, offer(first)))
        assertFalse(registry.result(fresh, offer(second)))
    }

    @Test fun matchingClipAndReadGrantAreRequiredAndRestoreIsNeverImplicit() {
        val registry = FixtureRegistry()
        val picker = AdminOriginalPicker(registry)
        val picked = mutableListOf<Uri>()
        fun launch() = picker.launch("same", { "same" }, {}, { _, u -> picked += u })
        val noRead = offer(first, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val differentClip = offer(first).apply { clipData = ClipData.newRawUri("other", second) }
        val moreClips = offer(first).apply {
            clipData = ClipData.newRawUri("first", first).apply { addItem(ClipData.Item(second)) }
        }
        val nested = offer(first).apply {
            clipData = ClipData("nested", arrayOf("audio/*"), ClipData.Item(null, null, Intent("fixture.nested"), first))
        }
        val nonContent = offer(Uri.parse("file:///synthetic.wav"))
        for (bad in listOf(noRead, differentClip, moreClips, nested, nonContent)) {
            assertEquals(AdminOriginalPicker.Launch.STARTED, launch())
            registry.result(registry.codes.last(), bad)
            assertTrue(picked.isEmpty())
        }
        assertEquals(AdminOriginalPicker.Launch.STARTED, launch())
        registry.result(registry.codes.last(), offer(first), Activity.RESULT_CANCELED)
        assertTrue(picked.isEmpty())
        assertEquals(AdminOriginalPicker.Launch.STARTED, launch())
        registry.result(registry.codes.last(), offer(second).apply { clipData = ClipData.newRawUri("same", second) })
        assertEquals(listOf(second), picked)
        picker.dispose()
        assertEquals(AdminOriginalPicker.Launch.FAILED, launch())
    }

    @Test fun synchronousResultIsNotErasedAndThrowLeavesNextAttemptPossible() {
        val registry = FixtureRegistry()
        val picker = AdminOriginalPicker(registry)
        val events = mutableListOf<String>()
        fun launch() = picker.launch("owner", { "owner" }, { events += "start" },
            { _, u -> events += "accepted:" + u.lastPathSegment })
        registry.fail = true
        assertEquals(AdminOriginalPicker.Launch.FAILED, launch())
        registry.fail = false
        registry.inline = offer(first)
        assertEquals(AdminOriginalPicker.Launch.STARTED, launch())
        assertEquals(listOf("start", "start", "accepted:first"), events)
    }

    @Test fun recreationCannotConsumePendingOldResultAndFreshPickerWorks() {
        val registry = FixtureRegistry()
        var count = 0
        val old = AdminOriginalPicker(registry)
        assertEquals(AdminOriginalPicker.Launch.STARTED, old.launch("a", { "a" }, {}, { _, _ -> count++ }))
        val priorRequest = registry.codes.last()
        old.dispose()
        val current = AdminOriginalPicker(registry)
        assertEquals(AdminOriginalPicker.Launch.STARTED, current.launch("a", { "a" }, {}, { _, _ -> count++ }))
        registry.result(priorRequest, offer(first))
        assertEquals(0, count)
        registry.result(registry.codes.last(), offer(second))
        assertEquals(1, count)
    }
}
