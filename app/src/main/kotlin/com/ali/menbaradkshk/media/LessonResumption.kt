package com.ali.menbaradkshk.media

import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LocalStore

internal data class LessonResumeTarget(
    val lesson: Lesson,
    val positionMs: Long,
    val durationMs: Long,
    val isContinuation: Boolean,
    val localPath: String?,
)

/** Prefer the latest meaningful unfinished attempt; never confuse list order with playability. */
internal fun selectLessonResumeTarget(
    recentIds: List<String>,
    lessonsById: Map<String, Lesson>,
    positions: Map<String, Long>,
    durations: Map<String, Long>,
    completed: Set<String>,
    allowReplayFallback: Boolean,
    savedOnly: Boolean = false,
    localPath: (String) -> String? = { null },
): LessonResumeTarget? {
    var fallback: LessonResumeTarget? = null
    for (id in recentIds) {
        val lesson = lessonsById[id] ?: continue
        val local = localPath(id)
        if ((savedOnly || lesson.audioUrl.isBlank()) && local == null) continue
        val position = positions[id]?.coerceAtLeast(0) ?: 0L
        val duration = durations[id]?.takeIf { it > 0L } ?: lesson.durationMs
        val isComplete = id in completed || (duration > 0L && position >= duration)
        val canContinue = !isComplete && position >= 30_000L &&
            (duration <= 0L || position < duration - 30_000L)
        if (canContinue) return LessonResumeTarget(lesson, position, duration, true, local)
        if (allowReplayFallback && fallback == null) {
            fallback = LessonResumeTarget(lesson, if (isComplete) 0L else position, duration, false, local)
        }
    }
    return fallback
}

internal fun LocalStore.coldLessonResumeTarget(): LessonResumeTarget? {
    val lookup = playableAudioSnapshot()
    return selectLessonResumeTarget(
        recentPlayedIds(), lessons().associateBy(Lesson::id), positions(), durations(),
        completedIds().toSet(), allowReplayFallback = true, localPath = { lookup(it)?.path },
    )
}
