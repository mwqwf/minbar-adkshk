package com.ali.menbaradkshk.adminsurface.analytics

import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import org.json.JSONObject

internal const val ListeningLessonPath = "/admin/lessons"
internal const val ListeningSectionPath = "/admin/subcategories"
private const val DayMs = 86_400_000L

internal enum class LessonAddedRange(val days: Long?, val label: String) {
    ALL(null, "كل الدروس"), WEEK(7, "٧ أيام"), MONTH(30, "٣٠ يومًا"),
}

private data class ListeningLesson(
    val id: String,
    val title: String,
    val section: String?,
    val addedAtMs: Long?,
    val count: Long,
)

internal data class ListeningRank(val label: String, val count: Long)
internal data class ListeningCohort(
    val lessons: Int,
    val cumulativeCount: Long,
    val recentAgeUnknown: Int,
    val topLessons: List<ListeningRank>,
    val topSections: List<ListeningRank>,
)

internal data class ListeningAnalytics internal constructor(
    val measuredAtMs: Long,
    val byAddedRange: Map<LessonAddedRange, ListeningCohort>,
)

/** The old admin computes these aggregates from active lesson rows and live subcategory names. */
internal suspend fun fetchListeningAnalytics(api: AdminWorkspaceApi, nowMs: Long): ListeningAnalytics {
    val lessons = api.get(ListeningLessonPath)
    val sections = api.get(ListeningSectionPath)
    return summarizeListening(lessons, sections, nowMs)
}

internal fun summarizeListening(
    lessonResponse: JSONObject, sectionResponse: JSONObject, nowMs: Long,
): ListeningAnalytics {
    require(nowMs > 0L)
    val lessons = lessonResponse.activeRows().map { row ->
        val id = requireNotNull(row.identifier("id"))
        val count = requireNotNull(row.strictNonnegativeLong("views"))
        val added = row.strictNonnegativeLong("created_at_ms")?.takeIf { it in 1L..nowMs }
        ListeningLesson(
            id, row.displayText("title", "درس دون عنوان"),
            row.identifier("subcategory_id"), added, count,
        )
    }
    require(lessons.map { it.id }.toSet().size == lessons.size)
    val sections = sectionResponse.activeRows().map {
        requireNotNull(it.identifier("id")) to it.displayText("name", "قسم دون اسم")
    }
    require(sections.map { it.first }.toSet().size == sections.size)
    val names = sections.toMap()
    val unknownAge = lessons.count { it.addedAtMs == null }
    val ranges = LessonAddedRange.entries.associateWith { range ->
        val subset = if (range.days == null) lessons else lessons.filter {
            it.addedAtMs != null && nowMs - it.addedAtMs < range.days * DayMs
        }
        val playSum = subset.fold(0L) { total, item -> Math.addExact(total, item.count) }
        val rankedLessons = subset.asSequence().filter { it.count > 0L }
            .sortedWith(compareByDescending<ListeningLesson> { it.count }.thenBy { it.title }.thenBy { it.id })
            .take(10).map { ListeningRank(it.title, it.count) }.toList()
        val sectionCounts = mutableMapOf<String, Long>()
        for (item in subset) {
            val section = item.section?.takeIf(names::containsKey) ?: continue
            if (item.count > 0L) sectionCounts[section] = Math.addExact(sectionCounts[section] ?: 0L, item.count)
        }
        val rankedSections = sectionCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }
                .thenBy { names.getValue(it.key) }.thenBy { it.key })
            .take(8).map { ListeningRank(names.getValue(it.key), it.value) }
        ListeningCohort(subset.size, playSum, if (range.days == null) 0 else unknownAge, rankedLessons, rankedSections)
    }
    return ListeningAnalytics(nowMs, ranges)
}

private fun JSONObject.activeRows(): List<JSONObject> {
    val array = requireNotNull(optJSONArray("items"))
    return (0 until array.length()).map { array.getJSONObject(it) }.filter {
        // Both exact endpoints exclude deleted rows and return SQL deleted_at_ms as null.
        require(it.has("deleted_at_ms"))
        it.isNull("deleted_at_ms")
    }
}

private fun JSONObject.identifier(key: String): String? = (opt(key) as? String)?.takeIf(String::isNotBlank)

private fun JSONObject.strictNonnegativeLong(key: String): Long? = (opt(key) as? Number)?.let {
    it.toLong().takeIf { n -> n in 0L..9_007_199_254_740_991L && it.toDouble() == n.toDouble() }
}

private val email = Regex("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")
private fun JSONObject.displayText(key: String, fallback: String): String =
    (opt(key) as? String).orEmpty().replace(email, "…").trim().take(240).ifBlank { fallback }
