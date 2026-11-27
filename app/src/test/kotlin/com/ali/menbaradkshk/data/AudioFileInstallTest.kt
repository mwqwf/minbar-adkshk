package com.ali.menbaradkshk.data

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFileInstallTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedRenameLeavesIndexedAudioAndPartialUntouched() {
        val old = folder.newFile("lesson.ogg").apply { writeText("old audio") }
        val partial = folder.newFile("lesson.ogg.part").apply { writeText("new audio") }
        assertThrows(IOException::class.java) {
            installVerifiedAudio(partial, old, rename = { _, _ -> false }) {
                fail("A failed rename must not publish anything")
                true
            }
        }
        assertEquals("old audio", old.readText())
        assertEquals("new audio", partial.readText())
    }

    @Test fun failedPublicationLeavesOldAudioAndMakesVerifiedPartialRetriable() {
        val old = folder.newFile("lesson.mp3").apply { writeText("old audio") }
        val partial = folder.newFile("lesson.ogg.part").apply { writeText("new audio") }
        val nominalTarget = File(folder.root, "lesson.ogg")
        assertThrows(IOException::class.java) {
            installVerifiedAudio(partial, nominalTarget) {
                assertEquals("old audio", old.readText())
                assertEquals("new audio", it.readText())
                false
            }
        }
        assertEquals("old audio", old.readText())
        assertEquals("new audio", partial.readText())
        assertFalse(nominalTarget.exists())
    }

    @Test fun successfulPublicationUsesDistinctVerifiedPathEvenWithSameExtension() {
        val old = folder.newFile("lesson.ogg").apply { writeText("old audio") }
        val partial = folder.newFile("lesson.ogg.part").apply { writeText("new audio") }
        var published: File? = null
        val result = installVerifiedAudio(partial, old) {
            assertNotEquals(old, it)
            assertEquals("old audio", old.readText())
            assertEquals("new audio", it.readText())
            published = it
            true
        }
        assertEquals(published, result)
        assertFalse(partial.exists())
        assertEquals("ogg", result.extension)
        assertEquals("old audio", old.readText())
    }
}
