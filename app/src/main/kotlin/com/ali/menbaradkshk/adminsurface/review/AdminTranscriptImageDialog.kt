package com.ali.menbaradkshk.adminsurface.review

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException

@Composable
internal fun AdminTranscriptImageDialog(
    preview: AdminTranscriptImagePreview,
    item: TranscriptPreviewItem,
    onClose: () -> Unit,
) {
    if (!preview.available()) return
    var image by remember(preview, item) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(preview, item) { mutableStateOf(false) }
    LaunchedEffect(preview, item) {
        try {
            image = preview.firstPage(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("معاينة خاصة للصفحة الأولى") },
        text = {
            val bitmap = image
            if (bitmap != null && preview.available()) {
                Image(bitmap.asImageBitmap(), "الصفحة الأولى من المساهمة", Modifier.heightIn(max = 520.dp))
            } else if (failed) {
                Text("تعذّرت معاينة الصفحة. قد يكون الطلب تغيّر أو الصورة غير مدعومة.")
            } else {
                CircularProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
