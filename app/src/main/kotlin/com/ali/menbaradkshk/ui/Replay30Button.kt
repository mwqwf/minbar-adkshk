package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ⏪ **«أعِد ٣٠ ثانية»** — زرٌّ واحد كبير للفعل الأكثر تكراراً في الاستماع.
 *
 * **لماذا زرٌّ مستقلّ بهذا الحجم؟** لأنّ مستمع الدرس العلميّ يقول عشرات
 * المرّات في الدرس الواحد: «ما فهمتُ هذه الجملة، أعِدها». وهذا الفعل لم يكن
 * له مقصدٌ واحد واضح: زرُّ الترجيع صغيرٌ في صفٍّ من ثلاثة، والسحب على شريط
 * التقدّم يحتاج دقّةً لا يملكها من يستمع وهو يسوق أو يعمل.
 *
 * الرقم جزءٌ من رسم الأيقونة، فلا يُقصّ عند تكبير النص داخل زرٍّ ثابت الحجم.
 * يبقى الوصف «أعِد ثلاثين ثانية» متاحًا لقارئ الشاشة.
 *
 * الزرّ لا يعرف شيئاً عن المشغّل: كلّ مستدعٍ يحسب موضعه الجديد بنفسه من
 * `vm.playback`، فلا يُفتح مسار تشغيل ثانٍ بجوار القائم.
 */
@Composable
fun Replay30Button(
    onClick: () -> Unit,
    size: Dp,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(background, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "أعِد ثلاثين ثانية" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Replay30,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.60f),
        )
    }
}
