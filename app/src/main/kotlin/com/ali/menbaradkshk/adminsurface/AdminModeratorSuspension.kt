package com.ali.menbaradkshk.adminsurface

import java.io.IOException
import org.json.JSONObject

internal const val MODERATOR_SUSPEND_VERIFY = "/admin/guarded-moderator-suspension/verify"
internal const val MODERATOR_SUSPEND_WRITE = "/admin/guarded-moderator-suspension/suspend"
internal data class ModeratorSuspensionSnapshot(
    val member: ModeratorRosterItem, val stamp: String, val changedAtMs: Long,
)

internal class ModeratorSuspensionAccess(private val current: () -> Boolean,
    private val request: suspend (path: String, body: JSONObject) -> JSONObject) {
    fun available() = current()
    private fun requireAccess() { if (!available()) throw AdminAccessRejected() }
    suspend fun verify(member: ModeratorRosterItem): ModeratorSuspensionSnapshot {
        requireAccess()
        require(member.role in setOf("admin","supervisor") && validModeratorEmail(member.email))
        val json = request(MODERATOR_SUSPEND_VERIFY, JSONObject().put("email",member.email))
        requireAccess()
        if (json.keys().asSequence().toSet()!=setOf("email","role","stamp","changedAtMs") ||
            json.opt("email")!=member.email || json.opt("role")!=member.role) throw IOException("Moderator role changed")
        val stamp = (json.opt("stamp") as? String)?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
            ?: throw IOException("Invalid revision")
        val ms = when (val n = json.opt("changedAtMs")) {
            is Int -> n.toLong()
            is Long -> n
            else -> throw IOException("Invalid revision")
        }
        if (ms !in 0..9_007_199_254_740_991L) throw IOException("Invalid revision")
        return ModeratorSuspensionSnapshot(member,stamp,ms)
    }
    suspend fun suspendVerified(snapshot: ModeratorSuspensionSnapshot) {
        requireAccess()
        val member=snapshot.member
        require(member.role in setOf("admin","supervisor") && validModeratorEmail(member.email) &&
            snapshot.stamp.matches(Regex("[a-f0-9]{32}")) && snapshot.changedAtMs in 0..9_007_199_254_740_991L)
        val json=request(MODERATOR_SUSPEND_WRITE,JSONObject().put("email",member.email)
            .put("expectedRole",member.role).put("expectedStamp",snapshot.stamp)
            .put("expectedChangedAtMs",snapshot.changedAtMs))
        requireAccess()
        if (json.keys().asSequence().toSet()!=setOf("email","status") ||
            json.opt("email")!=member.email || json.opt("status")!="suspended")
            throw IOException("Suspension not confirmed")
    }
}
