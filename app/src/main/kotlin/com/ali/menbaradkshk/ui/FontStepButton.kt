package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    /// ⚠️ تُقرأ **داخل** الحلقة ومعالج اللمس لا تُستعمل مفتاحاً لهما — انظر
    /// `pointerInput(Unit)` أدناه.
    val enabledNow by rememberUpdatedState(enabled)
    var held by remember { mutableStateOf(false) }

    // حلقة التكرار أثناء الضغط: مهلة أولى تمنع تكراراً غير مقصود من نقرة
    // عاديّة، ثمّ تسارع تدريجيّ حتى حدٍّ أدنى للفاصل.
    //
    // ⛔ **حلقة لا نهائيّة كانت هنا**: كان الشرط `while (true)`، ومعالج اللمس
    // مفتاحُه `pointerInput(enabled)`. فحين يبلغ الحجم الحدَّ الأقصى ينقلب
    // `enabled` إلى false، فيُعاد بناء المعالج ويُلغى الكوروتين وهو معلَّق في
    // `waitForUpOrCancellation()` — فلا يُنفَّذ `held = false` أبداً. تبقى
    // الحلقة تدور ٢٢ مرّة في الثانية تكتب في المخزن وترفع رقم المراجعة الذي
    // تراقبه شاشات التطبيق كلّها، إلى أن يغادر المستخدم الشاشة. حرارةٌ
    // وبطاريّةٌ وتقطيعٌ من رفع الإصبع إلى الخروج.
    //
    // العلاجان معاً: الشرط صار `enabledNow` فتقف الحلقة عند الحدّ من نفسها،
    // ومفتاح معالج اللمس صار `Unit` فلا يُعاد بناؤه ولا يُلغى قبل أن يرفع
    // المستخدم إصبعه.
    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        delay(HOLD_BEFORE_REPEAT_MS)
        var interval = FIRST_INTERVAL_MS
        while (enabledNow) {
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
            // ⚠️ المفتاح `Unit` لا `enabled`: مفتاحٌ متغيّر يعني إلغاء الكوروتين
            // في منتصف اللمسة (انظر التعليق على حلقة التكرار أعلاه).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!enabledNow) return@awaitEachGesture
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

/// نسبة خطوة الخطّ — مصدر واحد لصفّ القراءة وللشاشة المكبَّرة. كان الرقم
/// مكتوباً بيده في كل موضع، فيختلف إحساس الخطوة بين موضعين يضبطان الشيء نفسه.
const val FONT_STEP_RATIO = 0.12f

/**
 * 🔠 صفّ ضبط حجم الخطّ — **واحد لكل شاشات القراءة** (الأذكار والمصحف).
 *
 * كان لكل شاشة صفُّها الخاصّ: في الأذكار «حجم الخطّ − ٢٤ + … عريض» بأزرار
 * كبيرة، وفي المصحف زرّان صغيران بلا مسمّى ولا رقمٍ ولا مخرجٍ إلى الافتراضي.
 * فكان المستخدم يتعلّم الشيء مرّتين، ويجد في إحداهما أقلّ ممّا في الأخرى بلا
 * سبب. وهذا الصفّ يوحّدهما: نفس الترتيب، ونفس المعاني، ونفس الأزرار.
 *
 * - **الرقم زرٌّ**: نقرةٌ عليه تعيد الحجم الافتراضيّ — مخرجٌ آمن لمن كبّر أكثر
 *   ممّا ينبغي ولا يعرف كيف يعود.
 * - **الخطوة نسبيّة** ([stepFraction]) لا ثابتة: الثابتة قفزةٌ فجّة عند ٢٠
 *   نقطة وزحفٌ لا يُحسّ عند ١٠٠.
 * - **[current] دالّة لا قيمة**: زرّ الضغط المستمرّ يحسب خطوته من آخر قيمة في
 *   المخزن، وإلّا تجمّد التكبير عند خطوة واحدة ما دام الإصبع مضغوطاً.
 */
@Composable
fun ReadingFontRow(
    current: () -> Float,
    value: Float,
    min: Float,
    max: Float,
    default: Float,
    onChange: (Float) -> Unit,
    bold: Boolean,
    onBold: () -> Unit,
    modifier: Modifier = Modifier,
    stepFraction: Float = FONT_STEP_RATIO,
    buttonSize: Int = 52,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "حجم الخطّ",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        // ⭐ «أ−»/«أ+» لا «−»/«+»: الرمز المجرّد يحتمل معاني كثيرة (عدّ،
        // صوت، سرعة)، أمّا الحرف مع العلامة فيقطع بأنّ المقصود حجم الخطّ —
        // وأكثر من يحتاج التكبير لا يقرأ عناوين الأزرار الصغيرة أصلاً.
        FontStepButton(
            label = "أ−",
            enabled = value > min,
            onStep = { onChange(current().let { it - (it * stepFraction).coerceAtLeast(1f) }) },
            size = buttonSize,
            fontSize = (buttonSize * 0.40f).toInt(),
        )
        TextButton(onClick = { onChange(default) }) {
            Text(
                "${value.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Teal,
            )
        }
        FontStepButton(
            label = "أ+",
            enabled = value < max,
            onStep = { onChange(current().let { it + (it * stepFraction).coerceAtLeast(1f) }) },
            size = buttonSize,
            fontSize = (buttonSize * 0.40f).toInt(),
        )
        Spacer(Modifier.weight(1f))
        // ⭐ «عريض» — كلمةً لا أيقونة، ومكتوبةً بالوزن الذي تصفه فيراها
        // المستخدم قبل أن يقرأها. والحجم وحده لا يكفي لضعيف البصر: الثخانة
        // هي ما يفصل الحرف عن الخلفيّة.
        TextButton(onClick = onBold) {
            Text(
                "عريض",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = if (bold) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
