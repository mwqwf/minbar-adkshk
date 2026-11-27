package com.ali.menbaradkshk.ui.adminbridge.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Host supplies an explicitly requested review, inspection result, and live exact target names.
 * No lifecycle/launch effect or network is triggered from this composable.
 */
@Composable
internal fun AdminUploadPrivateReview(
    draft: AdminUploadDraft,
    intent: UploadStageIntent,
    inspectedMime: String,
    eligible: Boolean,
    busy: Boolean,
    onConfirm: (UploadStageIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val matches = intent.mimeType == inspectedMime && inspectedMime in stagedAudioMimeTypes
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("راجع المسودة الخاصة") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("العنوان: ${intent.title}")
                Text("القسم: ${intent.expectedCategoryName}")
                Text("القسم الفرعي: ${intent.expectedSubcategoryName}")
                if (intent.speaker.isNotBlank()) Text("الشيخ: ${intent.speaker}")
                if (intent.description.isNotBlank()) Text("الوصف: ${intent.description}")
                Text("الملف: ${draft.fileName}")
                Text("الحجم: ${draft.byteCount} بايت")
                Text("ستُرفع نسخة خاصة للتحضير فقط. لن يُنشر الدرس ولن يتغيّر الفهرس العام.")
                if (!eligible || !matches) Text("الرفع غير متاح لهذه المسودة. ستبقى النسخة على جهازك.")
            }
        },
        confirmButton = {
            TextButton(enabled = eligible && matches && !busy, onClick = { onConfirm(intent) }) {
                Text("أكّد حفظ المسودة الخاصة")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("رجوع") }
        },
    )
}
