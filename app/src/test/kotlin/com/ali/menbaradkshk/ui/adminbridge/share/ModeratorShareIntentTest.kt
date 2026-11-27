package com.ali.menbaradkshk.ui.adminbridge.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ModeratorShareIntentTest {
    private val own = "com.ali.menbaradkshk"
    private val source = Uri.parse("content://recorder.example/recording")
    private fun share(uri: Uri = source) = Intent(Intent.ACTION_SEND).setType("audio/ogg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_STREAM, uri)

    @Test fun exactStreamOrMatchingClipOnly() {
        assertEquals(source, submittedAudio(share(), own))
        val matching = share().apply { clipData = ClipData.newRawUri("audio", source) }
        assertEquals(source, submittedAudio(matching, own))
        val clipOnly = share().apply { removeExtra(Intent.EXTRA_STREAM); clipData = ClipData.newRawUri("audio", source) }
        assertEquals(source, submittedAudio(clipOnly, own))
        assertNull(submittedAudio(matching.apply {
            clipData = ClipData.newRawUri("different", Uri.parse("content://recorder.example/other"))
        }, own))
    }

    @Test fun rejectsBroaderOrAmbiguousOffersWithoutQueryingAnyProvider() {
        val invalid = listOf(
            share(Uri.parse("file:///tmp/a.ogg")),
            share(Uri.parse("content://$own.fileprovider/file")),
            share().setType("image/jpeg"),
            share().setAction(Intent.ACTION_SEND_MULTIPLE),
            share().setFlags(0),
            share().addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            share().addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            share().putExtra(Intent.EXTRA_TEXT, "mixed"),
            share().apply { clipData = ClipData.newRawUri("two", source).apply { addItem(ClipData.Item(source)) } },
        )
        for (intent in invalid) assertNull(submittedAudio(intent, own))
    }
}
