package com.ali.menbaradkshk.ui.adminbridge.share

import java.io.InputStream
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraft
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraftStore
import java.io.File

private val audioMime = Regex("audio/(?:\\*|[a-z0-9][a-z0-9._+\\-]{0,63})", RegexOption.IGNORE_CASE)

internal fun supportsModeratorAudioShareType(type: String?) =
    type != null && audioMime.matches(type)

internal fun validModeratorShareAuthority(authority: String?, ownPackage: String): Boolean =
    authority != null && authority.length in 1..253 &&
        authority.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" } &&
        !authority.equals(ownPackage, ignoreCase = true) &&
        !authority.startsWith("$ownPackage.", ignoreCase = true)

/** Intentionally knows only about the verified-account local draft store, no upload or publication. */
internal fun prepareModeratorSharedAudio(
    store: AdminUploadDraftStore,
    confirmed: Boolean,
    leaseActive: () -> Boolean,
    hasReadGrant: () -> Boolean,
    checkCancelled: () -> Unit,
    validateAudio: (File) -> Unit,
    open: () -> InputStream,
): AdminUploadDraft {
    fun check() {
        checkCancelled()
        check(confirmed && leaseActive() && hasReadGrant()) { "No active moderator share permission" }
    }
    check()
    return store.prepare(
        fileName = "تسجيل صوتي مشترك",
        reportedSize = null,
        checkActive = ::check,
        validateAudio = validateAudio,
    ) {
        check()
        open()
    }
}
