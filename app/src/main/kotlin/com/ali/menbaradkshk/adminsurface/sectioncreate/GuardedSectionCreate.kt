package com.ali.menbaradkshk.adminsurface.sectioncreate

import java.io.IOException
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

internal const val SECTION_CREATE_PATH = "/admin/guarded-section-create"
internal const val SECTION_CREATE_PARENT_PATH = "$SECTION_CREATE_PATH/parents"
private val operationIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val sectionId = Regex("[A-Za-z0-9_.-]{1,128}")
internal class SectionCreateConflict : IOException("Section conflicts with current catalog")

internal data class SectionCreateParent(val id: String, val name: String, val updatedAtMs: Long)
internal data class SectionCreateParents(val items: List<SectionCreateParent>, val truncated: Boolean)
internal data class SectionCreateReceipt(val id: String, val name: String, val categoryId: String?, val updatedAtMs: Long)
internal data class SectionCreateIntent(val operationId: String, val name: String, val parent: SectionCreateParent?)

private fun validName(name: String, limit: Int) = name.isNotEmpty() && name.length <= limit && name.trim() == name &&
    name.none { c -> c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() }
internal fun normalizedSectionCreationName(input: String): String? =
    Normalizer.normalize(input.trim(), Normalizer.Form.NFC).takeIf { validName(it, 160) }
private fun validParent(p: SectionCreateParent) =
    p.id.matches(sectionId) && p.id !in listOf(".", "..") && validName(p.name, 512) &&
        p.updatedAtMs in 0..9_007_199_254_740_991L

internal class GuardedSectionCreateAccess(
    private val active: () -> Boolean,
    private val send: suspend (String, String, JSONObject?) -> JSONObject,
) {
    fun available() = active()

    suspend fun parents(): SectionCreateParents {
        check(active())
        val result = send("GET", SECTION_CREATE_PARENT_PATH, null)
        check(active())
        return decodeSectionCreateParents(result)
    }

    suspend fun create(intent: SectionCreateIntent): SectionCreateReceipt {
        check(active())
        require(intent.operationId.matches(operationIdPattern) &&
            normalizedSectionCreationName(intent.name) == intent.name &&
            intent.parent?.let(::validParent) != false)
        val parent = intent.parent?.let {
            JSONObject().put("id", it.id).put("name", it.name).put("updatedAtMs", it.updatedAtMs)
        }
        val body = JSONObject().put("operationId", intent.operationId).put("name", intent.name)
            .put("parent", parent ?: JSONObject.NULL)
        val result = send("POST", SECTION_CREATE_PATH, body)
        check(active())
        return decodeSectionCreateReceipt(result, intent)
    }
}

private fun JSONObject.exact(vararg expected: String) = require(keys().asSequence().toSet() == expected.toSet())
private fun JSONObject.value(key: String) = opt(key) as? String ?: throw IOException("Invalid section text")
private fun JSONObject.time(key: String): Long = when (val time = opt(key)) {
    is Int -> time.toLong()
    is Long -> time
    else -> throw IOException("Invalid section revision")
}.also { require(it in 0..9_007_199_254_740_991L) }

internal fun decodeSectionCreateParents(response: JSONObject): SectionCreateParents {
    response.exact("items", "truncated")
    val items = response.opt("items") as? JSONArray ?: throw IOException("No parent list")
    require(items.length() <= 100)
    val rows = (0 until items.length()).map { i ->
        val row = items.getJSONObject(i)
        row.exact("id", "name", "updatedAtMs")
        SectionCreateParent(row.value("id"), row.value("name"), row.time("updatedAtMs")).also {
            require(validParent(it))
        }
    }
    require(rows.map { it.id }.toSet().size == rows.size)
    val truncated = response.opt("truncated") as? Boolean ?: throw IOException("Invalid parent list")
    return SectionCreateParents(rows, truncated)
}

internal fun decodeSectionCreateReceipt(response: JSONObject, intent: SectionCreateIntent): SectionCreateReceipt {
    response.exact("operationId", "id", "name", "categoryId", "updatedAtMs")
    val id = response.value("id")
    val expectedId = (if (intent.parent == null) "cat_" else "sub_") + intent.operationId.replace("-", "")
    val categoryId = if (intent.parent == null) {
        require(response.opt("categoryId") === JSONObject.NULL)
        null
    } else response.value("categoryId").also { require(it == intent.parent.id) }
    val name = response.value("name")
    val stamp = response.time("updatedAtMs")
    require(response.opt("operationId") == intent.operationId && id == expectedId && name == intent.name &&
        stamp > (intent.parent?.updatedAtMs ?: 0L))
    return SectionCreateReceipt(id, name, categoryId, stamp)
}
