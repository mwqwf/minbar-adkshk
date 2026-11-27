package com.ali.menbaradkshk.adminsurface

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** Optional authority for single-lesson restore with no implicit ancestor changes. */
fun interface AdminTrashRestoreApi {
    suspend fun restore(command: AdminTrashRestoreCommand)
}

class AdminTrashRestoreCommand internal constructor(
    val lessonId: String,
    private val payload: JSONObject,
) {
    // Do not fall back to /restore: that route can also restore ancestors and does not check generation.
    val path: String get() = "/admin/lessons/$lessonId/restore-guarded"
    val body: JSONObject get() = JSONObject(payload.toString())
}

internal const val AdminTrashPath = "/admin/trash"
private val safeTrashId = Regex("[A-Za-z0-9_-]{1,128}")
private fun JSONObject.raw(key: String): String? = opt(key) as? String
private fun JSONObject.positiveTimestamp(key: String): Long? = (opt(key) as? Number)?.let {
    it.toLong().takeIf { n -> n in 1L..9_007_199_254_740_990L && it.toDouble() == n.toDouble() }
}

internal data class ArchivedLesson(
    val id: String,
    val title: String,
    val categoryId: String,
    val subcategoryId: String,
    val deletedAtMs: Long,
    val updatedAtMs: Long,
)

internal class ArchiveChanged : Exception()
internal class ArchiveParentsUnavailable : Exception()

private fun archivedLesson(row: JSONObject): ArchivedLesson? {
    val id = row.raw("id")?.takeIf(safeTrashId::matches) ?: return null
    val title = row.raw("title") ?: return null
    val categoryId = row.raw("category_id")?.takeIf(String::isNotBlank) ?: return null
    val subcategoryId = row.raw("subcategory_id")?.takeIf(String::isNotBlank) ?: return null
    val deleted = row.positiveTimestamp("deleted_at_ms") ?: return null
    val updated = row.positiveTimestamp("updated_at_ms") ?: return null
    return ArchivedLesson(id, title, categoryId, subcategoryId, deleted, updated)
}

private fun JSONObject.archiveRows(table: String): List<JSONObject> {
    val array = requireNotNull(optJSONArray(table))
    return (0 until array.length()).map { array.getJSONObject(it) }
}

/** The Backend lists only soft-deleted rows across these three resources. No purge or delete API is used. */
internal fun readArchive(response: JSONObject): List<WorkspaceRow> {
    val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("ar"))
    return listOf(
        Triple("lessons", "title", "درس"),
        Triple("categories", "name", "قسم رئيسي"),
        Triple("subcategories", "name", "قسم فرعي"),
    ).flatMap { (table, labelKey, kind) ->
        response.archiveRows(table).map { row ->
            val deletedAt = requireNotNull(row.positiveTimestamp("deleted_at_ms"))
            val title = row.raw(labelKey)?.trim()?.take(240).orEmpty().ifBlank { "عنصر دون اسم" }
            deletedAt to WorkspaceRow(title, "$kind · ${dates.format(Date(deletedAt))}",
                archivedLesson = if (table == "lessons") archivedLesson(row) else null)
        }
    }.sortedByDescending { it.first }.map { it.second }
}

internal suspend fun prepareArchiveRestore(
    original: ArchivedLesson,
    api: AdminWorkspaceApi,
): AdminTrashRestoreCommand {
    require(safeTrashId.matches(original.id))
    // Read the selected *deleted generation* from the real archive list again.
    val candidate = api.get(AdminTrashPath).archiveRows("lessons")
        .filter { it.raw("id") == original.id }.singleOrNull()?.let(::archivedLesson)
    if (candidate != original) throw ArchiveChanged()
    // Fast preflight feedback; the guarded write must repeat parent checks in its own SQL.
    if (fetchLessonDestinations(api).none {
            it.categoryId == original.categoryId && it.subcategoryId == original.subcategoryId
        }) throw ArchiveParentsUnavailable()
    return AdminTrashRestoreCommand(original.id, JSONObject()
        .put("expectedDeletedAtMs", original.deletedAtMs)
        .put("expectedUpdatedAtMs", original.updatedAtMs)
        .put("expectedTitle", original.title)
        .put("expectedCategoryId", original.categoryId)
        .put("expectedSubcategoryId", original.subcategoryId))
}
