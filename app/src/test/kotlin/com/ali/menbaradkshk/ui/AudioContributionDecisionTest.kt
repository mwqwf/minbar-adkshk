package com.ali.menbaradkshk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioContributionDecisionTest {
    @Test fun approvalReportsAcceptanceWithoutAssertingPublication() {
        val accepted = audioContributionDecision("approved")
        val edited = audioContributionDecision("approved_edited")
        assertTrue(accepted.accepted)
        assertTrue(edited.accepted)
        assertEquals("قُبلت للنشر", accepted.label)
        assertEquals("قُبلت بعد تعديل", edited.label)
    }

    @Test fun rejectedPendingAndUnrecognizedStatusesCannotReportAcceptance() {
        assertEquals(AudioContributionDecision.Rejected, audioContributionDecision("rejected"))
        for (status in listOf("", "pending", "published", "unknown")) {
            val decision = audioContributionDecision(status)
            assertFalse(decision.accepted)
            assertEquals(AudioContributionDecision.Pending, decision)
        }
    }
}
