package com.ali.menbaradkshk.notification

import com.ali.menbaradkshk.data.canDeliverSupportOutboxKind
import org.junit.Assert.*
import org.junit.Test

class AdminOnlyContributionTest {
    @Test fun oldDecisionRoutesNeverReopenPublicSubmissionScreen() {
        assertTrue(isRetiredContributionNotice("submission", "lesson"))
        assertTrue(isRetiredContributionNotice("transcript", "not-set"))
        assertTrue(isRetiredContributionNotice(null, "my-submissions"))
        assertNull(NotificationPoster.destinationFor(mapOf("route" to "my-submissions", "lessonId" to "lesson-a")))
        assertNull(NotificationPoster.destinationFor(mapOf("type" to "transcript", "lessonId" to "lesson-a")))
        assertEquals("https://minbar-adkassahk.vercel.app/lesson/lesson-a", NotificationPoster.destinationFor(mapOf("type" to "lesson", "lessonId" to "lesson-a")))
        assertFalse(isRetiredContributionNotice("support", "support"))
    }

    @Test fun retiredLessonSupportOutboxDoesNotDisableNormalSupport() {
        assertFalse(canDeliverSupportOutboxKind("lesson_help"))
        assertFalse(canDeliverSupportOutboxKind(" LESSON_HELP "))
        assertTrue(canDeliverSupportOutboxKind("bug"))
        assertTrue(canDeliverSupportOutboxKind("suggestion"))
    }
}
