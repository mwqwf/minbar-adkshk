package com.ali.menbaradkshk.data

import org.json.JSONObject

/** Optional Worker ordering only for an explicitly complete sibling frame. */
data class LessonDisplayOrder(
    val categoryId: String,
    val subcategoryId: String,
    val revision: Long,
    val position: Int,
    val count: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("categoryId", categoryId).put("subcategoryId", subcategoryId)
        .put("revision", revision).put("position", position).put("count", count)

    companion object {
        private val id = Regex("[a-zA-Z0-9_.-]{1,128}")
        private fun ident(x: Any?) = (x as? String)?.takeIf {
            id.matches(it) && it != "." && it != ".."
        }
        private fun exactNumber(value: Any?) = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
        internal fun parse(raw: Any?, category: String, subcategory: String): LessonDisplayOrder? {
            val r = raw as? JSONObject ?: return null
            if (r.keys().asSequence().toSet() != setOf("categoryId","subcategoryId","revision","position","count")) return null
            val cat = ident(r.opt("categoryId"))?.takeIf { it == category } ?: return null
            val sub = ident(r.opt("subcategoryId"))?.takeIf { it == subcategory } ?: return null
            val rev = exactNumber(r.opt("revision"))?.takeIf { it in 1..9_007_199_254_740_990L } ?: return null
            val count = exactNumber(r.opt("count"))?.takeIf { it in 2..20 }?.toInt() ?: return null
            val rank = exactNumber(r.opt("position"))?.takeIf { it in 1..count.toLong() }?.toInt() ?: return null
            return LessonDisplayOrder(cat, sub, rev, rank, count)
        }
    }
}

internal fun orderedSubcategoryLessons(rows: List<Lesson>, subcategoryId: String): List<Lesson> {
    val legacy = { rows.sortedBy(Lesson::createdAtMs) }
    if (rows.size !in 2..20) return legacy()
    val first = rows.first().displayOrder ?: return legacy()
    val positions = mutableSetOf<Int>()
    if (rows.any { row ->
        val hint = row.displayOrder
        hint == null || row.categoryId != first.categoryId || row.subcategoryId != subcategoryId ||
            hint.categoryId != first.categoryId || hint.subcategoryId != subcategoryId ||
            hint.revision <= 0L || hint.revision != first.revision || hint.count != rows.size ||
            hint.position !in 1..rows.size || !positions.add(hint.position)
    } || rows.map(Lesson::id).toSet().size != rows.size) return legacy()
    return rows.sortedBy { checkNotNull(it.displayOrder).position }
}
