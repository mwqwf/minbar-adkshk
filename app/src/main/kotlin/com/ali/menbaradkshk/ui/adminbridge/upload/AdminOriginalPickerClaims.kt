package com.ali.menbaradkshk.ui.adminbridge.upload

/**
 * One Android original-audio document intent per memory-only registry key. Called on the UI thread.
 * Revoking a route severs its authority but must not free the launch slot before Android drains the
 * callback. A foreground pause just waits for a fresh verified admin session; it is not a revocation.
 */
internal class AdminOriginalPickerClaims<T : Any> {
    class Ticket internal constructor(internal val account: String) {
        internal var revoked = false
    }
    data class Result<T>(val account: String, val document: T)
    private var outstanding: Ticket? = null

    fun begin(account: String, currentRoute: String?): Ticket? {
        if (account.isBlank() || currentRoute != account || outstanding != null) return null
        return Ticket(account).also { outstanding = it }
    }

    fun revokeRoute() { outstanding?.revoked = true }

    fun launchFailed(ticket: Ticket) {
        // A thrown launch has never obtained a callback slot. A stale failure cannot clear another.
        if (outstanding === ticket) outstanding = null
    }

    fun accept(
        currentRoute: String?, resultOk: Boolean, contentOnly: Boolean,
        readGrant: Boolean, hasClipData: Boolean, document: T?,
    ): Result<T>? {
        val ticket = outstanding ?: return null
        outstanding = null
        if (ticket.revoked || ticket.account != currentRoute || !resultOk ||
            !contentOnly || !readGrant || hasClipData || document == null) return null
        return Result(ticket.account, document)
    }
}
