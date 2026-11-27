package com.ali.menbaradkshk.adminsurface.review

import com.ali.menbaradkshk.adminsurface.AdminAccessRejected
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceRole
import com.ali.menbaradkshk.adminsurface.WorkspaceRow
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

internal const val FeedbackPath = "/admin/feedback"
internal const val AdminAlertsPath = "/admin/alerts"

internal enum class AlertReadFilter(val label: String, val read: Boolean?) {
    All("الكل", null), Unread("غير مقروء", false), Read("مقروء", true);
}

internal fun List<WorkspaceRow>.matchingAlertReadFilter(filter: AlertReadFilter): List<WorkspaceRow> =
    filter { row -> row.alertRead != null && (filter.read == null || filter.read == row.alertRead) }

internal suspend fun readFeedbackReview(role: AdminWorkspaceRole, api: AdminWorkspaceApi): List<WorkspaceRow> {
    // Cloud privacy.ts reserves the entire feed to owners, not just feedback deletion.
    if (role != AdminWorkspaceRole.OWNER) throw AdminAccessRejected()
    val rows = api.get(FeedbackPath).items()
    return rows.sortedByDescending { it.time("created_at_ms") }.map { item ->
        val kind = when (item.field("kind")) {
            "benefited" -> "استفاد"
            "audio_issue" -> "مشكلة صوت"
            "copyright" -> "حقوق نشر"
            "abuse" -> "إساءة"
            else -> "بلاغ"
        }
        val note = sanitizedFeedbackNote(item.field("text"))
        // No contact, sender, device ID, attachment, lesson identifier, or raw response is shown.
        WorkspaceRow(kind, listOfNotNull(note, formatAlertTime(item.time("created_at_ms"))).joinToString("\n").ifBlank { null })
    }
}

internal suspend fun readAdminAlerts(role: AdminWorkspaceRole, api: AdminWorkspaceApi): List<WorkspaceRow> {
    val response = api.get(AdminAlertsPath)
    // Existing endpoint uses the current authenticated principal solely for readership and audience checks.
    // It is not kept in a DTO or shown. The gate, not this value, decides the caller's role.
    val me = response.field("me").trim()
    require('@' in me && me.length <= 320)
    return response.items().sortedByDescending { it.time("createdAtMs") }.mapNotNull { item ->
        val recipient = item.field("email")
        if ((recipient.isNotBlank() && !recipient.equals(me, ignoreCase = true)) ||
            item.field("excludeEmail").equals(me, ignoreCase = true)) return@mapNotNull null
        val label = when (item.field("type")) {
            // Older adminx.ts included the acceptance code itself in body. Do not render this alert at all.
            "owner_code" -> return@mapNotNull null
            "support" -> if (role == AdminWorkspaceRole.OWNER) "رسالة دعم" else return@mapNotNull null
            "suspicious_lesson", "suspicious_scan" ->
                if (role == AdminWorkspaceRole.OWNER) "مراجعة الدروس المشتبه بها" else return@mapNotNull null
            "submission" -> "مساهمة صوتية"
            "transcript" -> "نصّ مشروح مقترَح"
            "engagement" -> "تفاعل المستمعين"
            "milestone" -> "مؤشر منبر"
            "digest", "weekly_digest" -> "ملخّص منبر"
            "featured_expiring" -> "انتهاء تمييز درس"
            "dead_audio_scan" -> "فحص روابط الصوت"
            else -> "تنبيه عام"
        }
        val readers = requireNotNull(item.optJSONArray("readBy"))
        val hasRead = (0 until readers.length()).any {
            (readers.opt(it) as? String)?.equals(me, ignoreCase = true) == true
        }
        val status = if (hasRead) "مقروء" else "غير مقروء"
        // Do not display any free alert title/body, payload, ref ID, raw recipient or reader.
        // Opening this page never stamps a read, dismisses a record, or sends a message.
        WorkspaceRow(label, listOfNotNull(status, formatAlertTime(item.time("createdAtMs"))).joinToString(" · "),
            alertRead = hasRead)
    }
}

private fun JSONObject.items(): List<JSONObject> {
    val array = requireNotNull(optJSONArray("items"))
    return (0 until array.length()).map { array.getJSONObject(it) }
}

private fun JSONObject.field(key: String): String = (opt(key) as? String).orEmpty()
private fun JSONObject.time(key: String): Long = (opt(key) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L

internal fun formatAlertTime(timestamp: Long): String? = if (timestamp in 1L..253_402_300_799_000L) {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("ar"))
        .format(Date(timestamp))
} else null

// Free-text previews remain useful to the owner. This masks common contact/token formats,
// not all possible identifiable text; no links are made clickable.
private val privateSpans = listOf(
    Regex("(?i)(?:https?://|www\\.)[^\\s<>()]+"),
    Regex("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+"),
    Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+"),
    Regex("(?i)\\b(?:ms_|sk_|ghp_|eyJ)[A-Za-z0-9._-]{12,}"),
    Regex("(?<!\\p{N})[+＋]?(?:[\\p{Nd}][\\s().-]?){7,15}(?!\\p{N})"),
)

internal fun sanitizedFeedbackNote(text: String): String? {
    // Server accepts free text; cap processing before regex on any malformed unbounded legacy row.
    var scrubbed = text.take(5000)
    for ((index, pattern) in privateSpans.withIndex()) {
        if (index != 1 || '@' in scrubbed) scrubbed = scrubbed.replace(pattern, "…")
    }
    return scrubbed.filterNot { it.isISOControl() && it != '\n' && it != '\t' }
        .trim().take(500).ifBlank { null }
}
