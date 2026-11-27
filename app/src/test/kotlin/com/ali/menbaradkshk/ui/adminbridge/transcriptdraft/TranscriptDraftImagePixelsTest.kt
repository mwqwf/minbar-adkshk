package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptDraftImagePixelsTest {
    @Test fun actualBitmapCodecProducesAllowedFreshBaseline() {
        val source = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val output = ByteArrayOutputStream()
        check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        source.recycle()
        val pixels = prepareDraftImagePixels({ ByteArrayInputStream(output.toByteArray()) }, {})
        assertEquals(160 to 80, pixels.shape)
        assertNotNull(draftJpegShape(pixels.copyBytes()))
    }
    @Test fun revocationPreventsTouchingProvider() {
        assertThrows(IllegalStateException::class.java) {
            prepareDraftImagePixels({ error("would open original") }, { error("lease expired") })
        }
    }
}
