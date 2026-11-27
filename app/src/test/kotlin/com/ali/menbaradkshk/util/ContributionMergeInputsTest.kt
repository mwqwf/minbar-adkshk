package com.ali.menbaradkshk.util

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ContributionMergeInputsTest {
    @Test fun unknownAndLyingHintsDoNotReplaceActualCumulativeCap() = withCache { cache ->
        val cap = 100L * 1024L * 1024L
        val provider = FakeProvider(listOf(cap - 1, 1), listOf(-1, 0))
        val staged = stageContributionMerge(cache, provider.inputs(), cap) {}
        try {
            assertEquals(listOf(cap - 1, 1), staged.parts.map { it.length() })
            assertEquals(listOf("part_0.m4a", "part_1.mp3"), staged.parts.map { it.name })
            assertEquals(0, provider.hintReads)
            assertTrue(provider.streams.all { it.closed })
            assertTrue(provider.streams.all { it.maxRequested <= 64 * 1024 })
            assertEquals(1, provider.streams.last().singleReads)
        } finally { staged.directory.deleteRecursively() }
    }

    @Test fun overCapNeverWritesTheExtraByteAndCleansOnlyItsOwnAttempt() = withCache { cache ->
        val prior = File(cache, "merge_prior").apply { mkdir() }
        val untouched = File(prior, "keep").apply { writeText("prior") }
        var writtenAtProbe = -1L
        val provider = FakeProvider(listOf(13, 20), listOf(13, 1))
        provider.onOverflowProbe = {
            val liveAttempt = cache.listFiles()!!.single { it != prior }
            writtenAtProbe = liveAttempt.listFiles()!!.sumOf { it.length() }
        }
        assertThrows(ContributionMergeSizeExceeded::class.java) { stageContributionMerge(cache, provider.inputs(), 32) {} }
        assertEquals(32L, writtenAtProbe)
        assertEquals(1, provider.streams.last().singleReads)
        assertTrue(provider.streams.all { it.closed })
        assertEquals(listOf(prior), cache.listFiles()!!.toList())
        assertEquals("prior", untouched.readText())
    }

    @Test fun cancellationDuringProviderReadStopsBeforeWriteAndRemovesAttempt() = withCache { cache ->
        var cancelled = false
        val stream = Synthetic(40).apply { onBulk = { cancelled = true } }
        assertThrows(CancellationException::class.java) {
            stageContributionMerge(cache, listOf(ContributionMergeInput("clip.a/b") { stream }), 80) {
                if (cancelled) {
                    val current = cache.listFiles()!!.single()
                    assertEquals(0L, current.listFiles()!!.single().length())
                    throw CancellationException("synthetic cancel")
                }
            }
        }
        assertTrue(stream.closed)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun sharedAudioPublishesOnlyCompleteFilesAndKeepsNeighbor() = withCache { cache ->
        val keep = File(cache, "existing.m4a").apply { writeText("stable") }
        val good = Synthetic(20)
        val staged = stageSharedContributionAudio(cache, ContributionMergeInput("../same.M4A") { good }, 20) {}
        assertEquals(20L, staged.length())
        assertTrue(staged.name.startsWith("shared_") && staged.extension == "m4a")
        val invalid = Synthetic(21)
        invalid.onProbe = { assertEquals(20L, cache.listFiles()!!.single { it != staged && it != keep }.length()) }
        assertThrows(ContributionMergeSizeExceeded::class.java) {
            stageSharedContributionAudio(cache, ContributionMergeInput("../same.M4A") { invalid }, 20) {}
        }
        assertTrue(invalid.closed)
        assertEquals(setOf(keep, staged), cache.listFiles()!!.toSet())
        assertEquals("stable", keep.readText())
    }

    @Test fun sharedAudioCancellationNeverPublishesPartialInput() = withCache { cache ->
        val keep = File(cache, "other.mp3").apply { writeText("keep") }
        var stopped = false
        val source = Synthetic(100).apply { onBulk = { stopped = true } }
        assertThrows(CancellationException::class.java) {
            stageSharedContributionAudio(cache, ContributionMergeInput("unknown.mp3") { source }, 120) {
                if (stopped) throw CancellationException("cancel")
            }
        }
        assertTrue(source.closed)
        assertEquals(listOf(keep), cache.listFiles()!!.toList())
    }

    private inline fun withCache(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("minbar-merge-cap-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private class FakeProvider(private val lengths: List<Long>, private val descriptors: List<Long>) {
        var hintReads = 0
            private set
        val streams = mutableListOf<Synthetic>()
        var onOverflowProbe: () -> Unit = {}
        // This is what an Android provider queried for asset length would have reported.
        fun descriptor(index: Int): Long { hintReads++; return descriptors[index] }
        fun inputs() = lengths.mapIndexed { index, actual ->
            ContributionMergeInput(if (index == 0) "clip.M4A" else "clip.mp3") {
                Synthetic(actual).also { source ->
                    source.onProbe = { if (index == lengths.lastIndex) onOverflowProbe() }
                    streams += source
                }
            }
        }
    }

    private class Synthetic(private var remaining: Long) : InputStream() {
        var closed = false
        var singleReads = 0
        var maxRequested = 0
        var onProbe: () -> Unit = {}
        var onBulk: () -> Unit = {}
        override fun read(): Int {
            singleReads++
            onProbe()
            if (remaining <= 0) return -1
            remaining--
            return 4
        }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            maxRequested = maxOf(maxRequested, len)
            onBulk()
            if (remaining <= 0) return -1
            val n = minOf(remaining, len.toLong()).toInt()
            bytes.fill(4, off, off + n)
            remaining -= n
            return n
        }
        override fun close() { closed = true }
    }
}
