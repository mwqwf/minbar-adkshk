package com.ali.menbaradkshk.ui

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioShareStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sameTitleAndExtensionKeepDistinctStableBytes() {
        val cache = tmp.newFolder()
        val first = tmp.newFile("one.ogx").apply { writeText("one") }
        val second = tmp.newFile("two.ogg").apply { writeText("two") }
        val a = requireNotNull(stageAudioShare(cache, first, "الدرس 1"))
        val b = requireNotNull(stageAudioShare(cache, second, "الدرس 1"))
        assertEquals("الدرس 1.ogg", a.file.name)
        assertEquals(a.file.name, b.file.name)
        assertNotEquals(a.file.parentFile, b.file.parentFile)
        assertEquals("one", a.file.readText())
        assertEquals("two", b.file.readText())
        b.discard()
        assertEquals("one", a.file.readText())
        assertFalse(b.file.parentFile!!.exists())
    }

    @Test fun cleansOnlyAgedOwnedAttemptsAndDiscardsFailedCopies() {
        val cache = tmp.newFolder()
        val source = tmp.newFile("source.ogg").apply { writeText("voice") }
        val old = requireNotNull(stageAudioShare(cache, source, "old", now = 10L))
        val now = 48 * 60 * 60_000L
        val fresh = requireNotNull(stageAudioShare(cache, source, "fresh", now = now))
        val attempts = requireNotNull(fresh.file.parentFile?.parentFile)
        val unrelated = attempts.resolve("another-feature").apply { mkdir() }
        val sibling = cache.resolve("share").apply { mkdir() }.resolve("legacy.ogg").apply { writeText("legacy") }
        val empty = tmp.newFile("empty.ogg")
        assertNull(stageAudioShare(cache, empty, "failure", now = now))
        assertFalse(old.file.exists())
        assertTrue(fresh.file.exists())
        assertTrue(unrelated.exists())
        assertEquals("legacy", sibling.readText())
        assertEquals(setOf(fresh.file.parentFile, unrelated), attempts.listFiles()!!.toSet())
    }
}
