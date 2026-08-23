package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ⏪ **«أعِد ٣٠ ثانية»** — زرٌّ واحد كبير للفعل الأكثر تكراراً في الاستماع.
 *
 * **لماذا زرٌّ مستقلّ بهذا الحجم؟** لأنّ مستمع الدرس العلميّ يقول عشرات
 * المرّات في الدرس الواحد: «ما فهمتُ هذه الجملة، أعِدها». وهذا الفعل لم يكن
 * له مقصدٌ واحد واضح: زرُّ الترجيع صغيرٌ في صفٍّ من ثلاثة، والسحب على شريط
 * التقدّم يحتاج دقّةً لا يملكها من يستمع وهو يسوق أو يعمل.
 *
 * **ولماذا الرقم مكتوب؟** كي يعرف قبل أن يضغط كم سيرجع — الأيقونة وحدها
 * تعِد بشيء مجهول. والرقم بالأرقام العربيّة كبقيّة التطبيق.
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
            .clickable(onClick = onClick)
            .semantics { contentDescription = "أعِد ثلاثين ثانية" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Replay,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.42f),
            )
            Text(
                "٣٠ ث",
                color = tint,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.21f).sp,
            )
        }
    }
}
