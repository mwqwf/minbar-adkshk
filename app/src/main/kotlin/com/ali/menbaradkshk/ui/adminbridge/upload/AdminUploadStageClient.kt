package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal const val MAX_STAGE_BYTES = (8 * 1024 * 1024) - 1
private val uuid4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val destinationId = Regex("[A-Za-z0-9_-]{1,128}")
private val hashPattern = Regex("[0-9a-f]{64}")
internal val stagedAudioMimeTypes = setOf("audio/mpeg", "audio/wav", "audio/ogg", "audio/opus",
    "audio/aac", "audio/mp4", "audio/amr", "audio/flac", "audio/x-ms-wma", "audio/x-matroska",
    "audio/webm", "audio/3gpp", "audio/aiff", "audio/midi")
private fun safeText(value: String, max: Int) = value.length <= max &&
    value.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }

internal data class UploadStageIntent(
    val title: String, val speaker: String, val description: String,
    val categoryId: String, val subcategoryId: String,
    val expectedCategoryName: String, val expectedSubcategoryName: String,
    val mimeType: String,
) {
    fun encode(draft: AdminUploadDraft): JSONObject {
        require(safeText(title, 400) && title.isNotBlank() && safeText(speaker, 160) && safeText(description, 2000))
        require(destinationId.matches(categoryId) && destinationId.matches(subcategoryId))
        require(safeText(expectedCategoryName, 400) && safeText(expectedSubcategoryName, 400))
        require(mimeType in stagedAudioMimeTypes && hashPattern.matches(draft.sha256))
        return JSONObject().put("title", title).put("speaker", speaker).put("description", description)
            .put("categoryId", categoryId).put("subcategoryId", subcategoryId)
            .put("expectedCategoryName", expectedCategoryName).put("expectedSubcategoryName", expectedSubcategoryName)
            .put("sourceSha256", draft.sha256).put("sourceBytes", draft.byteCount).put("mimeType", mimeType)
    }
}

internal enum class UploadStageStep(val method: String, val suffix: String) {
    Reserve("PUT", ""), Verify("GET", ""), Source("PUT", "/source"), Commit("POST", "/commit"),
}

internal fun uploadStagePath(id: String, step: UploadStageStep): String {
    require(uuid4.matches(id))
    return "/admin/lesson-upload-drafts/$id${step.suffix}"
}

internal fun exactStageNumber(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal data class UploadStageReceipt(val draftId: String, val sourceSha256: String, val sourceBytes: Long)

internal fun interface UploadStageWire {
    suspend fun call(step: UploadStageStep, id: String, body: ByteArray?, mime: String?): JSONObject
}

/** One operator-authorized attempt. No background work, auto-retries or legacy fallback. */
internal class AdminUploadStageClient(private val wire: UploadStageWire) {
    suspend fun submit(outline: UploadStageIntent, draft: AdminUploadDraft, payload: ByteArray): UploadStageReceipt {
        require(uuid4.matches(draft.id) && draft.byteCount in 1..MAX_STAGE_BYTES.toLong())
        val intent = outline.encode(draft).toString().toByteArray(Charsets.UTF_8)
        require(intent.size <= 8192)
        require(payload.size.toLong() == draft.byteCount)
        val bytes = payload.copyOf()
        val digest = draft.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), digest))

        suspend fun call(step: UploadStageStep, body: ByteArray? = null, mime: String? = null): JSONObject {
            currentCoroutineContext().ensureActive()
            return wire.call(step, draft.id, body, mime).also { currentCoroutineContext().ensureActive() }
        }

        // GET omits metadata; only a matching PUT proves the frozen intent is the same.
        val reserved = prove(call(UploadStageStep.Reserve, intent, JSON), draft, null)
        val current = prove(call(UploadStageStep.Verify), draft, true)
        if (current.revision < reserved.revision) failProof()
        if (current.revision == 2L) {
            if (!current.verified) failProof()
        } else {
            if (!current.verified) {
                val staged = prove(call(UploadStageStep.Source, bytes, outline.mimeType), draft, true)
                if (!staged.verified) failProof()
            }
            val body = JSONObject().put("expectedRevision", 1).put("sourceSha256", draft.sha256)
                .put("sourceBytes", draft.byteCount).toString().toByteArray(Charsets.UTF_8)
            val committed = prove(call(UploadStageStep.Commit, body, JSON), draft, true)
            if (committed.revision != 2L || !committed.verified) failProof()
        }
        return UploadStageReceipt(draft.id, draft.sha256, draft.byteCount)
    }

    private data class State(val revision: Long, val verified: Boolean)
    private fun prove(json: JSONObject, draft: AdminUploadDraft, requireProof: Boolean?): State {
        if (json.opt("ok") != true || json.opt("draftId") != draft.id ||
            json.opt("sourceSha256") != draft.sha256 || exactStageNumber(json.opt("sourceBytes")) != draft.byteCount)
            failProof()
        val revision = exactStageNumber(json.opt("revision")) ?: failProof()
        val state = json.opt("state")
        if (!((revision == 1L && state == "reserved") || (revision == 2L && state == "committed_draft")))
            failProof()
        val verified = json.opt("sourceVerified")
        if (requireProof == true && verified !is Boolean) failProof()
        if (requireProof == null && json.has("sourceVerified")) failProof()
        return State(revision, verified == true)
    }

    private fun failProof(): Nothing = throw IOException("Private draft receipt not verified")
    private companion object { const val JSON = "application/json; charset=utf-8" }
}
