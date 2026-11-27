package com.ali.menbaradkshk.ui.adminbridge.upload

/**
 * Foreground review selection, with no import, network or publication side effects. Keep one
 * instance per verified screen/lease. An asynchronous result must identify the exact request.
 * Existing temporary OpenDocument selection is not part of this model.
 */
internal class LocalOrderedOpusReviewSelection {
    enum class Step { SELECTING, PREPARING, READY, PLAYING, REVIEWED, SAVING }
    class Request internal constructor(drafts: List<AdminUploadDraft>) {
        val originals: List<AdminUploadDraft> = java.util.Collections.unmodifiableList(ArrayList(drafts))
    }
    class Playback internal constructor(internal val request: Request)
    private var playing: Playback? = null
    private var ordered = emptyList<AdminUploadDraft>()
    private var active: Request? = null
    var step = Step.SELECTING
        private set

    fun selected(): List<AdminUploadDraft> = ordered.toList()

    /** Tap order is the playback order; removing and adding appends the original again. */
    fun toggle(draft: AdminUploadDraft): Boolean {
        if (step != Step.SELECTING) return false
        val next = if (ordered.any { it.id == draft.id }) ordered.filterNot { it.id == draft.id }
        else if (ordered.size < MAX) ordered + draft else return false
        ordered = next
        return true
    }

    /** Return a revoked request for the UI to close/discard immediately. */
    fun updateAvailable(drafts: List<AdminUploadDraft>): Request? {
        val good = ordered.filter { selected -> drafts.count { it.id == selected.id } == 1 && selected in drafts }
        if (good == ordered) return null
        val revoked = cancel()
        ordered = good
        return revoked
    }

    fun prepare(): Request? {
        if (step != Step.SELECTING || ordered.size !in MIN..MAX) return null
        return Request(ordered).also {
            step = Step.PREPARING
            active = it
        }
    }

    fun candidateReady(request: Request): Boolean = advance(request, Step.PREPARING, Step.READY)
    fun explicitPlay(request: Request): Playback? =
        if (advance(request, Step.READY, Step.PLAYING)) Playback(request).also { playing = it } else null

    fun playEnded(playback: Playback): Boolean = finishAttempt(playback, Step.REVIEWED)
    fun interrupted(playback: Playback): Boolean = finishAttempt(playback, Step.READY)

    private fun finishAttempt(playback: Playback, next: Step): Boolean {
        if (playing !== playback || !advance(playback.request, Step.PLAYING, next)) return false
        playing = null
        return true
    }
    fun explicitKeepLocal(request: Request): Boolean = advance(request, Step.REVIEWED, Step.SAVING)

    fun finishedSaving(request: Request): Boolean {
        if (!advance(request, Step.SAVING, Step.SELECTING)) return false
        ordered = emptyList()
        active = null
        return true
    }

    fun cancel(): Request? = active.also {
        active = null
        playing = null
        step = Step.SELECTING
    }

    private fun advance(request: Request, from: Step, to: Step): Boolean {
        if (active !== request || step != from) return false
        step = to
        return true
    }

    private companion object { const val MIN = 2; const val MAX = 4 }
}

/** Resource revocation is independent: a platform release failure must not leave the other resources open. */
internal fun releaseLocalReview(vararg cleanup: () -> Unit) {
    cleanup.forEach { action -> try { action() } catch (_: Exception) { /* keep revoking */ } }
}
