@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Category
import com.ali.menbaradkshk.data.ContentState
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.Subcategory
import com.ali.menbaradkshk.data.transcriptSearchAnchor
import com.ali.menbaradkshk.data.transcriptSearchWords
import com.ali.menbaradkshk.media.PlaybackUiState
import com.ali.menbaradkshk.util.normalizeArabic
import com.ali.menbaradkshk.util.progress
import com.ali.menbaradkshk.util.arabicCountLabel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.delay
import com.ali.menbaradkshk.util.subcategoriesCountLabel
import com.ali.menbaradkshk.util.lessonsCountLabel

// ----------------------------------------------------------------------------
// الرئيسية
// ----------------------------------------------------------------------------

@Composable
fun HomeScreen(
    vm: AppViewModel,
    state: ContentState,
    playback: PlaybackUiState,
) {
    val revision by vm.store.revision.collectAsState()

    // الريلات تُحسب هنا (سياق قابل للتركيب) ثم تُعرض داخل القائمة — نمط الأصل
    // حيث ContentRepository يعيد حساب الريلات ويُخطر الشاشات.
    val ward = remember(revision, state.lessons) { vm.content.dailyWard() }
    val rails = remember(revision, state.lessons) {
        listOf(
            Triple(featuredRailTitle, vm.content.featured(), false),
            Triple("تابع الاستماع", vm.content.continueListening(), true),
            Triple("لم تُكمله بعد", vm.content.unfinished(), true),
            Triple("الأكثر استماعاً هذا الأسبوع 🔥", vm.content.trending(), false),
            Triple("الأكثر استماعاً", vm.content.mostListened(), false),
            Triple("الأحدث", vm.content.newest(), false),
            Triple("استكمل قسمك", vm.content.continueSection(), false),
            Triple("قسم اليوم", vm.content.randomSectionToday(), false),
        )
    }
    // بلا أي سجل تكون «مقترح لك» نسخة طبق الأصل من «الأحدث» (ثالث قائمة
    // متطابقة للوافد الجديد)، فتُستبدل بـ«ابدأ من هنا»: محطّة الدروس القصيرة.
    val hasHistory = remember(revision, state.lessons) { vm.content.hasHistory() }
    val feed = remember(revision, state.lessons, hasHistory) {
        if (hasHistory) vm.content.recommended() else vm.content.shortStation()
    }
    val feedTitle = if (hasHistory) "مقترح لك" else "ابدأ من هنا"

    // 📴 وضع «أظهر المحفوظ فقط»: يُرشَّح **قبل** إسقاط المكرّر كي لا يحتجز
    // ريلٌ درساً غير منزَّل ثمّ يسقط من الجميع فلا يظهر في أيّ مكان.
    val savedIds = rememberSavedOnlyIds(vm)
    // تُقرأ هنا (سياق قابل للتركيب) لا داخل قائمة LazyColumn.
    val online = rememberOnline()

    // لا يتكرّر درس واحد بين الريلات: أوّل ريل يظهر فيه يحتفظ به، وما بعده
    // يسقطه — كي لا يرى المستخدم القائمة نفسها ثلاث مرات.
    val deduped = remember(rails, feed, savedIds) {
        val seen = mutableSetOf<String>()
        val uniqueRails = rails.map { (title, lessons, showProgress) ->
            Triple(title, lessons.savedOnly(savedIds).filter { seen.add(it.id) }, showProgress)
        }
        uniqueRails to feed.savedOnly(savedIds).filter { it.id !in seen }
    }
    val visibleRails = deduped.first
    val visibleFeed = deduped.second

    // ▶️ «تابع من حيث وقفت»: أكثر ما يفعله المستمع هو إكمال درس الأمس، وكان
    // ذلك يكلّفه تذكّر اسم الدرس ثمّ البحث عنه. يُحسب هنا **مرّة واحدة** داخل
    // `remember` لأنّ `position`/`duration` يفكّان JSON كاملاً عند كل نداء.
    // `playback.mediaId` في المفتاح كي تتحدّث البطاقة عند تغيّر الدرس الجاري:
    // حفظُ الموضع كتابةٌ «صامتة» لا ترفع `revision` (انظر LocalStore.setPosition).
    val resume = remember(revision, state.lessons, savedIds, playback.mediaId) {
        resumeItemOf(vm, state, savedIds)
    }

    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = { vm.content.requestDeepRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        // ٨٨dp أسفل القائمة: زرّ «شارك درساً» العائم (٥٦dp + ١٦dp هامشه) كان
        // يغطّي آخر بطاقة، والحشوة تُبقيها كاملةً فوقه.
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            // ⚠️ حين لا شبكة أصلاً يظهر SavedOnlyBar وحده: كان الشريطان
            // يتكدّسان معاً — رسالتا انقطاع متلاصقتان بنفس اللون والأيقونة —
            // و«إعادة المحاولة» بلا شبكة عبث. شريط الخطأ لفشل المزامنة
            // والشبكة موجودة (عطل خادم ونحوه) فقط.
            if (state.offline && state.error != null && online) {
                item { OfflineBanner(state.error) { vm.content.requestDeepRefresh() } }
            }

            // 📴 «لا يوجد إنترنت — أظهر المحفوظ فقط»: مفتاحٌ واحد بدل نقرةٍ
            // تنتهي بخطأ تشغيل على درسٍ غير منزَّل (انظر OfflineOnly.kt).
            item { SavedOnlyBar(vm) }

            // 🔔 **الطبقة المستمرّة** من تذكير التحديث.
            //
            // بقيّة الطبقات كلّها مؤقّتة: الإشعار يمرّ ويُمسح، والشاشة الكاملة
            // لا تعود قبل ٢٤ ساعة، وتذكير الدرس مرّتان في اليوم. فمن أغلق
            // الجميع بقي على نسخته أسابيع بلا أثرٍ يذكّره.
            //
            // وهذا الشريط لا يزول ما دامت النسخة قديمة — لكنّه **صغير ولا
            // يحجب شيئاً**: سطرٌ واحد فوق المحتوى. الإلحاح في البقاء لا في
            // الحجم، وهي الموازنة التي تجعله تذكيراً لا إزعاجاً.
            item { UpdateBanner(vm) }

            // ▶️ أوّل ما يراه المستمع: الدرس الذي تركه في منتصفه. نقرة واحدة
            // تعيده إلى الثانية نفسها، ولا تظهر البطاقة إن لم يكن هناك ما
            // يُتابَع (انظر `resumeItemOf`).
            if (resume != null) {
                item(key = "resume") { ResumeCard(vm, resume) }
            }

            // 📬 «ما يخصّني» — بطاقات مكتوبة بدل أيقونات الشريط العلوي.
            item { HomeInboxCards(vm) }

            // إجراءات سريعة: إذاعة منبر، وضع القيادة، حصادك.
            item { QuickActions(vm) }

            // تلميحات إرشادية للمستخدم الجديد (تظهر مرة واحدة).
            item {
                HintCard(
                    vm = vm,
                    hintKey = "library_tab",
                    icon = Icons.Filled.Folder,
                    text = "كل الأقسام والسلاسل تجدها في تبويب «المكتبة» أسفل الشاشة.",
                    actionLabel = "فتح المكتبة",
                    onAction = { vm.openRoot(Route.Library) },
                )
            }
            item {
                HintCard(
                    vm = vm,
                    hintKey = "contribute_fab",
                    icon = Icons.Filled.MicExternalOn,
                    text = "لديك تسجيل مفيد؟ شاركه من زر «شارك درساً» وسيُنشر بعد موافقة المشرفين.",
                )
            }
            // تلميح «حول» — تذكير خفيف لمرة واحدة، لا يظهر في الوهلة الأولى
            // بل بعد مدة قصيرة من أول فتح للتطبيق، ثم يختفي ذاتياً كالبقية.
            item {
                var aboutHintReady by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (vm.store.hintSeen("about_intro")) return@LaunchedEffect
                    val elapsed = System.currentTimeMillis() - vm.store.firstOpenMs()
                    val waitMs = 3 * 60_000L - elapsed
                    if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                    aboutHintReady = true
                }
                if (aboutHintReady) {
                    HintCard(
                        vm = vm,
                        hintKey = "about_intro",
                        icon = Icons.Filled.Info,
                        text = "اطّلع على «حول التطبيق» من الإعدادات لمعرفة المزيد عنا: نسخة الويب، قناتنا على يوتيوب، ومصدر التطبيق المفتوح.",
                        actionLabel = "فتح «حول»",
                        onAction = { vm.open(Route.About) },
                    )
                }
            }

            // وِرد اليوم يسقط في وضع «المحفوظ فقط» إن لم يكن منزَّلاً: بطاقةٌ
            // كبيرة نقرتها تنتهي بخطأ تشغيل أسوأ من غيابها.
            if (ward != null && (savedIds == null || ward.id in savedIds)) {
                item { DailyWardCard(vm, ward, feed) }
            }

            visibleRails.forEach { (title, lessons, showProgress) ->
                // «مختارات المنبر» ريلٌ ببطاقات كبيرة مكتوبة (انظر
                // `FeaturedCard`)، وبقيّة الريلات على حالها.
                if (title == featuredRailTitle) {
                    featuredRailItem(vm, title, lessons, playback)
                } else {
                    railItem(vm, title, lessons, playback, showProgress = showProgress)
                }
            }

            if (visibleFeed.isNotEmpty()) {
                item { RailHeader(feedTitle) }
                // ⛔ لا يدخل `revision` في المفتاح: كان تبدّله (نبضتان كل خمس
                // ثوانٍ أثناء التشغيل) يُتلف تركيبة كل صفّ ويعيد بناءها.
                items(visibleFeed, key = { "feed-${it.id}" }) { lesson ->
                    AudioItem(vm, lesson, visibleFeed, playback, showActions = false)
                }
            }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.lessons.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp)) {
                        Text(
                            "يجب الاتصال بالإنترنت أول مرة لتحميل المحتوى. بعد ذلك يمكنك استخدام التطبيق دون إنترنت.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// ▶️ تابع من حيث وقفت
// ----------------------------------------------------------------------------

/// الدرس الذي تركه المستمع في منتصفه، بموضعه ومدّته.
class ResumeItem(val lesson: Lesson, val positionMs: Long, val durationMs: Long)

/**
 * ▶️ أحدث درس **لم يُكمَل** وله موضع استماع محفوظ.
 *
 * ⚠️ دالّة عاديّة لا قابلة للتركيب عن قصد: `positions()`/`durations()`
 * و`completedIds()` تفكّ JSON كاملاً في كل نداء، فتُقرأ كلّها **مرّة واحدة**
 * من داخل `remember` في الرئيسية، لا في جسم التركيب.
 *
 * ولا تُرجع شيئاً حين لا استماع سابق، ولا لدرسٍ اكتمل — سواء بعلامة «مكتمل»
 * المحفوظة أو بموضعٍ بلغ آخر نصف دقيقة من المدّة.
 */
private fun resumeItemOf(
    vm: AppViewModel,
    state: ContentState,
    savedIds: Set<String>?,
): ResumeItem? {
    val completed = vm.store.completedIds().toSet()
    val positions = vm.store.positions()
    val durations = vm.store.durations()
    for (id in vm.store.recentPlayedIds()) {
        if (id in completed) continue
        // 📴 وضع «أظهر المحفوظ فقط»: بطاقةٌ كبيرة نقرتها تنتهي بخطأ تشغيل
        // أسوأ من غيابها (نفس منطق «وِرد اليوم» أعلاه).
        if (savedIds != null && id !in savedIds) continue
        val lesson = state.lessonById[id] ?: continue
        if (lesson.audioUrl.isBlank()) continue
        val position = positions[id] ?: 0L
        val duration = durations[id] ?: 0L
        // أقلّ من نصف دقيقة: لم يبدأ فعلاً، وعرضُه «متابعة» مبالغة.
        if (position < 30_000L) continue
        // آخر نصف دقيقة: انتهى عملياً ولو لم يُعلَّم «مكتمل».
        if (duration > 0L && position >= duration - 30_000L) continue
        return ResumeItem(lesson, position, duration)
    }
    return null
}

/**
 * ▶️ بطاقة «تابع من حيث وقفت» — أوّل ما يراه المستمع في الرئيسية.
 *
 * المواضع محفوظة أصلاً، لكنّ العودة إليها كانت تحتاج تذكّر اسم الدرس ثمّ
 * البحث عنه. هنا: الاسم وشريط التقدّم ودقيقة التوقّف وزرّ تشغيل كبير — ونقرةٌ
 * واحدة تعيده إلى الثانية نفسها عبر `Route.Lesson(id, startAtMs)`.
 */
@Composable
private fun ResumeCard(vm: AppViewModel, item: ResumeItem) {
    val lesson = item.lesson
    val title = remember(lesson.id, lesson.title) { sectionAwareTitle(vm, lesson) }
    val accent = brandTintOnSurface(GreenBrand)
    val minutes = (item.positionMs / 60_000L).toInt()
    // «الدقيقة 0» لا تعني شيئاً لمن يقرأ — تُقال «من أوّله» بدلها.
    val where = if (minutes >= 1) "تابع من الدقيقة $minutes" else "تابع من أوّله"
    val open = { vm.open(Route.Lesson(lesson.id, item.positionMs)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = open)
            .padding(14.dp),
    ) {
        Text(
            "تابع من حيث وقفت",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lesson.speaker.isNotBlank()) {
                    Text(
                        lesson.speaker,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    where,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            // هدف لمس ٥٦dp — أكبر من الحدّ الأدنى، فهو الفعل الأهمّ في البطاقة.
            IconButton(onClick = open, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.PlayCircleFilled,
                    contentDescription = "تشغيل من حيث وقفت",
                    tint = accent,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        if (item.durationMs > 0L) {
            Spacer(Modifier.height(10.dp))
            ClassicLinearProgress(
                progress = progress(item.positionMs, item.durationMs),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = accent,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.railItem(
    vm: AppViewModel,
    title: String,
    lessons: List<Lesson>,
    playback: PlaybackUiState,
    showProgress: Boolean = false,
) {
    if (lessons.isEmpty()) return
    item(key = "rail-$title") {
        Column(horizontalAlignment = Alignment.Start) {
            RailHeader(title)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                items(lessons, key = { "$title-${it.id}" }) { lesson ->
                    AudioCard(vm, lesson, lessons, playback, showProgress = showProgress)
                }
            }
        }
    }
}

/// عنوان ريل «مختارات المنبر» — مكتوب مرّة واحدة كي يبقى الريل وبطاقته
/// المخصّصة مرتبطين ولو تغيّر العنوان.
const val featuredRailTitle = "مختارات المنبر ⭐"

/// ريل «مختارات المنبر» ببطاقاته الكبيرة المكتوبة.
private fun androidx.compose.foundation.lazy.LazyListScope.featuredRailItem(
    vm: AppViewModel,
    title: String,
    lessons: List<Lesson>,
    playback: PlaybackUiState,
) {
    if (lessons.isEmpty()) return
    item(key = "rail-$title") {
        Column(horizontalAlignment = Alignment.Start) {
            RailHeader(title)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                items(lessons, key = { "$title-${it.id}" }) { lesson ->
                    FeaturedCard(vm, lesson, lessons, playback)
                }
            }
        }
    }
}

/**
 * ⭐ بطاقة «مختارات المنبر» — **العنوان هو الصورة**.
 *
 * كانت البطاقة تملأ نفسها بأيقونة القسم العملاقة (نجمة أو كتاب) حين لا صورة
 * للدرس، وهو رمزٌ لا يعني شيئاً لمن ينظر: كل بطاقات القسم الواحد تتشابه فلا
 * يميّز بينها شيء. فحلّ محلّه **اسم الدرس بخطّ كبير داخل البطاقة**: عنوانٌ
 * يُقرأ خيرٌ من رمزٍ لا يدلّ.
 *
 * والخلفيّة لون القسم نفسه (هويّة `CategoryColors` بلا تغيير) مع تدرّج داكن
 * من الأسفل يضمن تباين النصّ الأبيض مهما فتح اللون — كما في بقيّة أسطح
 * التطبيق المتدرّجة (`SectionCard` و`DailyWardCard`).
 */
@Composable
private fun FeaturedCard(
    vm: AppViewModel,
    lesson: Lesson,
    playlist: List<Lesson>,
    playback: PlaybackUiState,
) {
    val accent = colorForCategory(lesson.categoryId)
    val active = playback.mediaId == lesson.id && lesson.id.isNotBlank()
    // العناوين الرقميّة الخام تُعرض باسم قسمها الفرعي (انظر sectionAwareTitle).
    val title = remember(lesson.id, lesson.title) { sectionAwareTitle(vm, lesson) }
    Box(
        modifier = Modifier
            .width(238.dp)
            .height(136.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(accent, Slate)))
            .clickable { vm.openPlayer(lesson, playlist) },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000)),
                    ),
                ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (lesson.speaker.isNotBlank()) {
                Text(
                    lesson.speaker,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) {
            Icon(
                Icons.Filled.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp),
            )
        }
    }
}

/**
 * 📬 بطاقات «ما يخصّني»: الإشعارات، مساهماتي، تنزيلاتي — **بأسمائها مكتوبة**.
 *
 * كانت هذه المداخل أيقونات في الشريط العلويّ (سبع أيقونات بلا كلمة واحدة،
 * تزاحم العنوان حتى يكاد يُقصّ). وجمهور التطبيق لا يفكّ رموز الأيقونات، فما
 * لا اسم له لا يُفتح. البطاقة هنا: أيقونة كبيرة واسمٌ تحتها، وشارة العدد غير
 * المقروء على «الإشعارات» كما كانت على الجرس تماماً.
 */
@Composable
private fun HomeInboxCards(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        StreakLine(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NotificationsCard(vm, Modifier.weight(1f))
            MySubmissionsCard(vm, Modifier.weight(1f))
            InboxCard(
                icon = Icons.Filled.Download,
                label = "تنزيلاتي",
                // درجة فاتحة على أسطح السمة الداكنة (انظر brandTintOnSurface).
                tint = brandTintOnSurface(OrangeBrand),
                modifier = Modifier.weight(1f),
                onClick = { vm.open(Route.Downloads) },
            )
        }
    }
}

/// سطر السلسلة 🔥 — كان شريحة برقمٍ مجرّد في الشريط العلويّ لا يُفهم معناها،
/// فصار جملةً مكتوبة في المتن. يظهر من يومين فصاعداً، والنقر يفتح «حصادك».
@Composable
private fun StreakLine(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val streak = remember(revision) { vm.store.streakDays() }
    if (streak < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { vm.open(Route.Stats) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = Gold,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "سلسلتك: ${arabicCountLabel(streak, "يومٌ واحد", "يومان", "أيام", "يوماً")}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun NotificationsCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val items by vm.notifications.collectAsState()
    val revision by vm.store.revision.collectAsState()
    val unread = remember(items, revision) {
        val lastSeen = vm.store.notificationLastSeenMs()
        visibleNotifications(vm, items).count { it.createdAtMs > lastSeen }
    }
    InboxCard(
        icon = Icons.Filled.NotificationsNone,
        label = "الإشعارات",
        tint = brandTintOnSurface(Teal),
        badge = if (unread > 0) "$unread" else null,
        modifier = modifier,
        onClick = { vm.open(Route.Notifications) },
    )
}

/// «مساهماتي» — تظهر لمن ساهم من قبل فقط (نفس شرط زرّ الشريط السابق)، وعليها
/// نقطة إذا حُسمت مساهمة بعد آخر زيارة.
@Composable
private fun MySubmissionsCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    var user by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (user == null) return
    // الدخول المجهول يقع لكل مستخدم عند أوّل إقلاع، فوجود الهوية وحده لا
    // يعني مساهماً. من لم يساهم قطّ: لا بطاقة تزحم الصفّ، ولا مستمعا
    // Firestore يعملان طوال بقاء الرئيسية على مجموعتين فارغتين عنده.
    val revision by vm.store.revision.collectAsState()
    val contributed = remember(revision) { vm.hasContributedBefore() }
    if (!contributed) return

    val flow = remember { vm.submissions.mine().catch { emit(emptyList()) } }
    val submissions by flow.collectAsState(initial = emptyList())
    val transcriptsFlow = remember { vm.transcripts.mine().catch { emit(emptyList()) } }
    val transcriptItems by transcriptsFlow.collectAsState(initial = emptyList())
    val hasNewDecision = remember(submissions, transcriptItems, revision) {
        val seen = vm.store.submissionsLastSeenMs()
        submissions.any { it.status != "pending" && it.decidedAtMs > seen } ||
            transcriptItems.any { it.status != "pending" && it.decidedAtMs > seen }
    }
    InboxCard(
        icon = Icons.Filled.Outbox,
        label = "مساهماتي",
        tint = brandTintOnSurface(BlueBrand),
        badge = if (hasNewDecision) " " else null,
        modifier = modifier,
        onClick = { vm.open(Route.MySubmissions) },
    )
}

/// بطاقة واحدة: أيقونة كبيرة والاسم مكتوب تحتها، وهدف لمسٍ يفوق ٤٨dp بكثير.
@Composable
private fun InboxCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier.heightIn(min = 96.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (badge != null) {
                androidx.compose.material3.BadgedBox(
                    badge = { androidx.compose.material3.Badge { Text(badge) } },
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(34.dp))
                }
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/// حالة «القائمة فارغة» مفرّقة إلى ثلاث: أثناء التحميل مؤشّر دوّار، وعند انقطاع
/// الاتصال رسالة الاتصال مع «إعادة المحاولة»، وغير ذلك رسالة الفراغ الصحيحة —
/// كي لا يُنسب الفراغ للإنترنت وهو محمّل أصلاً أو ما زال قيد التحميل.
@Composable
private fun EmptyOrLoadingState(
    loading: Boolean,
    offline: Boolean,
    offlineMessage: String,
    emptyMessage: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            loading -> CircularProgressIndicator()
            offline -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    offlineMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }

            else -> Text(
                emptyMessage,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun RailHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun QuickActions(vm: AppViewModel) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // درجات فاتحة على أسطح السمة الداكنة (انظر brandTintOnSurface).
        item { QuickChip(Icons.Filled.Radio, "إذاعة منبر", brandTintOnSurface(Teal)) { vm.open(Route.Radio) } }
        item { QuickChip(Icons.Filled.DirectionsCar, "وضع القيادة", brandTintOnSurface(BlueBrand)) { vm.open(Route.Car) } }
        item { QuickChip(Icons.Filled.Insights, "حصادك", brandTintOnSurface(OrangeBrand)) { vm.open(Route.Stats) } }
        // ⛔ «تنزيلاتي» ليست هنا عمداً: مكانها **صفّ أدوات الشريط العلوي**
        // مع البحث والإشعارات والمساهمات (قرار صريح من صاحب التطبيق).
        // هذه الشرائح لمداخل الاستماع، وتلك لمداخل «ما يخصّني».
    }
}

@Composable
private fun QuickChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
    )
}

@Composable
private fun DailyWardCard(vm: AppViewModel, ward: Lesson, feed: List<Lesson>) {
    Box(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Teal, Slate)),
                RoundedCornerShape(12.dp),
            )
            .clickable { vm.openPlayer(ward, listOf(ward) + feed) }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WbSunny, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("وِرد اليوم", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    ward.displayTitle,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(Icons.Filled.PlayCircleFilled, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

// ----------------------------------------------------------------------------
// المكتبة (الأقسام) — التغيير الوحيد المُبقى: الأقسام صفحة مستقلة عن الرئيسية.
// ----------------------------------------------------------------------------

@Composable
fun LibraryScreen(vm: AppViewModel, state: ContentState) {
    // عدّ الفروع مرّة لكل تغيّر محتوى: المسح داخل الصفّ كان يعيد مسح كل
    // الفروع لكل بطاقة قسم في كل إعادة تركيب.
    val counts = remember(state.subcategories) {
        state.subcategories.groupingBy(Subcategory::categoryId).eachCount()
    }
    // 📴 «المحفوظ فقط»: لا يبقى إلا قسمٌ فيه درسٌ منزَّل فعلاً — فتحُ قسمٍ
    // فارغ بلا إنترنت طريقٌ مسدود لا فائدة في عرضه.
    val savedIds = rememberSavedOnlyIds(vm)
    val categories = remember(state.categories, state.lessons, savedIds) {
        if (savedIds == null) {
            state.categories
        } else {
            val withSaved = state.lessons
                .filter { it.id in savedIds }
                .mapTo(HashSet()) { it.categoryId }
            state.categories.filter { it.id in withSaved }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SavedOnlyBar(vm) }
        items(categories, key = Category::id) { category ->
            val count = counts[category.id] ?: 0
            SectionCard(
                categoryId = category.id,
                name = category.name,
                subtitle = if (count > 0) subcategoriesCountLabel(count) else null,
                onClick = { vm.open(Route.Category(category.id)) },
            )
        }
        // كانت الشاشة تبقى بيضاء أثناء التحميل، ورسالة الفراغ تطلب سحباً
        // للتحديث لا وجود له في هذا التبويب — فوُحِّدت مع الشاشتين الشقيقتين.
        if (categories.isEmpty()) {
            item {
                EmptyOrLoadingState(
                    // في وضع «المحفوظ فقط» الفراغ ليس فراغ محتوى ولا انقطاع
                    // شبكة: لم يُحفظ شيء بعد، وهذا ما يجب أن يُقال.
                    loading = state.loading && savedIds == null,
                    offline = state.offline && savedIds == null,
                    offlineMessage = "يجب الاتصال بالإنترنت أول مرة لتحميل الأقسام. بعد ذلك يمكنك التصفّح دون إنترنت.",
                    emptyMessage = if (savedIds != null) {
                        "لا يوجد درس محفوظ على جهازك بعد. حمّل دروساً وأنت متصل لتسمعها بلا إنترنت."
                    } else {
                        "لا توجد أقسام."
                    },
                    onRetry = { vm.content.requestDeepRefresh() },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// شاشة الأقسام الفرعية (SubcategoriesScreen في الأصل)
// ----------------------------------------------------------------------------

@Composable
fun CategoryScreen(vm: AppViewModel, categoryId: String, state: ContentState) {
    val revision by vm.store.revision.collectAsState()
    val subs = remember(revision, state.subcategories) { vm.content.subcategoriesForCategory(categoryId) }
    var certificateFor by remember { mutableStateOf<Subcategory?>(null) }

    if (subs.isEmpty()) {
        EmptyOrLoadingState(
            loading = state.loading,
            // رسالة الاتصال لا تصحّ إلا حين لا نسخة محفوظة أصلاً؛ ومع وجود نسخة
            // يكون الفراغ فراغ قسم لا انقطاع شبكة.
            offline = state.offline && state.subcategories.isEmpty(),
            offlineMessage = "يجب الاتصال بالإنترنت أول مرة لتحميل الأقسام. بعد ذلك يمكنك التصفّح دون إنترنت.",
            emptyMessage = "لا توجد أقسام فرعية في هذا القسم.",
            onRetry = { vm.content.requestDeepRefresh() },
        )
        return
    }

    val accent = colorForCategory(categoryId)
    val bulk by vm.bulkDownload.collectAsState()
    // تقدّم السلاسل وحالة المتابعة تُحسب مرّة واحدة للقسم كلّه: كانت تُستدعى
    // داخل كل عنصر بلا `remember`، و`seriesProgress` يرشّح الدروس ويفكّ
    // «المكتملة» من JSON في كل نداء — أي n مرّة مع كل إعادة تركيب.
    val progressBySub = remember(revision, state.lessons, subs) {
        subs.associate { it.id to vm.content.seriesProgress(it.id) }
    }
    val followedSubs = remember(revision) { vm.store.followedSubcategories().toSet() }
    LazyColumn(
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HintCard(
                vm = vm,
                hintKey = "follow_subcategory",
                icon = Icons.Filled.NotificationsActive,
                text = "اضغط جرس أي قسم فرعي لمتابعته — يصلك إشعار بكل درس جديد فيه.",
            )
        }
        // تحميل القسم الرئيسي كاملاً (بكل أقسامه الفرعية) دفعة واحدة.
        item {
            val categoryLessons = remember(revision, state.lessons) {
                vm.content.lessonsForCategory(categoryId)
            }
            val active = bulk != null
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (active) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("${bulk?.done}/${bulk?.total}", style = MaterialTheme.typography.bodyMedium)
                        // موقوف ⇒ لا دوّار: الدوّار المتحرّك بلا تقدّم يوحي بعمل جارٍ.
                        if (bulk?.paused == true) {
                            Icon(
                                Icons.Filled.PauseCircleFilled,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            bulk?.label.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        DownloadQueueControls(vm)
                    }
                }
                // ⚠️ الزرّ يبقى ظاهراً ولو كان الطابور يعمل: `downloadLessons`
                // تُلحق بالطابور الجاري وتُسقط المكرّر أصلاً، وكان إخفاؤه يمنع
                // إضافة قسم كامل حتى ينتهي كلّ ما يجري — ولو كان درساً واحداً
                // من قسم آخر على شبكة بطيئة.
                DownloadAllButton(
                    text = if (active) {
                        "أضِف القسم كاملاً إلى الطابور"
                    } else {
                        "تحميل القسم كاملاً (${lessonsCountLabel(categoryLessons.size)})"
                    },
                    enabled = categoryLessons.isNotEmpty(),
                    onClick = {
                        val name = state.categoryById[categoryId]?.name ?: "القسم"
                        vm.requestBulkDownload(name, categoryLessons)
                    },
                )
            }
        }
        // ⛔ لا `revision` في المفتاح — تبدّله كان يهدم تركيبة كل عنصر.
        items(subs, key = { it.id }) { sub ->
            val (done, total) = progressBySub[sub.id] ?: (0 to 0)
            val complete = total > 0 && done >= total
            val ratio = if (total > 0) done.toFloat() / total else 0f
            val following = sub.id in followedSubs
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(accent, Slate)), RoundedCornerShape(12.dp)),
            ) {
                Column {
                    ListItem(
                        modifier = Modifier.clickable { vm.open(Route.Subcategory(sub.id)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                iconForCategory(categoryId),
                                contentDescription = null,
                                tint = if (complete) PositiveOnDark else GoldOnDark,
                            )
                        },
                        headlineContent = {
                            Text(sub.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = if (total > 0) {
                            {
                                Text(
                                    if (complete) "أتممت السلسلة ✓" else "$done من $total",
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            null
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    vm.toggleFollow(sub.id)
                                    vm.showMessage(
                                        when {
                                            following -> "أُلغيت متابعة «${sub.name}»"
                                            // لا اشتراك يقع والإشعارات موقوفة،
                                            // فالوعد كان يكذب على من أوقفها.
                                            !vm.store.notificationsEnabled() ->
                                                "فعِّل الإشعارات لتصلك دروس «${sub.name}» الجديدة"
                                            else -> "ستصلك إشعارات دروس «${sub.name}» الجديدة"
                                        },
                                    )
                                }) {
                                    Icon(
                                        if (following) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                                        contentDescription = if (following) "إلغاء متابعة القسم" else "متابعة القسم",
                                        tint = Color.White,
                                    )
                                }
                                if (complete) {
                                    IconButton(onClick = { certificateFor = sub }) {
                                        Icon(
                                            Icons.Filled.WorkspacePremium,
                                            contentDescription = "شهادة الإتمام",
                                            tint = GoldOnDark,
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (total > 0) {
                        ClassicLinearProgress(
                            progress = ratio,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                .height(6.dp),
                            color = if (complete) PositiveOnDark else GoldOnDark,
                            trackColor = Color.White.copy(alpha = 0.24f),
                        )
                    }
                }
            }
        }
    }

    certificateFor?.let { sub ->
        CompletionCertificateDialog(
            vm = vm,
            subcategory = sub,
            onDismiss = { certificateFor = null },
        )
    }
}

@Composable
private fun CompletionCertificateDialog(vm: AppViewModel, subcategory: Subcategory, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = GoldOnDark, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("شهادة إتمام", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "أتممت الاستماع إلى سلسلة\n«${subcategory.name}»",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text("نسأل الله لك العلم النافع 🌿", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                // سطر التطبيق يجعل الشهادة دعوةً لمن يقرأها.
                                // «إليها» عائدة على السلسلة (مؤنّثة)، واسم
                                // التطبيق مرّة واحدة لا في سطرين متتاليين.
                                "أتممتُ سلسلة «${subcategory.name}» في تطبيق «منبر ادكصهك» 🎓\n" +
                                    "استمع إليها من هنا:\n" +
                                    "https://play.google.com/store/apps/details?id=com.ali.menbaradkshk",
                            )
                        },
                        "مشاركة",
                    ),
                )
            }) { Text("مشاركة") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        },
    )
}

// ----------------------------------------------------------------------------
// شاشة دروس قسم فرعي (LessonsScreen في الأصل)
// ----------------------------------------------------------------------------

@Composable
fun LessonsScreen(vm: AppViewModel, subcategoryId: String, playback: PlaybackUiState) {
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    val sub = content.subcategoryById[subcategoryId]
    val all = remember(revision, content.lessons) { vm.content.lessonsForSubcategory(subcategoryId) }
    // 📴 «المحفوظ فقط» — بلا إنترنت لا يظهر إلا ما يعمل بالنقر فعلاً.
    val lessons = rememberSavedOnly(vm, all)
    val bulk by vm.bulkDownload.collectAsState()
    var newestFirst by rememberSaveable(subcategoryId) { mutableStateOf(false) }
    val ordered = remember(lessons, newestFirst) { if (newestFirst) lessons.reversed() else lessons }
    // قراءة خريطة التنزيلات مرّة واحدة: `isDownloaded` لكل درس كان يفكّ خريطة
    // JSON كاملةً من التفضيلات، أي n تحليلاً كاملاً على خيط الواجهة.
    // ⚠️ العدّ والتحميل الجماعيّ على القائمة **الكاملة** لا المرشَّحة: ترشيح
    // العرض شأن الشاشة، أمّا «تنزيل كل دروس القسم» فيجب أن يبقى معناه كل
    // الدروس وإلّا صار زرّاً لا يفعل شيئاً.
    // ⚠️ العدّ على خيط الإدخال/الإخراج لا أثناء التركيب: فكّ JSON + `isFile`
    // لكل درس كان يقع على خيط الواجهة مع **كل** نبضة `revision` (كل قلب وكل
    // تلميح) — نفس علاج rememberSavedOnlyIds في OfflineOnly.kt.
    var downloadedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision, all) {
        downloadedCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val map = vm.downloads.all()
            all.count { l -> map[l.id]?.let { java.io.File(it).isFile } == true }
        }
    }
    // القسم الرئيسي قد لا يكون قد وصل بعد (رابط عميق/إشعار قبل اكتمال
    // المزامنة)، فالزرّ يُعطَّل بدل أن يبتلع الضغطة صامتاً.
    val parentId = sub?.categoryId?.takeIf { it.isNotBlank() }

    LazyColumn(contentPadding = PaddingValues(vertical = 10.dp)) {
        item {
            HintCard(
                vm = vm,
                hintKey = "bulk_download",
                icon = Icons.Filled.DownloadForOffline,
                text = "يمكنك تنزيل كل دروس هذا القسم دفعة واحدة للاستماع دون إنترنت — من الزر أدناه.",
            )
        }
        // تشغيل القسم كاملاً، تشغيل عشوائي، وفرز الدروس.
        if (ordered.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { vm.openPlayer(ordered.first(), ordered) },
                        leadingIcon = { Icon(Icons.Filled.PlayCircleFilled, null, tint = Teal, modifier = Modifier.size(20.dp)) },
                        label = { Text("تشغيل الكل") },
                    )
                    AssistChip(
                        onClick = {
                            val shuffled = ordered.shuffled()
                            vm.openPlayer(shuffled.first(), shuffled)
                        },
                        leadingIcon = { Icon(Icons.Filled.Shuffle, null, tint = BlueBrand, modifier = Modifier.size(20.dp)) },
                        label = { Text("عشوائي") },
                    )
                    Spacer(Modifier.weight(1f))
                    // الحال مكتوبة والخيارات مكتوبة (انظر `SortChip`).
                    SortChip(
                        currentIndex = if (newestFirst) 0 else 1,
                        options = listOf("الأحدث أولاً", "الأقدم أولاً"),
                        onSelect = { index -> newestFirst = index == 0 },
                    )
                }
            }
        }
        item {
            val active = bulk != null
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (active) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${bulk?.done}/${bulk?.total}", style = MaterialTheme.typography.bodyMedium)
                        if (bulk?.paused == true) {
                            Icon(
                                Icons.Filled.PauseCircleFilled,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            bulk?.label.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        DownloadQueueControls(vm)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⚠️ الزرّ يبقى ظاهراً ولو كان الطابور يعمل: `downloadLessons`
                    // تُلحق بالطابور الجاري وتُسقط المكرّر أصلاً، وكان إخفاؤه
                    // يمنع إضافة قسم كامل حتى ينتهي كلّ ما يجري — ولو كان
                    // درساً واحداً من قسم آخر على شبكة بطيئة.
                    DownloadAllButton(
                        text = when {
                            active -> "أضِف دروس القسم إلى الطابور"
                            downloadedCount > 0 -> "تنزيل الكل ($downloadedCount/${all.size} محمّل)"
                            else -> "تنزيل كل دروس القسم"
                        },
                        enabled = all.isNotEmpty(),
                        onClick = { vm.requestBulkDownload(sub?.name ?: "القسم", all) },
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { parentId?.let { vm.open(Route.Category(it)) } },
                        enabled = parentId != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "القسم الرئيسي")
                    }
                }
            }
        }
        if (lessons.isEmpty()) {
            item {
                // فراغُ «المحفوظ فقط» ليس فراغ قسم: نقول للمستخدم ما جرى بدل
                // أن يظنّ القسم خالياً.
                val savedOnlyEmpty = all.isNotEmpty()
                EmptyOrLoadingState(
                    loading = content.loading && !savedOnlyEmpty,
                    // مع وجود دروس محفوظة يكون القسم فارغاً فعلاً لا الاتصال منقطعاً.
                    offline = content.offline && content.lessons.isEmpty(),
                    offlineMessage = "يجب الاتصال بالإنترنت أول مرة لتحميل الدروس. بعد ذلك يمكنك الاستماع دون إنترنت.",
                    emptyMessage = if (savedOnlyEmpty) {
                        "لا يوجد درس محفوظ من هذا القسم على جهازك."
                    } else {
                        "لا توجد دروس في هذا القسم."
                    },
                    onRetry = { vm.content.requestDeepRefresh() },
                )
            }
        } else {
            // ⛔ لا `revision` في المفتاح — يهدم تركيبة الصفّ ويغلق قائمة
            // «إيقاف/إلغاء» المفتوحة. `AudioItem` يقرأ `revision` بنفسه.
            items(ordered, key = { it.id }) { lesson ->
                AudioItem(vm, lesson, ordered, playback)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// قائمة دروس عامة (المفضّلة/السجل/القوائم/التنزيلات/نتائج البحث)
// ----------------------------------------------------------------------------

@Composable
fun LessonListScreen(
    vm: AppViewModel,
    all: List<Lesson>,
    playback: PlaybackUiState,
    emptyTitle: String,
    emptyDetail: String = "لم تُضف عناصر هنا بعد.",
) {
    // 📴 «المحفوظ فقط» يسري على كل قوائم الدروس بلا استثناء.
    val lessons = rememberSavedOnly(vm, all)
    if (lessons.isEmpty()) {
        if (all.isEmpty()) {
            EmptyState(emptyTitle, emptyDetail)
        } else {
            EmptyState("لا شيء محفوظ هنا", "حمّل دروساً وأنت متصل لتسمعها بلا إنترنت.")
        }
        return
    }
    // ⛔ لا `revision` في المفتاح ولا اشتراك به هنا: `AudioItem` يقرأه بنفسه،
    // وإقحامه في المفتاح كان يهدم تركيبة كل صفّ مع كل كتابة في المخزن.
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(lessons, key = { it.id }) { lesson ->
            AudioItem(vm, lesson, lessons, playback)
        }
    }
}

// ----------------------------------------------------------------------------
// البحث — يطابق ContentSearchDelegate الأصلي حرفياً (أقسام + فرعية + دروس)
// ----------------------------------------------------------------------------

@Composable
fun SearchScreen(vm: AppViewModel, initial: String, playback: PlaybackUiState) {
    var query by rememberSaveable(initial) { mutableStateOf(initial) }
    val content by vm.content.state.collectAsState()
    val revision by vm.store.revision.collectAsState()
    val q = query.trim()

    // ⚠️ السجلّ يُكتب عند **تنفيذ** البحث لا أثناء الكتابة: الحفظ بعد مهلة
    // ٤٠٠ م.ث كان يخزّن كل بادئة يمرّ بها الإصبع («ال»، «الصي»، «الصيا») حتى
    // يمتلئ «عمليات بحث سابقة» بكلمات لم يقصدها أحد. والتنفيذ الصريح موجود:
    // زرّ البحث في لوحة المفاتيح، أو فتح نتيجة من النتائج.
    val commitSearch = {
        if (q.length >= 2) vm.store.addSearchQuery(q)
    }

    // 🎤 البحث الصوتي: من لا يكتب العربية بطلاقة كانت المكتبة كلّها مغلقة
    // أمامه إلّا بالتصفّح. نستدعي محرّك التعرّف على الكلام **في النظام نفسه**
    // — بلا مكتبة ولا إذن دائم ولا كلفة بيانات إضافيّة على المستخدم.
    val context = androidx.compose.ui.platform.LocalContext.current
    val speechIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "قل ما تبحث عنه")
        }
    }
    // لا يوجد محرّك على الجهاز ⇒ تُخفى الأيقونة: زرٌّ يفشل عند أوّل ضغطة
    // أسوأ من غيابه.
    val canSpeak = remember {
        speechIntent.resolveActivity(context.packageManager) != null
    }
    val voiceSearch = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotEmpty()) {
            // النتيجة تُنفَّذ فوراً: البحث هنا يجري مع تغيّر النصّ، ويُسجَّل
            // الاستعلام كما لو نُفِّذ بزرّ البحث — فهو تنفيذٌ صريح لا كتابة.
            query = spoken
            if (spoken.length >= 2) vm.store.addSearchQuery(spoken)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            label = { Text("ابحث في الدروس والأقسام...") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = "مسح")
                        }
                    }
                    if (canSpeak) {
                        IconButton(
                            onClick = {
                                // محرّك النظام قد يغيب فجأةً (تعطيله من
                                // الإعدادات) — فلا يسقط التطبيق بها.
                                runCatching { voiceSearch.launch(speechIntent) }
                                    .onFailure { vm.showMessage("البحث بالصوت غير متاح على هذا الجهاز.") }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "ابحث بصوتك",
                                tint = brandTintOnSurface(Teal),
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { commitSearch() }),
        )

        if (q.isEmpty()) {
            val history = remember(revision) { vm.store.searchHistory() }
            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    Text("ابحث في العناوين، الأقسام، وأسماء الشيوخ", modifier = Modifier.padding(top = 24.dp))
                }
            } else {
                LazyColumn {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("عمليات بحث سابقة", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { vm.store.clearSearchHistory() }) { Text("مسح") }
                        }
                    }
                    items(history, key = { it }) { entry ->
                        ListItem(
                            modifier = Modifier.clickable { query = entry },
                            headlineContent = { Text(entry) },
                            leadingContent = { Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
            }
        } else {
            val categories = content.categories
            val subcategories = content.subcategories
            // بحث بالكلمات لا بالجملة الحرفية: **كل كلمة** في الاستعلام يجب أن
            // ترد في العنوان أو المتحدّث أو اسم القسم — فـ«ابن باز الصيام» تجد
            // درساً عنوانه «الصيام» لمتحدّث «ابن باز» ولو اختلف الترتيب.
            val words = remember(q) {
                normalizeArabic(q).split(' ', '\n', '\t').filter(String::isNotBlank)
            }
            val catRes = remember(words, categories) {
                categories.filter { category ->
                    val hay = normalizeArabic(category.name)
                    words.all { hay.contains(it) }
                }
            }
            val subRes = remember(words, subcategories) {
                subcategories.filter { sub ->
                    val hay = normalizeArabic(sub.name)
                    words.all { hay.contains(it) }
                }
            }
            // فهرس مطبَّع يُبنى مرّة لكل تغيّر محتوى لا مع كل ضغطة مفتاح:
            // كان normalizeArabic يمرّ على كل الدروس عند كل حرف يُكتب.
            val lessonIndex = remember(content.lessons, categories, subcategories) {
                val categoryNames = categories.associate { it.id to it.name }
                val subcategoryNames = subcategories.associate { it.id to it.name }
                // الترتيب هنا مرّة واحدة لا مع كل ضغطة مفتاح: قصّ النتائج عند 80
                // كان يقتطع بترتيب معرّف الوثيقة (عشوائي) لا بالأحدث.
                content.lessons.sortedByDescending(Lesson::createdAtMs).map { lesson ->
                    lesson to normalizeArabic(
                        buildString {
                            append(lesson.displayTitle)
                            append(' ')
                            append(lesson.speaker)
                            append(' ')
                            append(categoryNames[lesson.categoryId].orEmpty())
                            append(' ')
                            append(subcategoryNames[lesson.subcategoryId].orEmpty())
                        },
                    )
                }
            }
            val lesRes = remember(words, lessonIndex) {
                lessonIndex.asSequence()
                    .filter { (_, hay) -> words.all { word -> hay.contains(word) } }
                    .map { it.first }
                    .take(80)
                    .toList()
            }

            // 📖 «في النص المشروح»: يجد الدرس بكلمةٍ من متنه لا من عنوانه —
            // فمن كتب «التيمّم» يبلغ الدرس الذي شُرح فيه ولو خلا عنوانه منه.
            // استعلامٌ واحد على فهرس الكلمات (transcript_index) بعد مهلة كتابة،
            // وبقيّة الترشيح في الجهاز على ما عاد به. ⛔ لا نصوص تُخزَّن هنا.
            val transcriptWords = remember(q) { transcriptSearchWords(q) }
            // المرساة: أندر الكلمات تقديراً — انظر [transcriptSearchAnchor].
            val transcriptAnchor = remember(transcriptWords) {
                transcriptSearchAnchor(transcriptWords)
            }
            var transcriptHits by remember { mutableStateOf(emptyList<String>()) }
            var transcriptAsked by remember { mutableStateOf("") }
            LaunchedEffect(q) {
                if (transcriptAnchor == null) {
                    transcriptHits = emptyList()
                    transcriptAsked = q
                    return@LaunchedEffect
                }
                // مهلة الكتابة: لا يُسأل الخادم عند كل حرف — الكلفة تُحسب.
                // ٧٥٠ م.ث لا أقلّ: المهلة القصيرة كانت تستعلم في منتصف الكلمة
                // فتتقلّب النتائج مع كل حرف يلحق بها.
                delay(750)
                transcriptHits = vm.transcripts.searchIndex(transcriptAnchor)
                transcriptAsked = q
            }
            // «سُئل عن هذا الاستعلام بالذات وانتهى»: يميّز «لم يُبحث بعد» عن
            // «بُحث فلم يُوجد»، فلا تظهر «لا نتائج» قبل أن يعود الجواب.
            val transcriptSearching = transcriptAnchor != null && transcriptAsked != q
            val lessonById = remember(lessonIndex) {
                lessonIndex.associateBy({ it.first.id }, { it.first })
            }
            // ⚠️ مطابقات المرساة كما هي — **بلا ترشيح ببقيّة الكلمات**: نافذة
            // الفهرس اعتباطيّة الترتيب (انظر [TranscriptRepository.searchIndex])
            // وترشيحُ AND فوقها كان يكاد يُفرغ القائمة من دروس مطابقة فعلاً.
            val transcriptRes = remember(transcriptHits, lessonById, lesRes) {
                if (transcriptHits.isEmpty()) {
                    emptyList()
                } else {
                    // ما ظهر في النتائج أعلاه لا يُكرَّر هنا.
                    val shown = lesRes.mapTo(HashSet<String>()) { it.id }
                    transcriptHits.asSequence()
                        .filter { it !in shown }
                        .mapNotNull(lessonById::get)
                        .take(20)
                        .toList()
                }
            }

            if (catRes.isEmpty() && subRes.isEmpty() && lesRes.isEmpty() &&
                transcriptRes.isEmpty()
            ) {
                // بدل شاشة فارغة: اقتراح «الأكثر استماعاً» ليبقى للمستخدم مخرج.
                //
                // ⚠️ بحثُ المتون الجاري لا يُخرج من هذا الفرع: كانت الشاشة
                // تنقلب كلّها بينه وبين سطر «يُبحث…» مع كل نبضة استعلام،
                // فيثبت البديل الآن ويتبدّل سطرُ العنوان وحده.
                val fallback = remember(revision, content.lessons) { vm.content.mostListened() }
                LazyColumn {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (transcriptSearching) {
                                    "يُبحث في النص المشروح…"
                                } else {
                                    "لا توجد نتائج لـ«$q»"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                // لا تُقترح كلمةٌ أقصر وحكمُ البحث لم يصدر بعد.
                                if (transcriptSearching) {
                                    " "
                                } else {
                                    "جرّب كلمة أقصر أو اسم المتحدّث."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (fallback.isNotEmpty()) {
                        item {
                            Text(
                                "الأكثر استماعاً",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 4.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }
                        items(fallback, key = { "top-${it.id}" }) { lesson ->
                            AudioItem(vm, lesson, fallback, playback, showActions = false)
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(catRes, key = { "cat-${it.id}" }) { c ->
                        ListItem(
                            modifier = Modifier.clickable { commitSearch(); vm.open(Route.Category(c.id)) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null, tint = Teal) },
                            headlineContent = { Text(c.name) },
                        )
                    }
                    items(subRes, key = { "sub-${it.id}" }) { s ->
                        ListItem(
                            modifier = Modifier.clickable { commitSearch(); vm.open(Route.Subcategory(s.id)) },
                            leadingContent = { Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = BlueBrand) },
                            headlineContent = { Text(s.name) },
                        )
                    }
                    items(lesRes, key = { "les-${it.id}" }) { l ->
                        ListItem(
                            modifier = Modifier.clickable { commitSearch(); vm.openPlayer(l, lesRes) },
                            leadingContent = { Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Gold) },
                            headlineContent = { Text(l.displayTitle) },
                            supportingContent = if (l.speaker.isNotBlank()) {
                                { Text(l.speaker) }
                            } else {
                                null
                            },
                        )
                    }
                    if (lesRes.size >= 80) {
                        item {
                            Text(
                                "عرض أول 80 نتيجة — حدّد البحث أكثر",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                    if (transcriptRes.isNotEmpty()) {
                        item {
                            Text(
                                "في النص المشروح",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 4.dp,
                                ),
                            )
                        }
                        items(transcriptRes, key = { "txt-${it.id}" }) { lesson ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    commitSearch()
                                    vm.openPlayer(lesson, transcriptRes)
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = Teal,
                                    )
                                },
                                headlineContent = { Text(lesson.displayTitle) },
                                supportingContent = if (lesson.speaker.isNotBlank()) {
                                    { Text(lesson.speaker) }
                                } else {
                                    null
                                },
                            )
                        }
                    } else if (transcriptSearching) {
                        // سطرٌ واحد يقول إن الجواب في الطريق — بلا رسالة
                        // «لا نتائج» ثانية تُناقض ما قد يظهر بعد لحظة.
                        item {
                            Text(
                                "يُبحث في النص المشروح…",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
