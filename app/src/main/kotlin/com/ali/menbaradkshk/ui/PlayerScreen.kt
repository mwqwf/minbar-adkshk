@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.media.PlaybackUiState
import com.ali.menbaradkshk.util.formatDuration
import com.ali.menbaradkshk.util.lessonShareLink
import com.ali.menbaradkshk.util.lessonShareText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// «الآن يُشغَّل» — النقل الأمين لـ player_screen.dart.
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    lesson: Lesson,
    startAtMs: Long?,
    playback: PlaybackUiState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()

    // الدرس المعروض يتبع الدرس المُشغَّل فعلياً (نمط _current الأصلي).
    val current = content.lessonById[playback.mediaId] ?: lesson
    val sub = content.subcategoryById[current.subcategoryId]
    val cat = content.categoryById[current.categoryId]
    val similar = remember(current.id, content.lessons) { vm.content.similarTo(current) }
    val favorite = remember(revision, current.id) { vm.store.isFavorite(current.id) }
    val accent = colorForCategory(current.categoryId)
    val active = playback.mediaId == current.id
    // ⚠️ لا تشترك هنا في vm.downloads.progress: `DownloadButton` يجمع تقدّمه
    // بنفسه، والاشتراك هنا كان يعيد تركيب الشاشة كلها مع كل حزمة بايتات.
    // قراءات التفضيلات تُلفّ بـ revision لأن الشاشة تُعاد مرّتين في الثانية.
    val dark = isDarkTheme(remember(revision) { vm.store.themeMode() })

    var momentsSheet by remember { mutableStateOf(false) }
    var playlistSheet by remember { mutableStateOf(false) }
    var feedbackMenu by remember { mutableStateOf(false) }
    var feedbackFor by remember { mutableStateOf<String?>(null) }
    var sleepSheet by remember { mutableStateOf(false) }

    /// هل ملفّ هذا الدرس على الجهاز الآن؟ يُعاد حسابه مع كل مراجعة للمخزن
    /// كي يظهر بند «أرسل الملف الصوتي» فور اكتمال التنزيل ويختفي فور حذفه.
    val downloadedNow = remember(revision, current.id) { hasLocalAudioFile(vm, current) }

    // بدء التشغيل عند فتح درس من رابط «لحظة» أو حين لا يكون الدرس فعّالاً.
    // موضع «اللحظة» يُستهلك مرة واحدة ثم يُزال من المسار، كي لا يعيد الرجوع
    // أو التدوير التشغيل من الثانية المشارَكة.
    // ⛔ ما نحفظه هو **قيمة** اللحظة لا مجرّد «استُهلكت»، والمفتاح lesson.id
    // وحده: كان المفتاح يشمل startAtMs فيصفّر العلامة فور replaceRoute (الذي
    // يجعل startAtMs=null)، فيُعاد النداء بلا موضع ويدهس pendingPlay المفرد
    // في PlaybackController — فتضيع اللحظة كلّها في الإقلاع البارد.
    // وحفظ القيمة يُبقي رابط لحظة ثانياً لنفس الدرس عاملاً (قيمة مختلفة).
    var handledStart by rememberSaveable(lesson.id) { mutableStateOf(Long.MIN_VALUE) }
    LaunchedEffect(lesson.id, startAtMs) {
        if (startAtMs != null) {
            // ⚠️ المساواة وحدها لا تكفي: `handledStart` ينجو من مغادرة الشاشة
            // (rememberSaveable داخل حافظ الحالة)، فإعادة النقر على الرابط
            // نفسه لاحقاً — والمشغّل على درس آخر — كانت تُسقط القفز والتشغيل
            // معاً وتفتح الشاشة على الدرس الجاري، فيبدو الرابط معطوباً.
            // فنقارن أيضاً بحالة التشغيل الفعليّة: درسٌ غير مُشغَّل ⇒ شغِّل
            // واقفز ولو تطابق `handledStart`.
            if (handledStart != startAtMs || playback.mediaId != lesson.id) {
                handledStart = startAtMs
                vm.playback.play(lesson, listOf(lesson) + vm.content.similarTo(lesson), startAtMs, restart = true)
                vm.replaceRoute(Route.Lesson(lesson.id))
            }
        } else if (handledStart == Long.MIN_VALUE && playback.mediaId != lesson.id) {
            handledStart = 0L
            vm.playback.play(lesson, listOf(lesson) + vm.content.similarTo(lesson))
        }
    }

    /// محمّل ⇒ نشارك الملف الصوتي نفسه، وغير محمّل ⇒ النصّ والرابط
    /// (سلوك الأصل). التفاصيل في `shareLessonPayload`.
    fun share(l: Lesson) {
        val posSec = if (active) playback.positionMs / 1_000L else 0L
        val text = lessonShareText(
            l,
            content.categoryById[l.categoryId]?.name,
            content.subcategoryById[l.subcategoryId]?.name,
            if (posSec > 10) posSec else null,
            momentLabel = if (posSec > 10) {
                "من الدقيقة ${posSec / 60}:${(posSec % 60).toString().padStart(2, '0')}"
            } else {
                null
            },
        )
        shareLessonPayload(context, vm, l, text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الآن يُشغَّل") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (dark) AppBarBackgroundDark else AppBarBackgroundLight,
                    titleContentColor = AppBarForeground,
                    navigationIconContentColor = AppBarForeground,
                    actionIconContentColor = AppBarForeground,
                ),
                navigationIcon = {
                    // كسهم النظام: مكدّس فارغ ⇒ الرئيسية لا لا-شيء. الشاشة
                    // بملء الشاشة بلا شريط سفليّ، فزرٌّ لا يفعل شيئاً يحبس
                    // المستخدم فيها (تُفتح من إشعار أو رابط عميق بلا مكدّس).
                    IconButton(onClick = { if (!vm.back()) vm.openRoot(Route.Home) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { momentsSheet = true }) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = "اللحظات")
                    }
                    IconButton(onClick = { playlistSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "إضافة إلى قائمة")
                    }
                    IconButton(onClick = { vm.toggleFavorite(current.id) }) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (favorite) "إزالة من المفضّلة" else "إضافة للمفضّلة",
                            tint = if (favorite) Color(0xFFFF8A80) else AppBarForeground,
                        )
                    }
                    Box {
                        IconButton(onClick = { feedbackMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "تفاعل")
                        }
                        DropdownMenu(expanded = feedbackMenu, onDismissRequest = { feedbackMenu = false }) {
                            // 📤 إرسال الملفّ نفسه — للمنزَّل فقط، ويختفي
                            // تماماً لغيره (بندٌ معطّل يُسأل عنه بلا فائدة).
                            // ولماذا أصلاً؟ لأنّ الرابط لا ينفع من لا إنترنت
                            // عنده، والملفّ ينتقل بالبلوتوث بلا شبكة.
                            if (downloadedNow) {
                                DropdownMenuItem(
                                    text = { Text("أرسل الملف الصوتي") },
                                    leadingIcon = { Icon(Icons.Filled.AudioFile, null) },
                                    onClick = {
                                        feedbackMenu = false
                                        sendLessonAudioFile(context, vm, current)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("استفدت من الدرس") },
                                leadingIcon = { Icon(Icons.Filled.ThumbUp, null) },
                                onClick = {
                                    feedbackMenu = false
                                    scope.launch {
                                        runCatching { vm.content.sendFeedback(current.id, "benefited", "") }
                                            .onSuccess { vm.showMessage("شكراً لك — وصلنا تفاعلك.") }
                                            .onFailure { vm.showMessage("تعذّر إرسال البلاغ. تحقق من الاتصال وحاول مجدداً.") }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("مشكلة في الصوت") },
                                leadingIcon = { Icon(Icons.Filled.VolumeOff, null) },
                                onClick = { feedbackMenu = false; feedbackFor = "audio_issue" },
                            )
                            DropdownMenuItem(
                                text = { Text("انتهاك حقوق نشر") },
                                leadingIcon = { Icon(Icons.Filled.Copyright, null) },
                                onClick = { feedbackMenu = false; feedbackFor = "copyright" },
                            )
                            DropdownMenuItem(
                                text = { Text("محتوى غير مناسب") },
                                leadingIcon = { Icon(Icons.Filled.GppMaybe, null) },
                                onClick = { feedbackMenu = false; feedbackFor = "abuse" },
                            )
                            DropdownMenuItem(
                                text = { Text("إبلاغ عن هذا الدرس") },
                                leadingIcon = { Icon(Icons.Filled.Report, null) },
                                onClick = { feedbackMenu = false; feedbackFor = "other" },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            playback.error?.let { error ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = {
                        // الدرس المعروض هو المُشغَّل ⇒ retry() يُعيد التهيئة من القائمة
                        // نفسها (المشغّل في STATE_IDLE بعد الخطأ)؛ وإلّا نبني قائمة جديدة.
                        if (active) {
                            vm.playback.retry()
                        } else {
                            vm.playback.clearError()
                            vm.playback.play(current, listOf(current) + similar, restart = true)
                        }
                    }) { Text("إعادة المحاولة") }
                }
                Spacer(Modifier.height(8.dp))
            }
            // 📚 شريط «التالي» — أعلى الشاشة كي يُرى بلا بحث. لا يظهر إلّا
            // أثناء العدّ التنازليّ، ويزول من نفسه بعده.
            AutoplayNextBar(
                onStop = vm.playback::stopAutoplayCountdown,
                onPlayNow = vm.playback::startNextNow,
            )
            if (playback.loading && active) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = Teal)
            }

            // الغلاف: دائرة بتدرّج لون القسم وأيقونته.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .background(Brush.linearGradient(listOf(accent, Slate)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        iconForCategory(current.categoryId),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(84.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                current.displayTitle,
                // ضغطة مطوّلة تنسخ عنوان الدرس — يستعمله الناس في المشاركة
                // والبحث عن الدرس نفسه في مكان آخر.
                modifier = Modifier
                    .fillMaxWidth()
                    .copyable(current.displayTitle),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            if (current.speaker.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    current.speaker,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 🎧 عدد مرّات الاستماع — هنا حيث يقرأه من فتح الدرس فعلاً، بخطٍّ
            // أصغر من اسم الشيخ: خبرٌ يُطمئن لا عنوانٌ ينافس.
            com.ali.menbaradkshk.util.listenCountLabel(current.views)?.let { listens ->
                Spacer(Modifier.height(4.dp))
                Text(
                    listens,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 📚 موضع الدرس في سلسلته — «شرح الأخضري» ستّون درساً مرقّمة، ومن
            // فتح واحداً منها لا يعرف أين هو منها. الترتيب هو ترتيب القسم
            // الفرعيّ نفسه (الأقدم أوّلاً) وهو ترتيب رفع الدروس أصلاً.
            val seriesPlace = remember(current.id, content.lessons) {
                if (current.subcategoryId.isBlank()) {
                    null
                } else {
                    val items = content.lessons
                        .filter { it.subcategoryId == current.subcategoryId && it.audioUrl.isNotBlank() }
                        .sortedBy(Lesson::createdAtMs)
                    val index = items.indexOfFirst { it.id == current.id }
                    // درسٌ وحيد في قسمه ليس «سلسلة»، فالسطر يُخفى.
                    if (index < 0 || items.size < 2) null else (index + 1) to items.size
                }
            }
            seriesPlace?.let { (place, total) ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "أنت في الدرس $place من $total",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Teal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))

            // 📖 «النص المشروح» في أعلى الشاشة عمداً — تحت العنوان مباشرة
            // ليراه كل من يفتح الصوتية، لا في ذيل الصفحة حيث لا يصله أحد.
            TranscriptSection(vm = vm, lesson = current)
            Spacer(Modifier.height(14.dp))

            // شريط التقدّم.
            // ⚠️ قراءتا المخزن تفكّان JSON كاملاً، وهذه الشاشة تُعاد مرّتين في
            // الثانية مع نبض الموضع — فكانتا تُنفَّذان في كل نبضة بلا داعٍ.
            // المفتاح: الدرس + `revision` (لا يتغيّر المحفوظ إلّا بكتابة).
            val savedDuration = remember(current.id, revision) { vm.store.duration(current.id) }
            val savedPosition = remember(current.id, revision) { vm.store.position(current.id) }
            val duration = if (active && playback.durationMs > 0L) playback.durationMs
            else current.durationMs.takeIf { it > 0L } ?: savedDuration
            val position = if (active) playback.positionMs else savedPosition
            // شريط سحب كلاسيكي كما في الأصل: خط رفيع مستمر ومقبض دائري أخضر
            // (بدل مقبض M3 العمودي الجديد وفجوة المسار).
            val sliderColors = SliderDefaults.colors(
                thumbColor = GreenBrand,
                activeTrackColor = GreenBrand,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            // ⚠️ لا تُصدر seekTo من onValueChange: كل بكسل سحب كان يُجهض طلب
            // الشبكة الجاري ويفتح طلب Range جديداً، والمقبض كان يتخلّف عن
            // الإصبع لأن الموضع لا ينبض إلا كل نصف ثانية. القفزة عند الإفلات.
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.playback.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = sliderColors,
                thumb = {
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(GreenBrand, CircleShape),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(3.dp),
                        colors = sliderColors,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // المنقضي عند الصفر: formatDuration تُعيد فراغاً عمداً للمدّة
                // المجهولة، لكن «مضى صفر» معلوم — فيُعرض 0:00 لا خانة فارغة.
                // (يمين الصفّ يبقى بلا حماية: مدّة مجهولة = فراغ مقصود.)
                Text(formatDuration(position).ifBlank { "0:00" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(duration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))

            // صفّ القفز + مؤقّت النوم.
            val skipSec = remember(revision) { vm.store.skipSeconds() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                // ⚠️ أيقونتان بلا أرقام: Replay10/Forward10 كانتا تعرضان «10»
                // والقفز فعلياً skipSec (١٥ ثانية) — الرقم المكتوب هو الحقيقة.
                TextButton(onClick = vm.playback::skipBackward) {
                    Icon(Icons.Filled.Replay, contentDescription = null, tint = BlueBrand)
                    Text("${skipSec}ث")
                }
                TextButton(onClick = vm.playback::skipForward) {
                    Icon(Icons.Filled.FastForward, contentDescription = null, tint = BlueBrand)
                    Text("${skipSec}ث")
                }
                TextButton(onClick = { sleepSheet = true }) {
                    Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Teal)
                    Text(" نوم")
                }
            }
            Spacer(Modifier.height(8.dp))

            // السرعات الست كما في الأصل.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
            ) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                    val selected = kotlin.math.abs(playback.speed - speed) < 0.01f
                    Box(Modifier.padding(horizontal = 4.dp)) {
                        FilterChip(
                            selected = selected,
                            onClick = { vm.playback.setSpeed(speed) },
                            label = { Text("${if (speed == speed.toLong().toFloat()) speed.toInt() else speed}×") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // صفّ التحكم: تحميل، السابق، تشغيل كبير، التالي، مشاركة.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadButton(vm, current, size = 44.dp)
                // طرفا القائمة: الزرّان كانا بكامل التباين ولا يفعلان شيئاً —
                // نوحّدهما مع المشغّل المصغّر الذي يعتمد hasPrevious/hasNext.
                IconButton(onClick = vm.playback::previous, enabled = playback.hasPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "السابق", tint = BlueBrand, modifier = Modifier.size(36.dp))
                }
                // ⭐ «أعِد ٣٠ ثانية» بجوار زرّ التشغيل وبحجم قريب منه.
                //
                // **لماذا زرٌّ مستقلّ والترجيع موجود؟** لأنّ أكثر ما يفعله
                // مستمع الدرس العلميّ هو «ما فهمتُ هذه الجملة، أعِدها»، وهذا
                // الفعل اليوم إمّا زرٌّ صغير في صفٍّ من ثلاثة، وإمّا سحبٌ على
                // الشريط يحتاج دقّةً لا يملكها من يستمع وهو يعمل.
                //
                // وثلاثون ثانية **ثابتة لا تتبع إعداد القفز**: الإعداد يضبط
                // الترجيع الدقيق، وهذا الزرّ معناه واحد مكتوبٌ عليه لا يتغيّر.
                Replay30Button(
                    onClick = {
                        val target = (position - 30_000L).coerceAtLeast(0L)
                        // درسٌ غير مُشغَّل الآن: لا موضع في المشغّل ليُرجَع
                        // منه، فنبدأ تشغيله من الموضع المطلوب مباشرةً.
                        if (active) {
                            vm.playback.seekTo(target)
                        } else {
                            vm.playback.play(current, listOf(current) + similar, target, restart = true)
                        }
                    },
                    size = 62.dp,
                    tint = BlueBrand,
                    background = BlueBrand.copy(alpha = 0.12f),
                )
                val playing = active && playback.playing
                val loading = active && playback.loading
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(if (playing) GreenBrand else OrangeBrand, CircleShape)
                        .clickable(enabled = !loading) {
                            if (active) vm.playback.toggle()
                            else vm.playback.play(current, listOf(current) + similar)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "إيقاف" else "تشغيل",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
                // ⚠️ صغّرنا «السابق/التالي» قليلاً (56→48) لإفساح مكان زرّ
                // «أعِد ٣٠ ثانية» في الصفّ نفسه على الشاشات الضيّقة — وهما
                // ما زالا فوق حدّ اللمس (٤٨) بأمان.
                IconButton(onClick = vm.playback::next, enabled = playback.hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "التالي", tint = BlueBrand, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { share(current) }) {
                    Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = Teal)
                }
            }

            // تشغيل تلقائي للتالي.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("تشغيل تلقائي للتالي")
                Switch(checked = playback.autoplay, onCheckedChange = vm.playback::setAutoplay)
            }

            // تخطّي الصمت — إلى جوار «التشغيل التلقائي» لأن كليهما يضبط سير
            // الاستماع لا الصوت نفسه. مطفأ افتراضياً، ومحفوظ بين الجلسات.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ContentCut,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("تخطّي الصمت")
                Switch(checked = playback.skipSilence, onCheckedChange = vm.playback::setSkipSilence)
            }
            playback.sleepEndsAtMs?.let { ends ->
                // ⚠️ العدّاد يحتاج مجدوله الخاص: كان يُحسب أثناء التركيب فقط،
                // ونبض المشغّل مشروط بالتشغيل — فيتجمّد الرقم عند الإيقاف
                // المؤقّت بينما تواصل الخدمة العدّ فعلاً حتى الإيقاف.
                var remaining by remember(ends) {
                    mutableStateOf((ends - System.currentTimeMillis()).coerceAtLeast(0L))
                }
                LaunchedEffect(ends) {
                    while (remaining > 0L) {
                        delay(1_000L)
                        remaining = (ends - System.currentTimeMillis()).coerceAtLeast(0L)
                    }
                }
                Text(
                    "مؤقّت النوم: ${formatDuration(remaining).ifBlank { "0:00" }}",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    color = OrangeBrand,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // 🌙 مؤقّت «نهاية الدرس» لا موعد له يُعدّ، فيُعلَن بشرطه نصّاً —
            // وإلّا ظنّ المستخدم أنّ اختياره ضاع.
            if (playback.sleepAfterItems > 0) {
                Text(
                    // العدد لا يتجاوز اثنين، فالصيغة صحيحة بلا جمعٍ ملحون.
                    if (playback.sleepAfterItems == 1) {
                        "سيتوقف التشغيل عند نهاية هذا الدرس"
                    } else {
                        "سيتوقف التشغيل عند نهاية الدرس الذي يليه"
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    color = OrangeBrand,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // التنقّل إلى القسم الفرعي/الرئيسي.
            if (current.subcategoryId.isNotBlank()) {
                FilledTonalButton(
                    onClick = { vm.open(Route.Subcategory(current.subcategoryId)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Text(" القسم الفرعي: ${sub?.name?.ifBlank { null } ?: "فتح القسم"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cat != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { vm.open(Route.Category(cat.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Text(" القسم الرئيسي: ${cat.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("صوتيات مشابهة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            if (similar.isEmpty()) {
                Text("لا توجد اقتراحات بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            } else {
                similar.forEach { s ->
                    val sActive = playback.mediaId == s.id
                    val sSub = content.subcategoryById[s.subcategoryId]
                    ListItem(
                        modifier = Modifier.clickable { vm.playback.play(s, similar) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(if (sActive) GreenBrand else colorForCategory(s.categoryId), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (sActive && playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                            }
                        },
                        headlineContent = { Text(s.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = sSub?.name?.takeIf(String::isNotBlank)?.let { { Text(it) } },
                    )
                }
            }
        }
    }

    // ---- ورقة مؤقّت النوم ----
    if (sleepSheet) {
        ModalBottomSheet(onDismissRequest = { sleepSheet = false }) {
            Text(
                "مؤقّت نوم",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
            )
            // 🌙 **قبل الدقائق عمداً**: من يستمع قبل نومه يريد أن يُتمّ الدرس
            // لا أن يخمّن رقماً يقطعه في وسطه أو يتركه يعمل بعده. والخياران
            // شرطٌ لا مدّة — «قِف عند طرف الدرس» — فلا يُخطئان مهما تبدّل
            // الدرس بالتشغيل التلقائيّ أو تبدّلت السرعة.
            listOf(1 to "إلى نهاية الدرس", 2 to "إلى نهاية درسين").forEach { (count, label) ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.playback.setSleepAfterItems(count)
                        sleepSheet = false
                        vm.showMessage("سيتوقف التشغيل $label")
                    },
                    leadingContent = { Icon(Icons.Filled.Bedtime, null, tint = Teal) },
                    headlineContent = { Text(label) },
                )
            }
            HorizontalDivider()
            listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.playback.setSleepTimer(minutes)
                        sleepSheet = false
                        vm.showMessage(
                            "سيتوقف التشغيل بعد " +
                                com.ali.menbaradkshk.util.minutesCountLabel(minutes),
                        )
                    },
                    headlineContent = {
                        // صيغة العدد العربيّة: «٥ دقائق» لا «5 دقيقة».
                        Text(com.ali.menbaradkshk.util.minutesCountLabel(minutes))
                    },
                )
            }
            // الإلغاء يظهر لأيّ من المؤقّتين — مؤقّت «نهاية الدرس» بلا موعد
            // فلا يكفي `sleepEndsAtMs` وحده دليلاً على وجود مؤقّت.
            if (playback.sleepEndsAtMs != null || playback.sleepAfterItems != 0) {
                ListItem(
                    modifier = Modifier.clickable {
                        vm.playback.cancelSleepTimer()
                        sleepSheet = false
                    },
                    leadingContent = { Icon(Icons.Filled.Cancel, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("إلغاء المؤقّت") },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- ورقة «إضافة إلى قائمة» ----
    if (playlistSheet) {
        var newListDialog by remember { mutableStateOf(false) }
        var newListName by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { playlistSheet = false }) {
            Text(
                "إضافة إلى قائمة",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
            )
            ListItem(
                modifier = Modifier.clickable { newListDialog = true },
                leadingContent = { Icon(Icons.Filled.Add, null, tint = Teal) },
                headlineContent = { Text("قائمة جديدة") },
            )
            // قراءة/تحليل JSON مرّة لكل تغيّر فعليّ لا مع كل نبضة موضع.
            val playlists = remember(revision) { vm.store.playlists() }
            playlists.forEach { playlist ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.store.addToPlaylist(playlist.id, current.id)
                        playlistSheet = false
                        vm.showMessage("أُضيف إلى \"${playlist.name}\"")
                    },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    headlineContent = { Text(playlist.name) },
                    supportingContent = {
                        // صيغة العدد العربيّة: «صوتيتان» لا «2 صوتية».
                        Text(com.ali.menbaradkshk.util.audiosCountLabel(playlist.lessonIds.size))
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        if (newListDialog) {
            AlertDialog(
                onDismissRequest = { newListDialog = false },
                title = { Text("قائمة جديدة") },
                text = {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        placeholder = { Text("اسم القائمة") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    // ⚠️ «حفظ» والاسم فارغ كان يغلق الحوار والورقة معاً بصمت —
                    // لا قائمة أُنشئت ولا الدرس أُضيف، ويظنّ المستخدم أنه حُفظ.
                    // الزرّ معطَّل حتى يُكتب اسم.
                    TextButton(
                        enabled = newListName.isNotBlank(),
                        onClick = {
                            val name = newListName.trim()
                            val created = vm.store.createPlaylist(name)
                            vm.store.addToPlaylist(created.id, current.id)
                            vm.showMessage("أُضيف إلى \"${created.name}\"")
                            newListName = ""
                            newListDialog = false
                            playlistSheet = false
                        },
                    ) { Text("حفظ") }
                },
                dismissButton = {
                    TextButton(onClick = { newListDialog = false }) { Text("إلغاء") }
                },
            )
        }
    }

    // ---- ورقة «اللحظات» ----
    if (momentsSheet) {
        var momentNoteDialog by remember { mutableStateOf(false) }
        var momentNote by remember { mutableStateOf("") }
        // ⚠️ اللحظة تُلتقط **عند ضغطة الزرّ** لا عند تأكيد الحوار: التشغيل
        // مستمرّ أثناء كتابة الملاحظة، فقراءة الموضع عند «حفظ» كانت تحفظ
        // لحظة متأخّرة عن المقصود — وأسوأ: إن انتقل التشغيل التلقائيّ للدرس
        // التالي والحوار مفتوح (current يتبع mediaId) حُفظت اللحظة على درس
        // آخر كليّاً بموضعه هو.
        var momentPositionMs by remember { mutableStateOf(0L) }
        var momentLessonId by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { momentsSheet = false }) {
            Text(
                "لحظات هذا الدرس",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(
                onClick = {
                    momentNote = ""
                    // «الحالية» = لحظة الضغط، لا لحظة إنهاء كتابة الملاحظة.
                    momentPositionMs = if (active) playback.positionMs else 0L
                    momentLessonId = current.id
                    momentNoteDialog = true
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                Text(" احفظ اللحظة الحالية")
            }
            Spacer(Modifier.height(8.dp))
            val moments = remember(revision, current.id) { vm.store.bookmarks(current.id) }
            if (moments.isEmpty()) {
                Text(
                    "لا لحظات محفوظة بعد.",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                moments.forEach { bookmark ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (playback.mediaId != current.id) {
                                vm.playback.play(current, listOf(current) + similar, bookmark.positionMs, restart = true)
                            } else {
                                vm.playback.seekTo(bookmark.positionMs)
                            }
                            momentsSheet = false
                        },
                        leadingContent = { Icon(Icons.Filled.PlayCircleOutline, null, tint = Teal) },
                        // لحظة عند الصفر ممكنة فعلاً (تُحفظ 0 حين لا يكون الدرس
                        // فعّالاً) — فلا يجوز أن يظهر عنوانها خانةً فارغة.
                        headlineContent = { Text(formatDuration(bookmark.positionMs).ifBlank { "0:00" }) },
                        supportingContent = bookmark.note.takeIf(String::isNotBlank)?.let { { Text(it) } },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    val sec = (bookmark.positionMs / 1_000.0).let { Math.round(it) }
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "استمع إلى هذه اللحظة من «${current.displayTitle}» (من ${formatDuration(bookmark.positionMs)}):\n" +
                                                        lessonShareLink(current, sec),
                                                )
                                            },
                                            "مشاركة اللحظة",
                                        ),
                                    )
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = "مشاركة اللحظة", tint = Teal)
                                }
                                IconButton(onClick = {
                                    vm.store.removeBookmark(current.id, bookmark.savedAtMs)
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (momentNoteDialog) {
            AlertDialog(
                onDismissRequest = { momentNoteDialog = false },
                title = { Text("حفظ لحظة") },
                text = {
                    OutlinedTextField(
                        value = momentNote,
                        onValueChange = { momentNote = it },
                        placeholder = { Text("ملاحظة (اختياري)") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        // القيم الملتقطة عند ضغطة الزرّ (انظر التعليق أعلاه).
                        vm.store.addBookmark(momentLessonId, momentPositionMs, momentNote.trim())
                        vm.showMessage("حُفظت اللحظة عند ${formatDuration(momentPositionMs).ifBlank { "0:00" }}")
                        momentNoteDialog = false
                    }) { Text("حفظ") }
                },
                dismissButton = {
                    TextButton(onClick = { momentNoteDialog = false }) { Text("إلغاء") }
                },
            )
        }
    }

    // ---- حوار وصف مشكلة (بلاغ بأنواعه) ----
    feedbackFor?.let { type ->
        var note by remember(type) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { feedbackFor = null },
            title = { Text(if (type == "audio_issue") "مشكلة في الصوت" else "إبلاغ عن مشكلة") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("صف المشكلة (اختياري)") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    feedbackFor = null
                    scope.launch {
                        runCatching { vm.content.sendFeedback(current.id, type, note.trim()) }
                            .onSuccess { vm.showMessage("شكراً لك — وصلنا تفاعلك.") }
                            .onFailure { vm.showMessage("تعذّر إرسال البلاغ. تحقق من الاتصال وحاول مجدداً.") }
                    }
                }) { Text("إرسال") }
            },
            dismissButton = {
                TextButton(onClick = { feedbackFor = null }) { Text("إلغاء") }
            },
        )
    }
}

/**
 * 📚 شريط «التالي: … يبدأ بعد كذا» — إعلانُ الانتقال التلقائيّ بين دروس
 * السلسلة الواحدة.
 *
 * **لماذا شريطٌ لا مجرّد انتقال صامت؟** لأنّ السلاسل هنا كتبٌ لا ملفّات:
 * من أنهى الدرس السابع عشر يريد الثامن عشر، لكنّه يريد أن يعرف أنّه ذاهب
 * إليه وأن يملك منعه. وقبل هذا الشريط كان الصوت يتبدّل بلا خبر، ومفتاحُ
 * التعطيل مدفونٌ أسفل الشاشة لا يصل إليه من لا يتقن التقنية.
 *
 * الزرّان كبيران (≥ ٤٨) لأنّهما يُضغطان في خمس ثوانٍ وربّما في الظلام.
 * ولا حالة تشغيل هنا: الشريط يقرأ [AutoplayState] ويأمر [PlaybackController]،
 * فلا مسار تشغيلٍ ثانٍ يُنشَأ.
 */
@Composable
private fun AutoplayNextBar(onStop: () -> Unit, onPlayNow: () -> Unit) {
    val pending by com.ali.menbaradkshk.media.AutoplayState.pending.collectAsState()
    val next = pending ?: return
    // العدّ يُشتقّ من الموعد المحفوظ لا من عدّادٍ محلّيّ: إعادةُ تركيب الشاشة
    // أو الرجوع إليها لا تُعيد الخمس ثوانٍ من أوّلها.
    var seconds by remember(next.token) {
        mutableIntStateOf(remainingSeconds(next.endsAtMs))
    }
    LaunchedEffect(next.token) {
        while (seconds > 0) {
            delay(250L)
            seconds = remainingSeconds(next.endsAtMs)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Teal.copy(alpha = 0.14f))
            .padding(12.dp),
    ) {
        Text(
            "التالي: ${next.title}",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // صيغة العدد العربيّة: «ثانيتان»/«٥ ثوانٍ» لا «5 ثانية».
            "يبدأ بعد " + com.ali.menbaradkshk.util.arabicCountLabel(
                seconds,
                "ثانية واحدة",
                "ثانيتين",
                "ثوانٍ",
                "ثانية",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // «إيقاف» أوّلاً وأعرض: هو الفعل الذي يُطلب على عجل.
            FilledTonalButton(
                onClick = onStop,
                modifier = Modifier.weight(1.4f).height(52.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text(" إيقاف")
            }
            FilledTonalButton(
                onClick = onPlayNow,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(" شغّل الآن")
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

/// الثواني الباقية إلى موعد الانتقال — تقريبٌ لأعلى كي لا يظهر «0» قبل الحدث.
private fun remainingSeconds(endsAtMs: Long): Int =
    (((endsAtMs - System.currentTimeMillis()) + 999L) / 1_000L).coerceIn(0L, 99L).toInt()
