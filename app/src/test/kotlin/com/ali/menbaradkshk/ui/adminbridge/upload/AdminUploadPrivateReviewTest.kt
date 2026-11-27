package com.ali.menbaradkshk.ui.adminbridge.upload

import android.app.Application
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminUploadPrivateReviewTest {
    private fun ogg(packet: ByteArray): ByteArray = ByteArray(28 + packet.size).apply {
        "OggS".encodeToByteArray().copyInto(this)
        this[5] = 2
        this[26] = 1
        this[27] = packet.size.toByte()
        packet.copyInto(this, 28)
    }

    @Test fun identifiesOnlyCompleteRecognizedOggFirstPacket() {
        val opus = ByteArray(19).also {
            "OpusHead".encodeToByteArray().copyInto(it)
            it[8] = 1
        }
        assertEquals(ReviewedAudioContainer.Opus, readAudioContainer(ogg(opus)))
        val vorbis = ByteArray(30).also {
            it[0] = 1
            "vorbis".encodeToByteArray().copyInto(it, 1)
        }
        assertEquals(ReviewedAudioContainer.Vorbis, readAudioContainer(ogg(vorbis)))
        assertNull(readAudioContainer(ogg(opus).copyOf(35)))
        assertNull(readAudioContainer(ogg(opus).also { it[5] = 3 }))
        assertNull(readAudioContainer(ogg(opus).also { it[18] = 1 }))
        assertNull(readAudioContainer(ogg(ByteArray(19))))
    }

    @Test fun rejectWrongWavPrimitiveAndTruncatedFlac() {
        val wave = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".encodeToByteArray()).putInt(38).put("WAVEfmt ".encodeToByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".encodeToByteArray()).putInt(2).putShort(0).array()
        assertEquals(ReviewedAudioContainer.Wav, readAudioContainer(wave))
        assertNull(readAudioContainer(wave.copyOf(45)))
        assertNull(readAudioContainer(wave.copyOf().also { it[20] = 6 }))
        val flac = ByteArray(42).also {
            "fLaC".encodeToByteArray().copyInto(it)
            it[7] = 34
        }
        assertEquals(ReviewedAudioContainer.Flac, readAudioContainer(flac))
        assertNull(readAudioContainer(flac.copyOf(41)))
        assertNull(readAudioContainer("container-not-known.mp3".encodeToByteArray()))
    }

    @Test fun confirmOncePersistsAndCannotBeChangedOrCrossed() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val a = "a".repeat(64)
            val store = AdminUploadDraftStore(dir, a)
            val draft = store.prepare("lie.mp3", 3) { "abc".byteInputStream() }
            val confirmed = UploadStageIntent("عنوان", "قارئ", "وصف", "c", "s", "قسم", "فرع", "audio/opus")
            assertNull(store.confirmedIntent(draft))
            store.freezeConfirmedIntent(draft, confirmed)
            val reopened = AdminUploadDraftStore(dir, a)
            assertEquals(confirmed, reopened.confirmedIntent(draft))
            reopened.freezeConfirmedIntent(draft, confirmed)
            val altered = confirmed.copy(subcategoryId = "different")
            assertTrue(runCatching { reopened.freezeConfirmedIntent(draft, altered) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { AdminUploadDraftStore(dir, "b".repeat(64)).confirmedIntent(draft) }.isFailure)
            assertEquals(confirmed, reopened.confirmedIntent(draft))
            dir.resolve("admin-upload-drafts-v1/$a/${draft.id}/confirmed-upload").appendText("corrupt")
            assertTrue(runCatching { reopened.freezeConfirmedIntent(draft, altered) }.exceptionOrNull() is IOException)
        } finally { dir.deleteRecursively() }
    }
}
