package com.ali.menbaradkshk.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class SupportRecordingFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val oldId = "d4529871-a"
    private val newId = "e6badf23-bee1-4acd-8ef2-cc3410b8f9bd"

    @Test fun recordedBytesSurviveCacheRemovalAfterControlledCommit() {
        val cache = folder.newFolder("cache")
        val private = folder.newFolder("no-backup")
        val source = File(cache, "voice_1234567890123.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val files = SupportRecordingFiles(private, cache)
        val durable = files.stage(source, newId)
        assertTrue(files.ownedDraft(durable, newId))
        assertTrue(source.exists())
        assertTrue(source.delete())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), durable.readBytes())
        files.removeOwned(durable, newId)
        assertFalse(durable.exists())
    }

    @Test fun rejectsUnownedFilesAliasesAndWrongDraftIdentity() {
        val cache = folder.newFolder("cache")
        val private = folder.newFolder("no-backup")
        val source = folder.newFile("voice_1.m4a").apply { writeText("outside") }
        val redirect = File(cache, "voice_2.m4a")
        Files.createSymbolicLink(redirect.toPath(), source.toPath())
        val legitimate = File(cache, "voice_3.m4a").apply { writeText("inside") }
        val files = SupportRecordingFiles(private, cache)
        assertThrows(IllegalArgumentException::class.java) { files.stage(source, oldId) }
        assertThrows(IllegalArgumentException::class.java) { files.stage(redirect, oldId) }
        val durable = files.stage(legitimate, oldId)
        files.removeOwned(durable, newId)
        files.removeOwned(redirect, newId)
        assertTrue(durable.exists())
        assertTrue(redirect.exists())
        assertTrue(source.exists())
    }

    @Test fun oversizedEmptyAndCompetingDraftCannotOverwriteOrDropBytes() {
        val cache = folder.newFolder("cache")
        val private = folder.newFolder("no-backup")
        val files = SupportRecordingFiles(private, cache)
        val empty = File(cache, "voice_1.m4a").apply { createNewFile() }
        val big = File(cache, "voice_2.m4a")
        RandomAccessFile(big, "rw").use { it.setLength(SUPPORT_AUDIO_MAX_BYTES + 1) }
        val source = File(cache, "voice_3.m4a").apply { writeText("original") }
        assertThrows(IllegalArgumentException::class.java) { files.stage(empty, oldId) }
        assertThrows(IllegalArgumentException::class.java) { files.stage(big, oldId) }
        val durable = files.stage(source, oldId)
        source.writeText("changed")
        assertThrows(IllegalArgumentException::class.java) { files.stage(source, oldId) }
        assertArrayEquals("original".encodeToByteArray(), durable.readBytes())
        assertTrue(empty.exists())
        assertTrue(big.exists())
    }

    @Test fun accountCleanupOnlyRemovesManagedScratchDraftAndTemporaryFiles() {
        val cache = folder.newFolder("cache")
        val private = folder.newFolder("no-backup")
        val unrelated = File(private, "keep.txt").apply { writeText("other") }
        val outsider = folder.newFile("do-not-delete")
        val scratch = File(cache, "voice_9.m4a").apply { writeText("uncopied") }
        val files = SupportRecordingFiles(private, cache)
        val durable = files.stage(scratch, oldId)
        val part = File(private, "draft_123.part").apply { writeText("incomplete") }
        files.clear()
        assertFalse(durable.exists())
        assertFalse(scratch.exists())
        assertFalse(part.exists())
        assertTrue(unrelated.exists())
        assertTrue(outsider.exists())
    }

    @Test fun preferenceFailureMayMutateItsMemoryButNotWorkerVisibleRows() {
        var preferencesMemory = listOf("previous")
        val outbox = CommittedSupportOutbox { preferencesMemory }
        assertThrows(IllegalStateException::class.java) {
            outbox.replace(listOf("uncommitted")) {
                preferencesMemory = listOf("uncommitted")
                false
            }
        }
        assertEquals(listOf("previous"), outbox.current())
        outbox.replace(listOf("confirmed")) { true }
        assertEquals(listOf("confirmed"), outbox.current())
        outbox.blockErasedRows()
        assertTrue(outbox.current().isEmpty())
    }
}
