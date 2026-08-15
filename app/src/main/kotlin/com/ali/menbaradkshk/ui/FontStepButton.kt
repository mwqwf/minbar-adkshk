package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 🔠 زرّ تغيير حجم الخطّ — **يكبّر ما دام مضغوطاً**.
 *
 * ضغطةٌ واحدة تُغيّر خطوةً واحدة (السلوك الاحتياطيّ لمن يريد ضبطاً دقيقاً)،
 * أمّا الإبقاء على الضغط فيواصل التكبير حتى يرفع المستخدم إصبعه عند الحجم
 * الذي يريده. وهذا هو المطلوب عملياً: بلوغ ١٢٠ نقطة من ١٩ بالنقر وحده
 * يحتاج نحو عشرين ضغطة — وهو عبثٌ على من ضعف بصره، وهو المقصود بالميزة.
 *
 * والتسارع مقصود أيضاً: تبدأ الخطوات بطيئة كي يستطيع الوقوف عند حجم قريب،
 * ثم تتسارع كي لا يطول الطريق إلى الأقصى.
 */
@Composable
fun FontStepButton(
    label: String,
    enabled: Boolean,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 52,
    fontSize: Int = 30,
) {
    val step by rememberUpdatedState(onStep)
    var held by remember { mutableStateOf(false) }

    // حلقة التكرار أثناء الضغط: مهلة أولى تمنع تكراراً غير مقصود من نقرة
    // عاديّة، ثمّ تسارع تدريجيّ حتى حدٍّ أدنى للفاصل.
    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        delay(HOLD_BEFORE_REPEAT_MS)
        var interval = FIRST_INTERVAL_MS
        while (true) {
            step()
            delay(interval)
            interval = (interval * ACCELERATION).toLong().coerceAtLeast(MIN_INTERVAL_MS)
        }
    }

    val tint = if (enabled) Teal else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f)
    Box(
        modifier = modifier
            .size(size.dp)
            .background(
                if (enabled) Teal.copy(alpha = .12f) else Color.Transparent,
                CircleShape,
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // خطوة فوريّة عند اللمس: الاستجابة تُطمئن أنّ الزرّ يعمل
                    // قبل أن تبدأ حلقة التكرار.
                    step()
                    held = true
                    waitForUpOrCancellation()
                    held = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

/// مهلة قبل بدء التكرار — أطول من نقرة عاديّة كي لا تتحوّل النقرة إلى قفزة.
private const val HOLD_BEFORE_REPEAT_MS = 350L
private const val FIRST_INTERVAL_MS = 140L
private const val MIN_INTERVAL_MS = 45L
private const val ACCELERATION = 0.88
