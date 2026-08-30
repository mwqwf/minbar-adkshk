package com.ali.menbaradkshk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.ContentState

/**
 * 👋 أوّل تشغيل — سؤال **واحد**، ثم لا شيء بعده أبداً.
 *
 * **لماذا وُجدت هذه الشاشة؟** أقوى ما في التطبيق (حفظ الدروس تلقائياً على
 * الواي فاي لتُسمَع بلا إنترنت) كان مدفوناً في الإعدادات، وأغلب الجمهور لا
 * يفتح الإعدادات قطّ.
 *
 * **ولماذا سؤال واحد فقط؟** سؤالُ «أي قسم تتابع؟» أُزيل عمداً (2026-08-30):
 * كان يوجّه هدف التنزيل القديم، أمّا محرّك الأولوية (معمارية «المكتبة
 * الكاملة») فيخدم المكتبة كلّها ويتعلّم اهتمام المستخدم من استماعه الفعلي —
 * والتنزيل صار شبه بلا كلفة بعد ضغط الأرشيف، فبقي القرار الوحيد الذي يملكه
 * المستخدم حقاً: أنُنزّل تلقائياً على الواي فاي أم لا. و«تخطَّ» ظاهرٌ دائماً.
 *
 * وتُعرض **بعد** وصول المحتوى الأوّل كي لا يبدو التطبيق معطوباً في أول لحظة.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel, content: ContentState, onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        AutoDownloadQuestion(vm = vm, onDone = onDone)
    }
}

/// السؤال الوحيد: أنُنزّل تلقائياً على الواي فاي؟ — الجواب زرّان لا أكثر.
@Composable
private fun AutoDownloadQuestion(
    vm: AppViewModel,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        QuestionTitle(
            "هل نحفظ لك الدروس تلقائياً حين تتصل بواي فاي، لتسمعها بلا إنترنت؟",
        )
        Text(
            "لن نستعمل بيانات هاتفك، ويمكنك تغيير هذا من الإعدادات متى شئت.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(
                onClick = {
                    // الواي فاي وحده: هذا نصّ السؤال حرفياً، فلا يجوز أن
                    // ينتهي الجواب بتحميلٍ على بيانات الهاتف. ومحرّك الأولوية
                    // يتولّى «ماذا يُنزَّل أولاً» بلا أي سؤال إضافي.
                    vm.setAutoDownloadTarget("recent")
                    vm.setAutoDownloadWifiOnly(true)
                    vm.setAutoDownloadEnabled(true)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(68.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("نعم", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    vm.setAutoDownloadEnabled(false)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(68.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("لا", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        SkipButton(onDone)
    }
}

@Composable
private fun QuestionTitle(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/// «تخطَّ» ظاهرٌ دائماً — لا حبس ولا سؤال إجباريّ.
@Composable
private fun SkipButton(onSkip: () -> Unit) {
    TextButton(
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 22.dp),
    ) {
        Text("تخطَّ", style = MaterialTheme.typography.titleLarge)
    }
}
