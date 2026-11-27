package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdminUploadDraftStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun store(account: String = a, max: Long = 3) = AdminUploadDraftStore(directory.root, account, max)
    private fun accountPath(): File = File(directory.root, "admin-upload-drafts-v1/$a")
    private fun data() = "abc".byteInputStream()

    @Test fun explicitDraftPersistsAndIsStableAndAccountIsolated() {
        var opened = 0
        val current = store()
        assertTrue(current.list().isEmpty())
        assertEquals(0, opened)
        val first = current.prepare("re/c\\ording.wav", null) { opened++; data() }
        val replay = store()
        assertEquals("recording.wav", first.fileName)
        assertEquals(3, first.byteCount)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first.sha256)
        assertEquals(listOf(first), replay.list())
        assertEquals(first, replay.prepare("another.wav", 3) { data() })
        assertEquals(listOf(first), replay.list())
        assertTrue(store(b).list().isEmpty())
        assertEquals(1, opened)
        assertThrows(IllegalArgumentException::class.java) { store("../not-an-account") }
    }

    @Test fun capMeasuredBeforeWriteAndOversizeReportedSourceNeverOpened() {
        var opens = 0
        val s = store()
        assertThrows(DraftSizeException::class.java) {
            s.prepare("large", 4) { opens++; "1234".byteInputStream() }
        }
        assertEquals(0, opens)
        for (claimed in listOf(null, 1L)) {
            assertThrows(DraftSizeException::class.java) {
                s.prepare("wrong", claimed) { "1234".byteInputStream() }
            }
            assertTrue(accountPath().listFiles().orEmpty().isEmpty())
        }
        assertThrows(DraftSizeException::class.java) { s.prepare("empty", null) { "".byteInputStream() } }
    }

    @Test fun cancellationAndAudioValidationFailureLeaveCompletedOriginalsIntact() {
        val s = store()
        val kept = s.prepare("kept.wav", 3) { data() }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            s.prepare("cancelled.wav", 3, checkActive = {
                if (++checks == 3) throw CancellationException()
            }) { "def".byteInputStream() }
        }
        assertThrows(IOException::class.java) {
            s.prepare("not-audio.wav", 3, validateAudio = { throw IOException() }) { "def".byteInputStream() }
        }
        assertEquals(listOf(kept), s.list())
        assertEquals(setOf(kept.id), accountPath().listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun recoverTouchesOnlyOwnedFilesInRecognizedStagingDirectoryAndNoSymlinks() {
        val s = store()
        val keep = s.prepare("completed.wav", 3) { data() }
        val stale = File(accountPath(), ".${UUID.randomUUID()}.part").apply { mkdir() }
        File(stale, "original").writeText("stale")
        val unknown = File(accountPath(), ".abcdef.part").apply { mkdir() }
        File(unknown, "original").writeText("private")
        val neighbor = directory.newFolder("neighbor")
        File(neighbor, "original").writeText("do not touch")
        Files.createSymbolicLink(File(accountPath(), ".${UUID.randomUUID()}.part").toPath(), neighbor.toPath())
        s.recover()
        assertFalse(stale.exists())
        assertEquals("private", File(unknown, "original").readText())
        assertEquals("do not touch", File(neighbor, "original").readText())
        assertEquals(listOf(keep), s.list())
    }

    @Test fun corruptMetadataIsNeverPresentedAndNotDestroyedByRecovery() {
        val s = store()
        val complete = s.prepare("good", 3) { data() }
        val payload = File(File(accountPath(), complete.id), "original")
        payload.appendText("x")
        s.recover()
        assertTrue(s.list().isEmpty())
        assertEquals("abcx", payload.readText())
    }
}
