package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDraftRecoveryTest {
    @Test fun genericMissingOrNetworkErrorDoesNotAuthorizeLocalArchiving() {
        val initial = TranscriptDraftProgress()
        assertFalse(initial.missing().mayArchiveLocally)
        assertFalse(initial.conflict().mayArchiveLocally)
        assertFalse(initial.conflict().unverified().mayArchiveLocally)
        assertFalse(initial.missing().conflict().mayArchiveLocally)
        assertTrue(initial.conflict().missing().mayArchiveLocally)
        assertFalse(initial.conflict().missing().unverified().mayArchiveLocally)
    }

    @Test fun lessonRevisionAndTextBothMatchBeforeSuppressingNewSave() {
        val same = GuardedTranscriptDraftIntent("lesson_a", "12345678-1234-4234-aabc-123456abcdef", 17L, "نص الدرس")
        assertTrue(matchesLatestTranscriptDraft(same, "lesson_a", 17L, "نص الدرس"))
        assertFalse(matchesLatestTranscriptDraft(same, "lesson_a", 18L, "نص الدرس"))
        assertFalse(matchesLatestTranscriptDraft(same, "lesson_b", 17L, "نص الدرس"))
        assertFalse(matchesLatestTranscriptDraft(same, "lesson_a", 17L, "نص جديد"))
    }

    @Test fun lessonRowsAdvertiseDraftOnlyWhenPublishedAndReady() {
        assertTrue(knownAvailableTranscriptLesson("ready", 0, 100L))
        assertTrue(knownAvailableTranscriptLesson("ready", 100L, 100L))
        assertFalse(knownAvailableTranscriptLesson("ready", 101L, 100L))
        assertFalse(knownAvailableTranscriptLesson("ready", "0", 100L))
        assertFalse(knownAvailableTranscriptLesson("pending", 0, 100L))
    }
}
