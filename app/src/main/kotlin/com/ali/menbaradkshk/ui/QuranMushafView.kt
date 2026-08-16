package com.ali.menbaradkshk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ali.menbaradkshk.data.MushafGeometry
import com.ali.menbaradkshk.data.MushafRepository

/**
 * 🖼️ العرض المصوَّر للمصحف — صفحات المصحف الورقي مع تمييز الآية الجارية.
 *
 * **جوهر الشاشة أنّها ليست صورةً صامتة**: الآية التي تُتلى الآن تُظلَّل عليها
 * بلون ذهبيّ شفّاف (وقد تمتدّ على أسطر فتُظلَّل كلّها)، والصفحة تتبع التلاوة
 * من نفسها، والنقر على أي آية يبدأ التلاوة منها. بدون ذلك يصير المصوَّر
 * صورةً في متصفّح لا مصحفاً في تطبيق تلاوة.
 *
 * **لماذا نحسب مقاس الصورة بأيدينا بدل `ContentScale.Fit` وحده؟** لأنّ
 * الإحداثيات نسبةٌ من الصورة لا من الشاشة: لو تُركت الصورة تملأ صندوقاً
 * أكبر منها لصار بين حافّتها وحافّة الصندوق هامشٌ مجهول القدر، فتنزلق كل
 * المستطيلات عن مواضعها. بحساب المقاس صراحةً يشترك الرسم والصورة والّلمس
 * في نظام إحداثيات واحد بالضبط.
 */
@Composable
fun QuranMushafView(
    modifier: Modifier = Modifier,
    /// الرواية المعروضة — تحدّد مجلَّد الصور ومصدر الإحداثيات معاً، ولا
    /// يجوز أن يفترقا: صفحة رواية تحت إحداثيات أخرى إطارٌ على آية غيرها.
    riwayaId: String,
    pages: List<IntArray>,
    /// نسبة عرض صفحة هذه الرواية إلى ارتفاعها — تختلف بينها بعد قصّ
    /// الهوامش، وأيّ خطأ فيها يُزيح مستطيلات الآيات عن مواضعها.
    pageAspect: Float,
    pageStarts: IntArray,
    initialPage: Int,
    /// الآية الجارية بفهرسها المسطّح، أو `-1` فلا تمييز (قارئ سورة كاملة
    /// مثلاً — والتمييز الكاذب أسوأ من غيابه).
    activeAyah: Int,
    localPage: (Int) -> String?,
    onAyahTap: (Int) -> Unit,
    onPageSettled: (Int) -> Unit,
    /// نقرةٌ على فراغ الصفحة تُظهر الأدوات أو تُخفيها — الإيماءة المألوفة في
    /// عارضات الصور والكتب. والنقر على آيةٍ يبقى للتلاوة لا للأدوات.
    onChromeToggle: () -> Unit,
) {
    val state = rememberPagerState(
        initialPage = (initialPage - 1).coerceIn(0, MushafRepository.PAGE_COUNT - 1),
        pageCount = { MushafRepository.PAGE_COUNT },
    )

    // الصفحة المطلوبة قد تصل **بعد** أوّل تركيب (تُشتقّ من الفهرس حين يجهز)،
    // و`initialPage` لا يُقرأ إلا مرّة واحدة. وهذا الأثر لا ينازع المستخدم:
    // تقليبه يُحدِّث المصدر نفسه فيصير الشرط كاذباً.
    LaunchedEffect(initialPage) {
        val target = (initialPage - 1).coerceIn(0, MushafRepository.PAGE_COUNT - 1)
        if (target != state.currentPage) runCatching { state.scrollToPage(target) }
    }

    // 🎯 الصفحة تتبع التلاوة: حين تنتقل الآية الجارية إلى صفحة أخرى ننتقل
    // إليها بحركة هادئة. بلا هذا يظلّ القارئ يقلّب بيده خلف الصوت، فتضيع
    // فائدة التمييز بعد سطرين.
    LaunchedEffect(activeAyah, pageStarts) {
        if (activeAyah < 0 || pageStarts.isEmpty()) return@LaunchedEffect
        val target = MushafGeometry.pageOfAyah(pageStarts, activeAyah) - 1
        if (target != state.currentPage) runCatching { state.animateScrollToPage(target) }
    }

    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.settledPage }
            .collect { onPageSettled(it + 1) }
    }

    HorizontalPager(
        state = state,
        modifier = modifier.fillMaxSize(),
        // صفحة واحدة خارج الشاشة على كل جهة: تُجهَّز الصورة التالية قبل أن
        // يصل إليها الإصبع، وأكثر من ذلك يحمّل الذاكرة بلا فائدة محسوسة.
        beyondViewportPageCount = 1,
    ) { index ->
        MushafPage(
            riwayaId = riwayaId,
            pageAspect = pageAspect,
            pageNumber = index + 1,
            rects = pages.getOrElse(index) { IntArray(0) },
            activeAyah = activeAyah,
            localPath = localPage(index + 1),
            onAyahTap = onAyahTap,
            onChromeToggle = onChromeToggle,
        )
    }
}

