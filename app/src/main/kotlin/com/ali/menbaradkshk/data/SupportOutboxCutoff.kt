package com.ali.menbaradkshk.data

internal fun isLegacySupportAttachmentCutoff(audioPath: String, status: Int?, machineCode: String?): Boolean =
    audioPath.isNotBlank() && status == 410 && machineCode == "legacy_support_text_only"

/** A cutoff receipt latches this draft locally; rollback must not make it use legacy public upload. */
internal fun canDispatchSupportDraft(failed: Boolean, attachmentUnavailable: Boolean): Boolean =
    !failed && !attachmentUnavailable

internal fun canRetrySupportDraft(failed: Boolean, attachmentUnavailable: Boolean): Boolean =
    failed && !attachmentUnavailable
