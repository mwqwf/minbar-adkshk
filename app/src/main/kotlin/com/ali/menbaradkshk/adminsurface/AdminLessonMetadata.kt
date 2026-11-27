package com.ali.menbaradkshk.adminsurface

import org.json.JSONArray
import org.json.JSONObject

/**
 * Only bind this capability to an atomic server-side guarded metadata route. The legacy
 * /admin/lessons/{id} upsert ignores preconditions and is deliberately not a destination here.
 */
fun interface AdminLessonMutationApi {
    suspend fun submit(command: AdminLessonMetadataCommand)
}

class AdminLessonMetadataCommand internal constructor(
    val lessonId: String,
    private val payload: JSONObject,
) {
    // Dedicated proposed route: a legacy server answers 404 rather than accepting an unguarded edit.
    val path: String get() = "/admin/lessons/$lessonId/metadata"
    val body: JSONObject get() = JSONObject(payload.toString())
}

internal const val MaxLessonTitle = 240
private val safeLessonId = Regex("[A-Za-z0-9_-]{1,128}")

internal data class LessonMetadata(
    val id: String,
    val title: String,
    val categoryId: String,
    val subcategoryId: String,
    val updatedAtMs: Long,
)

internal data class LiveLessonDestination(
    val categoryId: String, val categoryName: String,
    val subcategoryId: String, val subcategoryName: String,
)

internal sealed interface LessonMetadataEdit {
    data class Rename(val title: String) : LessonMetadataEdit
    data class Move(val to: LiveLessonDestination) : LessonMetadataEdit
}

internal class LessonMetadataStale : Exception()

internal fun liveLessonMetadata(row: JSONObject): LessonMetadata? {
    if (!row.has("deleted_at_ms") || !row.isNull("deleted_at_ms")) return null
    return parseLessonSnapshot(row, "category_id", "subcategory_id", "updated_at_ms")
}

private fun parseLessonSnapshot(
    row: JSONObject, categoryKey: String, subcategoryKey: String, revisionKey: String,
): LessonMetadata? {
    val id = row.rawString("id")?.takeIf(safeLessonId::matches) ?: return null
    val title = row.rawString("title") ?: return null
    val revision = (row.opt(revisionKey) as? Number)?.let {
        it.toLong().takeIf { value -> value in 0L..9_007_199_254_740_990L && it.toDouble() == value.toDouble() }
    } ?: return null
    val category = row.rawString(categoryKey) ?: return null
    val subcategory = row.rawString(subcategoryKey) ?: return null
    return LessonMetadata(id, title, category, subcategory, revision)
}

private fun JSONObject.rawString(key: String): String? = opt(key) as? String
private fun JSONObject.field(key: String): String = rawString(key).orEmpty()
private fun JSONArray.rows(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.liveSections(): List<JSONObject> =
    requireNotNull(optJSONArray("items")).rows()
        .filter { it.has("deleted_at_ms") && it.isNull("deleted_at_ms") }

internal suspend fun fetchLessonDestinations(api: AdminWorkspaceApi): List<LiveLessonDestination> {
    val categories = api.get("/admin/categories").liveSections()
        .groupBy { it.field("id") }
        .filterKeys(String::isNotBlank)
        .mapValues { (_, matches) -> matches.singleOrNull()?.field("name").orEmpty() }
    return api.get("/admin/subcategories").liveSections()
        .groupBy { it.field("id") }
        .values.mapNotNull { matches ->
            val item = matches.singleOrNull() ?: return@mapNotNull null
            val categoryId = item.field("category_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val categoryName = categories[categoryId]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val id = item.field("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.field("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            LiveLessonDestination(categoryId, categoryName, id, name)
        }.sortedWith(compareBy({ it.categoryName }, { it.subcategoryName }))
}

internal suspend fun prepareLessonMetadataCommand(
    original: LessonMetadata,
    edit: LessonMetadataEdit,
    api: AdminWorkspaceApi,
): AdminLessonMetadataCommand {
    require(safeLessonId.matches(original.id))
    val response = api.get("/admin/lessons/${original.id}/metadata")
    val actual = parseLessonSnapshot(response, "categoryId", "subcategoryId", "updatedAtMs")
    if (actual != original) throw LessonMetadataStale()
    val payload = JSONObject().put("expectedUpdatedAtMs", original.updatedAtMs)
        .put("expectedTitle", original.title)
        .put("expectedCategoryId", original.categoryId)
        .put("expectedSubcategoryId", original.subcategoryId)
    when (edit) {
        is LessonMetadataEdit.Rename -> {
            val title = edit.title.trim()
            require(title.length in 1..MaxLessonTitle && title != original.title && title.none(Char::isISOControl))
            payload.put("title", title)
        }
        is LessonMetadataEdit.Move -> {
            require(edit.to.subcategoryId != original.subcategoryId || edit.to.categoryId != original.categoryId)
            // Bind the chosen names to the IDs shown at confirmation; a removed/renamed fork requires a new selection.
            if (fetchLessonDestinations(api).singleOrNull { it == edit.to } == null) throw LessonMetadataStale()
            payload.put("categoryId", edit.to.categoryId).put("subcategoryId", edit.to.subcategoryId)
                .put("expectedNewCategoryName", edit.to.categoryName)
                .put("expectedNewSubcategoryName", edit.to.subcategoryName)
        }
    }
    return AdminLessonMetadataCommand(original.id, payload)
}
