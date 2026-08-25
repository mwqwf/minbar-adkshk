package com.ali.menbaradkshk.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 📸 «أرفِق آخر لقطة شاشة» — بطاقة اختصار فوق منطقة الإرفاق.
 *
 * ⛔ تُعيد الصياغة 2026-08-25 — ولماذا: النسخة السابقة كانت تستعلم MediaStore
 * عن مجلد Screenshots، وذلك يستلزم `READ_MEDIA_IMAGES`، وهذا الإذن يُلزم
 * Play بنموذج «الوظيفة الأساسيّة للصور والفيديوهات». فحُذف الإذن نهائياً،
 * ونقرةُ البطاقة تفتح الآن **منتقي الصور في النظام** (Photo Picker) الذي
 * يعرض الأحدث أوّلاً — فاللقطة الأخيرة أوّل ما يراه المستخدم — ولا يحتاج
 * أيّ إذن على أيّ إصدار أندرويد. لا تُعِد أيّ استعلام MediaStore هنا.
 */
@Composable
fun RecentScreenshotChip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    // الإخفاء محلّي للشاشة: من أغلق البطاقة لا تُعاد إليه ما دام فيها.
    var dismissed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPick(uri) }

    if (!enabled || dismissed) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // فتح المنتقي مقصوراً على الصور: لا فيديو ولا ملفات أخرى.
        val open = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        Row(
            Modifier
                // البطاقة كلّها هدف نقر واحد كبير — أسهل من إصابة زرّ صغير.
                .clickable(onClick = open)
                .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Screenshot,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("أرفِق آخر لقطة شاشة", fontSize = 12.5.sp)
                Text(
                    "تظهر الأحدث أوّلاً",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("إرفاق")
            }
            IconButton(onClick = { dismissed = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إخفاء الاقتراح")
            }
        }
    }
}
