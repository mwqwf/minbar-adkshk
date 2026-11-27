package com.ali.menbaradkshk.media

import com.ali.menbaradkshk.data.Lesson
import org.junit.Assert.*
import org.junit.Test

class LessonResumptionTest {
    private val newest = lesson("newest")
    private val unfinished = lesson("unfinished")
    private fun lesson(id: String, url: String = "https://example.invalid/audio") =
        Lesson(id, id, "category", "section", url, 0L, durationMs = 180_000L)

    @Test fun completedMostRecentDoesNotHideOlderUnfinishedLesson() {
        val args = mapOf(newest.id to newest, unfinished.id to unfinished)
        for (allowReplay in listOf(false, true)) {
            val target = selectLessonResumeTarget(
                listOf(newest.id, unfinished.id), args,
                mapOf(unfinished.id to 65_000L), emptyMap(), setOf(newest.id), allowReplay,
            )
            assertEquals(unfinished.id, target?.lesson?.id)
            assertEquals(65_000L, target?.positionMs)
            assertTrue(target?.isContinuation == true)
        }
    }

    @Test fun unavailableMostRecentDoesNotHideOlderUnfinishedLesson() {
        val target = selectLessonResumeTarget(
            listOf("removed", newest.id, unfinished.id),
            mapOf(newest.id to lesson(newest.id, ""), unfinished.id to unfinished),
            mapOf(newest.id to 60_000L, unfinished.id to 50_000L),
            emptyMap(), emptySet(), true,
        )
        assertEquals(unfinished.id, target?.lesson?.id)
        assertEquals(50_000L, target?.positionMs)
    }

    @Test fun coldFallbackPreservesShortSavedProgressAndCompletedReplayDoesNotSeekToEnd() {
        val catalog = mapOf(newest.id to newest)
        val short = selectLessonResumeTarget(
            listOf(newest.id), catalog, mapOf(newest.id to 12_000L), emptyMap(), emptySet(), true,
        )
        assertEquals(12_000L, short?.positionMs)
        assertFalse(short?.isContinuation ?: true)
        val complete = selectLessonResumeTarget(
            listOf(newest.id), catalog, mapOf(newest.id to 180_000L), emptyMap(), setOf(newest.id), true,
        )
        assertEquals(0L, complete?.positionMs)
        val home = selectLessonResumeTarget(
            listOf(newest.id), catalog, mapOf(newest.id to 12_000L), emptyMap(), emptySet(), false,
        )
        assertNull(home)
    }

    @Test fun savedOnlySkipsOnlineLessonAndCanResumeLocalOnlyLesson() {
        val target = selectLessonResumeTarget(
            listOf(newest.id, unfinished.id),
            mapOf(newest.id to newest, unfinished.id to lesson(unfinished.id, "")),
            mapOf(newest.id to 65_000L, unfinished.id to 85_000L),
            emptyMap(), emptySet(), false, savedOnly = true,
            localPath = { id -> if (id == unfinished.id) "/local/unfinished.opus" else null },
        )
        assertEquals(unfinished.id, target?.lesson?.id)
        assertEquals("/local/unfinished.opus", target?.localPath)
    }

    @Test fun persistedDurationWinsOverCatalogAndNearEndDoesNotReplaceGenuineContinuation() {
        val target = selectLessonResumeTarget(
            listOf(newest.id, unfinished.id),
            mapOf(newest.id to newest, unfinished.id to unfinished),
            mapOf(newest.id to 95_000L, unfinished.id to 70_000L),
            mapOf(newest.id to 100_000L), emptySet(), true,
        )
        assertEquals(unfinished.id, target?.lesson?.id)
    }
}
