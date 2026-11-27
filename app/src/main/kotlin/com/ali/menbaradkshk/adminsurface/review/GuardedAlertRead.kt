package com.ali.menbaradkshk.adminsurface.review

import com.ali.menbaradkshk.adminsurface.AdminAccessRejected
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceRole
import com.ali.menbaradkshk.adminsurface.WorkspaceRow
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal const val GUARDED_ALERT_FEED = "/admin/guarded-alert-own-read"
internal const val GUARDED_ALERT_MARK = "$GUARDED_ALERT_FEED/mark"
internal data class GuardedAlertRow(val id: String, val createdAtMs: Long, val kind: String, val read: Boolean)

private val idPattern=Regex("al_[a-f0-9]{20}")
private fun display(kind: String, role: AdminWorkspaceRole): String? = when(kind) {
    "support" -> "رسالة دعم".takeIf { role == AdminWorkspaceRole.OWNER }
    "suspicious_lesson","suspicious_scan" -> "مراجعة الدروس المشتبه بها".takeIf { role == AdminWorkspaceRole.OWNER }
    "submission" -> "مساهمة صوتية"
    "transcript" -> "نصّ مشروح مقترَح"
    "engagement" -> "تفاعل المستمعين"
    "milestone" -> "مؤشر منبر"
    "digest","weekly_digest" -> "ملخّص منبر"
    "featured_expiring" -> "انتهاء تمييز درس"
    "dead_audio_scan" -> "فحص روابط الصوت"
    "general" -> "تنبيه عام"
    else -> null
}

private fun strictAlert(raw: JSONObject): GuardedAlertRow {
    fun bad(): Nothing = throw IOException("Invalid scoped alert")
    if (raw.keys().asSequence().toSet()!=setOf("id","createdAtMs","kind","read")) bad()
    val id = (raw.opt("id") as? String)?.takeIf(idPattern::matches) ?: bad()
    val at = when(val n=raw.opt("createdAtMs")) { is Int -> n.toLong(); is Long -> n; else -> bad() }
    val kind=raw.opt("kind") as? String ?: bad()
    val read=raw.opt("read") as? Boolean ?: bad()
    if (at !in 1..9_007_199_254_740_991L || display(kind,AdminWorkspaceRole.OWNER)==null) bad()
    return GuardedAlertRow(id,at,kind,read)
}

internal class GuardedAlertReadAccess(private val role: AdminWorkspaceRole, private val current: () -> Boolean,
    private val request: suspend (path: String, body: JSONObject?) -> JSONObject) {
    private fun guard() { if(!current()) throw AdminAccessRejected() }
    suspend fun rows(): List<WorkspaceRow> {
        guard()
        val response=request(GUARDED_ALERT_FEED,null)
        guard()
        val items=response.opt("items") as? JSONArray ?: throw IOException("Invalid scoped feed")
        if (response.keys().asSequence().toSet()!=setOf("items") || items.length()>200) throw IOException("Invalid scoped feed")
        val seen=mutableSetOf<String>()
        return (0 until items.length()).mapNotNull { index ->
            val row=strictAlert(items.opt(index) as? JSONObject ?: throw IOException("Invalid scoped alert"))
            if(!seen.add(row.id)) throw IOException("Duplicate scoped alert")
            val label=display(row.kind,role) ?: return@mapNotNull null
            val read=if(row.read) "مقروء" else "غير مقروء"
            WorkspaceRow(label,listOfNotNull(read,formatAlertTime(row.createdAtMs)).joinToString(" · "),
                alertRead=row.read,guardedAlert=row)
        }
    }
    suspend fun mark(selected: GuardedAlertRow) {
        guard()
        require(!selected.read && display(selected.kind,role)!=null && idPattern.matches(selected.id))
        val reply=request(GUARDED_ALERT_MARK,JSONObject().put("id",selected.id).put("kind",selected.kind)
            .put("createdAtMs",selected.createdAtMs))
        guard()
        if (strictAlert(reply)!=selected.copy(read=true)) throw IOException("Could not verify the requested receipt")
    }
}
