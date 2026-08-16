package com.ali.menbaradkshk.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

/**
 * 🔍 التكبير بالإصبعين على نصٍّ قابل للتمرير.
 *
 * **لماذا لا نستعمل `transformable` أو `detectTransformGestures`؟** لأنّ
 * كليهما يبتلع السحب بإصبع واحد أيضاً، فيموت تمرير القائمة تحته — وهو أسوأ
 * مقايضة ممكنة: تكسب إيماءةً نادرة وتفقد الإيماءة الأساسيّة.
 *
 * فهذه تعمل بقاعدة واحدة صارمة: **لا تتدخّل إلا بإصبعين فأكثر**. الإصبع
 * الواحد يمرّ كما هو إلى القائمة بلا استهلاك، فيبقى التمرير والنقر والضغط
 * المطوّل (للنسخ) كما كانت تماماً.
 *
 * وتُقاس النسبة بين المسافتين لا بين الإطارين المتتاليين، وتُطبَّق على الحجم
 * الذي بدأت به الإيماءة — فلا يتراكم الخطأ ولا «يهرب» الخطّ من تحت الإصبع.
 *
 * @param current الحجم اللحظيّ بالنقاط (يُقرأ عند بداية الإيماءة).
 * @param onChange يُنادى بالحجم الجديد؛ على المخزن حصرُه في مداه.
 */
fun Modifier.pinchFontSize(
    current: () -> Float,
    onChange: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // `Initial` كي نرى الإصبع الثاني **قبل** أن تلتقطه القائمة، وإلا
            // بدأت القائمة تمريرها فلم يصل إلينا شيء.
            var event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size < 2) continue

            val startSpread = spread(event) ?: continue
            val startSize = current()
            var active = false
            /// آخر حجم أُبلغ به فعلاً — أساس عتبة الإبلاغ أدناه.
            var lastSent = startSize

            while (event.changes.fastAny { it.pressed }) {
                event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size < 2) break
                val now = spread(event) ?: break
                val ratio = now / startSpread
                // عتبة قبل التفعيل: إصبعان يستقرّان على الشاشة بلا قصد تكبير
                // (إمساك الهاتف مثلاً) يجب ألّا يغيّرا شيئاً.
                if (!active && abs(ratio - 1f) < 0.08f) continue
                active = true
                // ⚠️ عتبة إبلاغ (٣٪ من آخر حجم) لا إبلاغٌ مع كل حدث لمس:
                // [onChange] يكتب في `SharedPreferences` **ويرفع رقم مراجعة
                // المخزن** الذي تراقبه شاشات التطبيق كلّها، وأحداث اللمس تصل
                // بمعدّل الشاشة — فضمّةٌ واحدة كانت تُطلق عشرات الكتابات
                // وعشرات موجات إعادة التركيب، وهو التقطيع نفسه الذي عُولج في
                // تكبير المصحف بعتبةٍ مثلها. و٣٪ من الحجم أدقّ من أن تُرى.
                val next = startSize * ratio
                if (abs(next - lastSent) >= lastSent * 0.03f) {
                    lastSent = next
                    onChange(next)
                }
                // الاستهلاك **بعد** التفعيل فقط: قبله تبقى الأحداث للقائمة.
                event.changes.fastForEach { it.consume() }
            }
        }
    }
}

/** المسافة بين أبعد إصبعين — أساس نسبة التكبير. */
private fun spread(event: androidx.compose.ui.input.pointer.PointerEvent): Float? {
    val points = event.changes.filter { it.pressed }.map { it.position }
    if (points.size < 2) return null
    val a = points[0]
    val b = points[1]
    val distance = kotlin.math.hypot(a.x - b.x, a.y - b.y)
    // مسافة شبه معدومة تجعل النسبة لانهائيّة — تُرفض بدل أن تقفز بالخطّ.
    return distance.takeIf { it > 24f }
}
