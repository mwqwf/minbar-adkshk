package com.ali.menbaradkshk.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportOutboxCutoffTest {
    private val path = "/test/recording.m4a"
    private val code = "legacy_support_text_only"

    @Test fun exactCutoffIsRecognizedForDraftWithAudio() {
        assertTrue(isLegacySupportAttachmentCutoff(path, 410, code))
    }

    @Test fun ordinaryErrorsAndMediaFreeDraftsAreNotMislabeled() {
        assertFalse(isLegacySupportAttachmentCutoff("", 410, code))
        assertFalse(isLegacySupportAttachmentCutoff(" ", 410, code))
        assertFalse(isLegacySupportAttachmentCutoff(path, 410, null))
        assertFalse(isLegacySupportAttachmentCutoff(path, 410, "legacy_support_text_only "))
        assertFalse(isLegacySupportAttachmentCutoff(path, 410, "retired"))
        assertFalse(isLegacySupportAttachmentCutoff(path, 409, code))
        assertFalse(isLegacySupportAttachmentCutoff(path, null, code))
    }

    @Test fun cutoffNeverDispatchesOrRetriesEvenIfFailedBitIsMissing() {
        for (failed in listOf(false, true)) {
            assertFalse(canDispatchSupportDraft(failed, attachmentUnavailable = true))
            assertFalse(canRetrySupportDraft(failed, attachmentUnavailable = true))
        }
    }

    @Test fun ordinaryFailureCanRetryAndTextOnlyQueueRemainsDispatchable() {
        assertFalse(canDispatchSupportDraft(failed = true, attachmentUnavailable = false))
        assertTrue(canRetrySupportDraft(failed = true, attachmentUnavailable = false))
        assertTrue(canDispatchSupportDraft(failed = false, attachmentUnavailable = false))
        assertFalse(canRetrySupportDraft(failed = false, attachmentUnavailable = false))
    }
}