@Composable
private fun MushafPage(
    riwayaId: String,
    pageAspect: Float,
    pageNumber: Int,
    rects: IntArray,
    activeAyah: Int,
    localPath: String?,
    onAyahTap: (Int) -> Unit,
    onChromeToggle: () -> Unit,
) {
    // 🔍 التكبير — قيمتان لا أكثر: مقياس وإزاحة، وتعودان إلى الأصل مع كل
    // صفحة جديدة فلا يجد القارئ نفسه في زاوية صفحة لم يكبّرها.
    var scale by remember(pageNumber) { mutableFloatStateOf(1f) }
    var offset by remember(pageNumber) { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        // بلا تكبير لا إزاحة: وإلّا انزلقت الصفحة عن مكانها بلمسة عابرة.
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }

    val highlight = SecondaryGold.copy(alpha = .28f)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // ⚠️ التحويل **مقيَّد بحال التكبير**: `transformable` يبتلع السحب
            // الأفقيّ بإصبع واحد، فكان تقليب الصفحات لا يعمل أصلاً — والتقليب
            // هو الفعل الأساسيّ في المصحف، والتكبير ثانويّ. فما دام المقياس
            // ١× يُترك السحب للمقلِّب، ولا يُفتح التكبير إلا بنقرة مزدوجة.
            .transformable(transform, enabled = scale > 1f)
            // نقرةٌ على الهامش حول الصفحة تُظهر الأدوات أيضاً: صفحة المصحف
            // أضيق من الشاشة، فيبقى شريطان فارغان فوقها وتحتها. ولو لم
            // يستجيبا لبدا التطبيق جامداً لمن نقر هناك — وهو أوّل ما يفعله
            // من يبحث عن زرّ الرجوع.
            .pointerInput(Unit) { detectTapGestures { onChromeToggle() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                // ⚠️ **نسبة أبعاد صريحة لا حسابُ مقاسٍ بالبكسل**: الحساب
                // اليدويّ (px ← dp ← px) كان يُصغّر الصفحة إلى نحو ثلاثة
                // أرباعها بلا سبب ظاهر. و`aspectRatio` يعطي أكبر صندوق بنسبة
                // الصورة داخل المتاح — وهو المطلوب بالضبط، وبلا حساب يخطئ.
                //
                // ولأنّ الصندوق صار بنسبة الصورة تماماً، يملؤه الرسم بلا
                // هوامش، فتبقى الإحداثيات (نسبةٌ من الصورة) منطبقةً عليه.
                .fillMaxSize()
                .aspectRatio(pageAspect)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                // ⚠️ اللمس **داخل** الطبقة المحوَّلة لا خارجها: Compose يعكس
                // تحويل الطبقة على إحداثيات اللمس، فتبقى النقرة صحيحة مهما
                // كبّر المستخدم أو أزاح — بلا حساب معكوس يدويّ يخطئ.
                .pointerInput(pageNumber, rects) {
                    detectTapGestures(
                        // نقرة مزدوجة = تكبير/إعادة — إيماءة واحدة يعرفها
                        // الناس من الصور، أوضح من إصبعين لكبير السنّ، ولا
                        // تنازع المقلِّب على السحب.
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = { position ->
                            if (rects.isEmpty()) {
                                onChromeToggle()
                                return@detectTapGestures
                            }
                            val x = (position.x / size.width * MushafGeometry.SCALE).toInt()
                            val y = (position.y / size.height * MushafGeometry.SCALE).toInt()
                            // ⚠️ **داخل مستطيل الآية فقط** تبدأ التلاوة.
                            // `ayahAt` تُرجع أقرب آية مهما بعُدت النقرة، فلو
                            // اعتمدنا عليها وحدها لصارت كلّ نقرةٍ على الهامش
                            // أو بين السطور تلاوةً لم تُطلَب — ولما بقيت
                            // للمستخدم وسيلةٌ لإظهار الأدوات أصلاً.
                            if (MushafGeometry.hitsAyah(rects, x, y)) {
                                val ayah = MushafGeometry.ayahAt(rects, x, y)
                                if (ayah >= 0) onAyahTap(ayah)
                            } else {
                                onChromeToggle()
                            }
                        },
                    )
                },
        ) {
            AsyncImage(
                // الملفّ المنزَّل يسبق الشبكة؛ وإلا فالرابط، وكاش coil يتكفّل
                // بألّا تُجلب الصفحة مرّتين.
                model = localPath?.let { "file://$it" } ?: MushafRepository.pageUrl(riwayaId, pageNumber),
                contentDescription = "صفحة $pageNumber من المصحف",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                // ⛔ **بلا صبغ**: صفحات مجمع الملك فهد صورٌ ملوّنة كاملة —
                // ورقٌ فاتح وحبرٌ داكن وإطارٌ مزخرف وعلامات آيٍ ملوّنة. وكان
                // هنا `ColorFilter.tint(SrcIn)` وُضع لصورٍ سابقة كانت حبراً
                // على شفافيّة، فصار يطمس هذه كلَّها بلونٍ واحد: صفحةٌ سوداء.
                //
                // ولأنّ ورقها فاتح دائماً، تُعرض كما هي في السمتين — كالمصحف
                // الورقيّ نفسه لا يتبدّل بتبدّل الإضاءة.
            )
            if (activeAyah >= 0) {
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / MushafGeometry.SCALE
                    val sy = size.height / MushafGeometry.SCALE
                    MushafGeometry.forEachRect(rects, activeAyah) { x1, y1, x2, y2 ->
                        drawRect(
                            color = highlight,
                            topLeft = Offset(x1 * sx, y1 * sy),
                            size = Size((x2 - x1) * sx, (y2 - y1) * sy),
                        )
                    }
                }
            }
        }
    }
}
