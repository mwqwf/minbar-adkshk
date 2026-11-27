package com.ali.menbaradkshk.ui

internal enum class AudioContributionDecision(val label: String, val accepted: Boolean = false) {
    Accepted("قُبلت للنشر", true),
    AcceptedEdited("قُبلت بعد تعديل", true),
    Rejected("لم تُنشر"),
    Pending("قيد المراجعة"),
}

/** The submission response reports a moderation decision, not public lesson or audio availability. */
internal fun audioContributionDecision(status: String): AudioContributionDecision = when (status) {
    "approved" -> AudioContributionDecision.Accepted
    "approved_edited" -> AudioContributionDecision.AcceptedEdited
    "rejected" -> AudioContributionDecision.Rejected
    else -> AudioContributionDecision.Pending
}
