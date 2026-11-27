package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private val prepareUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

internal data class PrivateLessonPreparation(
    val draft: UploadStageReceipt,
    val preparationId: String,
)

internal fun proveCommittedPrivateDraft(draft: AdminUploadDraft, result: JSONObject): UploadStageReceipt {
    if (result.opt("state") != "committed_draft" || exactStageNumber(result.opt("revision")) != 2L)
        noPreparationProof()
    if (!result.matchesAudio(draft)) noPreparationProof()
    return UploadStageReceipt(draft.id, draft.sha256, draft.byteCount)
}

private fun JSONObject.matchesAudio(draft: AdminUploadDraft): Boolean =
    opt("ok") == true && opt("draftId") == draft.id &&
        opt("sourceSha256") == draft.sha256 && exactStageNumber(opt("sourceBytes")) == draft.byteCount &&
        opt("sourceVerified") == true

internal suspend fun prepareVerifiedDraft(
    draft: AdminUploadDraft,
    confirmedReceipt: UploadStageReceipt,
    getCommitted: suspend () -> JSONObject,
    postAdopt: suspend (JSONObject) -> JSONObject,
): PrivateLessonPreparation {
    currentCoroutineContext().ensureActive()
    if (proveCommittedPrivateDraft(draft, getCommitted()) != confirmedReceipt) noPreparationProof()
    currentCoroutineContext().ensureActive()
    val result = postAdopt(JSONObject().put("expectedRevision", 2)
        .put("sourceSha256", draft.sha256).put("sourceBytes", draft.byteCount))
    currentCoroutineContext().ensureActive()
    if (!result.matchesAudio(draft) || result.opt("state") != "prepared" ||
        exactStageNumber(result.opt("revision")) != 1L ||
        exactStageNumber(result.opt("draftRevision")) != 2L) noPreparationProof()
    val id = (result.opt("preparationId") as? String)?.takeIf(prepareUuid::matches) ?: noPreparationProof()
    return PrivateLessonPreparation(confirmedReceipt, id)
}

private fun noPreparationProof(): Nothing = throw IOException("Private preparation receipt not verified")
