package com.ali.menbaradkshk.ui

import com.ali.menbaradkshk.util.formatDuration

// Completion is a persisted event, never an estimate from the seek position.
internal fun listeningRowStatus(completed: Boolean, savedPositionMs: Long, active: Boolean): String? = when {
    completed -> "أتممت الاستماع"
    !active && savedPositionMs > 3_000L -> "توقّفت عند ${formatDuration(savedPositionMs)}"
    else -> null
}
