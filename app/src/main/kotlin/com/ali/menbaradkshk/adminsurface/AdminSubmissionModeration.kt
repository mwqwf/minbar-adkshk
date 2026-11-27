package com.ali.menbaradkshk.adminsurface

import java.security.MessageDigest
import org.json.JSONObject

/** Capability supplied by the authenticated host; no generic write API is exposed to the workspace. */
fun interface AdminSubmissionMutationApi {
    suspend fun submit(command: AdminSubmissionCommand)
}

class AdminSubmissionCommand internal constructor(
    val submissionId: String,
    val action: Action,
    private val payload: JSONObject,
) {
    enum class Action(val pathSegment: String) { APPROVE("approve"), REJECT("reject") }
    val path: String get() = "/admin/submissions/$submissionId/${action.pathSegment}"
    val body: JSONObject get() = JSONObject(payload.toString())
}

internal const val SubmissionListPath = "/admin/submissions?status=all&limit=500"
internal const val ModerationReasonMax = 300
private val submissionIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

internal data class SubmissionSnapshot(
    val id: String,
    val title: String,
    val category: String,
    val subcategory: String,
    val audioFingerprint: String,
    val rights: Boolean,
) {
    val canApprove: Boolean get() = title.isNotBlank() && category.isNotBlank() &&
        subcategory.isNotBlank() && audioFingerprint.isNotBlank() && rights
}

internal fun parsePendingSubmission(row: JSONObject): SubmissionSnapshot? {
    if (row.optString("status") != "pending") return null
    val id = row.stringField("id").takeIf(submissionIdPattern::matches) ?: return null
    // This is the audio review queue; separate transcript decisions have different contracts.
    val audio = row.stringField("audio_key").ifBlank { row.stringField("audio_url") }
    if (audio.isBlank()) return null
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(audio.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return SubmissionSnapshot(id, row.stringField("title"), row.stringField("category_id"),
        row.stringField("subcategory_id"), fingerprint, row.optInt("rights_confirmed") == 1)
}

private fun JSONObject.stringField(key: String): String =
    (opt(key) as? String).orEmpty().trim()

internal sealed interface ModerationDecision {
    data object Approve : ModerationDecision
    data class Reject(val reason: String) : ModerationDecision
}

internal class StaleSubmission : Exception()

/** Re-read the exact submission and section relationship before allowing a first, deliberate write. */
internal suspend fun prepareSubmissionCommand(
    old: SubmissionSnapshot,
    decision: ModerationDecision,
    api: AdminWorkspaceApi,
): AdminSubmissionCommand {
    require(submissionIdPattern.matches(old.id))
    val listed = requireNotNull(api.get(SubmissionListPath).optJSONArray("items"))
    val matches = (0 until listed.length()).mapNotNull { listed.optJSONObject(it) }
        .filter { it.stringField("id") == old.id }
    val current = matches.singleOrNull()?.let(::parsePendingSubmission)
    if (current != old) throw StaleSubmission()
    return when (decision) {
        ModerationDecision.Approve -> {
            require(old.canApprove)
            val categories = requireNotNull(api.get("/admin/categories").optJSONArray("items"))
            val subcategories = requireNotNull(api.get("/admin/subcategories").optJSONArray("items"))
            val category = (0 until categories.length()).mapNotNull { categories.optJSONObject(it) }
                .singleOrNull { it.stringField("id") == old.category }
                ?: throw StaleSubmission()
            val subcategory = (0 until subcategories.length()).mapNotNull { subcategories.optJSONObject(it) }
                .singleOrNull { it.stringField("id") == old.subcategory && it.stringField("category_id") == old.category }
                ?: throw StaleSubmission()
            val categoryName = category.stringField("name").takeIf(String::isNotBlank) ?: throw StaleSubmission()
            val subcategoryName = subcategory.stringField("name").takeIf(String::isNotBlank) ?: throw StaleSubmission()
            AdminSubmissionCommand(
                old.id,
                AdminSubmissionCommand.Action.APPROVE,
                JSONObject().put("title", old.title)
                    .put("categoryId", old.category).put("categoryName", categoryName)
                    .put("subcategoryId", old.subcategory).put("subcategoryName", subcategoryName)
                    .put("edited", false),
            )
        }
        is ModerationDecision.Reject -> {
            val reason = decision.reason.trim()
            require(reason.length in 2..ModerationReasonMax)
            AdminSubmissionCommand(old.id, AdminSubmissionCommand.Action.REJECT, JSONObject().put("reason", reason))
        }
    }
}
