package com.ali.menbaradkshk.ui.adminbridge.upload

import org.junit.Assert.*
import org.junit.Test

class LocalOrderedOpusReviewSelectionTest {
    private fun draft(id: String, hash: String = id) = AdminUploadDraft(id, "$id.ogg", 1, hash.repeat(64))

    @Test fun platformReleaseFailureCannotSkipWorkerRetirementOrCandidateClose() {
        val events = mutableListOf<String>()
        releaseLocalReview(
            { events += "audio"; error("audio failure") },
            { events += "worker"; error("worker failure") },
            { events += "scratch" },
        )
        assertEquals(listOf("audio", "worker", "scratch"), events)
    }

    @Test fun orderStableAndPreparationDoesNotPlayOrKeep() {
        val m = LocalOrderedOpusReviewSelection()
        val a = draft("a"); val b = draft("b"); val c = draft("c")
        assertNull(m.prepare())
        assertTrue(m.toggle(a)); assertTrue(m.toggle(b)); assertTrue(m.toggle(a)); assertTrue(m.toggle(c))
        assertTrue(m.toggle(a))
        val r = m.prepare()!!
        assertEquals(listOf(b,c,a), r.originals)
        assertNull(m.explicitPlay(r))
        assertFalse(m.explicitKeepLocal(r))
        assertTrue(m.candidateReady(r))
        assertFalse(m.explicitKeepLocal(r))
        val play = m.explicitPlay(r)!!
        assertFalse(m.explicitKeepLocal(r))
        assertTrue(m.playEnded(play))
        assertTrue(m.explicitKeepLocal(r))
        assertTrue(m.finishedSaving(r))
        assertTrue(m.selected().isEmpty())
    }

    @Test fun deletionOrSameIdWithDifferentBytesRevokesCandidate() {
        val m = LocalOrderedOpusReviewSelection()
        val a = draft("a"); val b = draft("b")
        assertTrue(m.toggle(a)); assertTrue(m.toggle(b))
        val old = m.prepare()!!
        assertTrue(m.candidateReady(old)); val oldPlayback = m.explicitPlay(old)!!
        assertSame(old, m.updateAvailable(listOf(a, draft("b", "c"))))
        assertEquals(listOf(a), m.selected())
        assertTrue(m.toggle(draft("b", "c")))
        val current = m.prepare()!!
        assertFalse(m.playEnded(oldPlayback))
        assertFalse(m.candidateReady(old))
        assertTrue(m.candidateReady(current))
    }

    @Test fun interruptionNeedsAnotherExplicitPlayAndCancelCannotAuthorizeAnySave() {
        val m = LocalOrderedOpusReviewSelection()
        m.toggle(draft("a")); m.toggle(draft("b"))
        val r = m.prepare()!!
        assertTrue(m.candidateReady(r)); val first = m.explicitPlay(r)!!
        assertTrue(m.interrupted(first))
        assertFalse(m.playEnded(first))
        assertFalse(m.explicitKeepLocal(r))
        val replay = m.explicitPlay(r)!!
        assertFalse(m.playEnded(first))
        assertTrue(m.playEnded(replay))
        assertSame(r, m.cancel())
        assertFalse(m.explicitKeepLocal(r))
    }
}
