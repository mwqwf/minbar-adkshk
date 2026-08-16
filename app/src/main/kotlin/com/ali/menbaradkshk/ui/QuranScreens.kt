package com.ali.menbaradkshk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.flow.collectLatest
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.data.QuranHit
import com.ali.menbaradkshk.data.QuranIndex
import com.ali.menbaradkshk.data.QuranMark
import com.ali.menbaradkshk.data.Reciter
import com.ali.menbaradkshk.data.Surah
import com.ali.menbaradkshk.media.PlaybackUiState

/**
 * 🕌 المصحف الكامل — شاشة الفهرسة.
 *
 * **ثلاث روايات** أعلى الشاشة (حفص أوّلاً وهي الافتراضيّة، ثم ورش وقالون)،
 * وتحتها الفهرسة بأربعة مداخل: السور والأجزاء والأحزاب والصفحات — وهي
 * المداخل التي يستعملها الناس فعلاً في المصحف الورقي.
 *
 * **قرار تصميميّ**: تبديل الرواية لا يُخرج المستخدم من مكانه ولا يفتح شاشة
 * جديدة — النصّ نفسه يتبدّل تحت يده. هذا ما يجعل المقارنة بين الروايات
 * ممكنة أصلاً، وهو جوهر الميزة لا زينة فيها.
 */
@Composable
fun QuranIndexScreen(vm: AppViewModel) {
    val index by vm.quranIndex.collectAsState()
    val riwaya by vm.riwaya.collectAsState()
    val error by vm.quranError.collectAsState()
    val revision by vm.store.revision.collectAsState()
    /// نصّ الرواية الجارية — يلزم بحثَ الآيات وحده، ويُقرأ هنا مرّة لا داخل
    /// فرعٍ شرطيّ حتى يبقى ترتيب النداءات ثابتاً في كل تركيب.
    val quranText by vm.quranText.collectAsState()

    LaunchedEffect(Unit) { vm.loadQuran() }

    val loaded = index
    if (error != null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                error.orEmpty(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val lastAyah = remember(revision) { vm.store.quranLastAyah() }
    val bookmarks = remember(revision) { vm.store.quranBookmarks() }
    // البحث والعلامات يُفتحان من الشريط العلوي، فحالتهما في الـViewModel.
    val searchOpen by vm.quranSearchOpen.collectAsState()
    val bookmarksSheet by vm.quranBookmarksOpen.collectAsState()
    // ⚠️ مغادرة الشاشة تُغلق البحث: لولا ذلك لعاد المستخدم إلى المصحف يوماً
    // آخر فيجده مفتوحاً على حقل بحثٍ فارغ لا يذكر أنّه فتحه.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            vm.setQuranSearchOpen(false)
            vm.setQuranBookmarksOpen(false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        RiwayaSelector(loaded, riwaya) { vm.setRiwaya(it) }

        // صراحة لا ضمناً: نصّ ورش وقالون رسمُ مصحفهما، لكنّ **ترقيم الآيات**
        // يتبع عدّ حفص. وهذا ليس تنازلاً بل شرط صحّة: ملفّات التلاوة آية-بآية
        // مرقّمة بعدّ حفص، فلولا المحاذاة عليه لأشار التمييز إلى آية غير التي
        // تُتلى. سطرٌ واحد هادئ — من يعنيه الأمر يجده، ولا يزحم بقيّة الناس.
        if (riwaya != com.ali.menbaradkshk.data.QuranRepository.DEFAULT_RIWAYA) {
            Text(
                "رسم مصحف ${loaded.riwaya(riwaya).name} • ترقيم الآيات بعدّ حفص",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                textAlign = TextAlign.Center,
            )
        }

        // 🔍 الحقل لا يظهر إلا حين يطلبه صاحبه من زرّ الشريط العلوي — كما في
        // الرئيسية. وما دام فيه نصّ فالشاشة كلّها نتائجُه: لا بطاقات ولا
        // تبويبات تنازعه الانتباه.
        if (searchOpen) {
            QuranSearchField(
                query = query,
                onChange = { query = it },
                onClose = {
                    query = ""
                    vm.setQuranSearchOpen(false)
                },
            )
            if (query.isNotBlank()) {
                QuranSearchResults(loaded, text = quranText, query = query, vm = vm)
                return@Column
            }
        }

        // «تابع القراءة» — يعيد المستخدم إلى موضعه بالضبط. أهمّ زرّ في
        // الشاشة لمن يقرأ ورده يومياً، فهو أعلاها.
        // ⚠️ `>= 0` لا `> 0`: الفهرس ٠ هو الفاتحة الآية ١ — موضعٌ صحيح لا
        // «لا موضع». وغياب الموضع صار `-1` (انظر [LocalStore.quranLastAyah]).
        // صفٌّ واحد يجمع مدخلَي الصفحة: «تابع القراءة» (وهو أهمّها لمن يقرأ
        // ورده يومياً فيأخذ العرض كلّه) و«نزّل المصحف كاملاً» أيقونةً بجانبه.
        //
        // ⚠️ **حُذف من هنا اختيار القارئ**: هو موجودٌ أصلاً في شريط شاشة
        // القراءة حيث يُستعمل فعلاً، ووجوده في الفهرس تكرارٌ يزيح السور عن
        // الشاشة. والتكرار في الواجهة ليس سخاءً بل ضريبةٌ على كل فتحة.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lastAyah >= 0) {
                val surah = loaded.surahAt(lastAyah)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { vm.open(Route.QuranSurah(surah.number, lastAyah)) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Teal.copy(alpha = .12f)),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bookmark,
                            contentDescription = null,
                            tint = Teal,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // الموضع في سطر واحد: «تابع» يكفي عنواناً، والمهمّ
                            // بعده أين وقف — لا شرحُ ما يفعله الزرّ.
                            "تابع: ${loaded.surahAt(lastAyah).name} ${lastAyah - surah.start + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Teal,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            WholeMushafChip(vm, loaded, riwaya)
        }

        // ملاحظة: «علاماتي» انتقل إلى الشريط العلوي (انظر [QuranBookmarksAction])
        // — فهو مدخلٌ يُزار عند الحاجة، ومكانه في المتن كان يزيح الفهرس.

        PrimaryTabRow(selectedTabIndex = tab) {
            TAB_TITLES.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }

        when (tab) {
            0 -> SurahList(loaded, vm)
            1 -> MarkList(loaded, loaded.juzs, "الجزء", vm)
            2 -> MarkList(loaded, loaded.hizbs, "الحزب", vm)
            else -> MarkList(loaded, loaded.pages, "صفحة", vm)
        }
    }

    if (bookmarksSheet) {
        QuranBookmarksSheet(
            index = loaded,
            bookmarks = bookmarks,
            onOpen = {
                vm.setQuranBookmarksOpen(false)
                vm.openQuranAtFlatAyah(it)
            },
            onRemove = { vm.store.toggleQuranBookmark(it) },
            onDismiss = { vm.setQuranBookmarksOpen(false) },
        )
    }
}

/**
 * ⭐ ورقة العلامات — الآيات التي علّمها القارئ بيده.
 *
 * صفٌّ واحد لكل علامة: موضعها بالكلام (سورة كذا — الآية كذا) لا برقم مسطّح
 * لا يعني أحداً، ونجمةٌ لنزعها من مكانها بلا شاشة إدارة ثانية.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QuranBookmarksSheet(
    index: QuranIndex,
    bookmarks: List<Int>,
    onOpen: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "علاماتي",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            textAlign = TextAlign.Center,
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(bookmarks, key = { it }) { flat ->
                val surah = index.surahAt(flat)
                ListItem(
                    modifier = Modifier.clickable { onOpen(flat) },
                    leadingContent = { Icon(Icons.Filled.Star, null, tint = SecondaryGold) },
                    headlineContent = {
                        Text("سورة ${surah.name}", fontWeight = FontWeight.Bold)
                    },
                    supportingContent = {
                        Text(
                            "الآية ${flat - surah.start + 1}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { onRemove(flat) }) { Text("إزالة") }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private val TAB_TITLES = listOf("السور", "الأجزاء", "الأحزاب", "الصفحات")

/**
 * شريحة فعلٍ واحدة في صفّ الفهرس — أيقونة وكلمة واحدة.
 *
 * الحالة تُكتب **في الشريحة نفسها** لا في سطرٍ ثانٍ تحتها: اسم القارئ، أو
 * الحجم المنزَّل، أو تقدّم التنزيل. فالسطر الثاني يضاعف الارتفاع مقابل شرحٍ
 * يقرؤه المستخدم مرّة واحدة في عمره.
 */
@Composable
private fun QuranActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Teal,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .40f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * ⬇️ «نزّل المصحف كاملاً» — شريحة واحدة بجانب «تابع القراءة».
 *
 * تبقى في الفهرس لأنّها **لا مدخل لها في مكان آخر**، بخلاف اختيار القارئ
 * الموجود في شريط شاشة القراءة. وتذكر **الحجم المنزَّل فعلاً** لا وعداً
 * مبهماً: من حقّ صاحب الهاتف أن يعرف ما يشغله قبل أن يشغله وبعده.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WholeMushafChip(
    vm: AppViewModel,
    index: QuranIndex,
    riwayaId: String,
) {
    val riwaya = index.riwaya(riwayaId)
    val storeRevision by vm.store.revision.collectAsState()
    val downloadRevision by vm.quranDownloads.revision.collectAsState()
    val progress by vm.quranDownloads.progress.collectAsState()

    val savedId = remember(storeRevision, riwaya.id) { vm.store.quranReciter(riwaya.id) }
    val reciter = riwaya.reciters.firstOrNull { it.id == savedId } ?: riwaya.defaultReciter
    val bytes = remember(downloadRevision, reciter?.id) {
        reciter?.let { vm.quranDownloads.bytesFor(it.id) } ?: 0L
    }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val running = progress != null

    QuranActionChip(
        icon = if (running) Icons.Filled.Close else Icons.Filled.Download,
        label = when {
            running -> progress?.let { "${it.done}/${it.total}" } ?: "إيقاف"
            bytes > 0L -> formatSize(bytes)
            else -> "نزّل المصحف"
        },
        tint = if (running) MaterialTheme.colorScheme.error else Teal,
        onClick = {
            val r = reciter ?: return@QuranActionChip
            when {
                running -> vm.cancelSurahDownload()
                // الحذف بسؤال صريح لا بنقرة — القاعدة نفسها في كل التطبيق.
                bytes > 0L -> confirmDeleteAll = true
                else -> vm.downloadWholeMushaf(r)
            }
        },
    )

    if (confirmDeleteAll) {
        ConfirmDeleteDownload(
            title = "حذف كل التلاوات المنزَّلة؟",
            body = "سيُحرَّر ${formatSize(bytes)}، وستحتاج إنترنت للاستماع بعدها. " +
                "نصّ المصحف يبقى متاحاً دائماً بلا إنترنت.",
            onConfirm = {
                confirmDeleteAll = false
                vm.quranDownloads.deleteAll()
                vm.showMessage("حُذفت التلاوات المنزَّلة.")
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

/**
 * 🖼️ صفّ «نزّل صور المصحف المصوَّر».
 *
 * ثلاث حالات في صفّ واحد كصفّ التلاوة تماماً: نزِّل / أوقِف / احذف — نفس
 * الأسلوب فلا يتعلّم المستخدم نظاماً ثانياً لأمرٍ واحد.
 */
@Composable
private fun MushafPagesRow(vm: AppViewModel, riwayaId: String) {
    val downloadRevision by vm.quranDownloads.revision.collectAsState()
    val progress by vm.quranDownloads.pageProgress.collectAsState()
    val bytes = remember(downloadRevision) { vm.quranDownloads.pagesBytes(riwayaId) }
    val count = remember(downloadRevision) { vm.quranDownloads.downloadedPageCount(riwayaId) }
    val running = progress != null
    var confirmDelete by remember { mutableStateOf(false) }
    val complete = count >= com.ali.menbaradkshk.data.MushafRepository.PAGE_COUNT

    ListItem(
        modifier = Modifier.clickable {
            if (running) vm.cancelPagesDownload() else vm.downloadMushafPages(riwayaId)
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent,
        ),
        leadingContent = {
            Icon(
                when {
                    running -> Icons.Filled.Close
                    complete -> Icons.Filled.DownloadDone
                    else -> Icons.Filled.MenuBook
                },
                null,
                tint = when {
                    running -> MaterialTheme.colorScheme.error
                    complete -> GreenBrand
                    else -> Teal
                },
            )
        },
        headlineContent = {
            Text(
                if (running) "إيقاف تنزيل الصور" else "نزّل صور المصحف المصوَّر",
                fontWeight = FontWeight.Bold,
            )
        },
        supportingContent = {
            val label = when {
                running -> progress?.let {
                    "صفحة ${it.done} من ${it.total}"
                }.orEmpty()
                complete -> "كل الصفحات منزَّلة (${formatSize(bytes)}) • تعمل بلا إنترنت"
                count > 0 -> "$count من ٦٠٤ صفحة (${formatSize(bytes)}) — اضغط لإكمالها"
                else -> "٦٠٤ صفحة، نحو ٥١ م.ب — ليعمل المصحف المصوَّر بلا إنترنت"
            }
            Text(label, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = if (count > 0 && !running) {
            {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            null
        },
    )

    if (confirmDelete) {
        ConfirmDeleteDownload(
            title = "حذف صور المصحف المنزَّلة؟",
            body = "سيُحرَّر ${formatSize(bytes)}، وستحتاج إنترنت لعرض المصحف " +
                "المصوَّر بعدها. النصّ المكتوب يبقى متاحاً دائماً بلا إنترنت.",
            onConfirm = {
                confirmDelete = false
                vm.deleteMushafPages()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/// صيغة حجم عربيّة مختصرة — الأرقام وحدها تكفي، بلا خانات عشريّة زائدة.
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824.0)} غ.ب"
    bytes >= 1_048_576L -> "${bytes / 1_048_576} م.ب"
    bytes >= 1024L -> "${bytes / 1024} ك.ب"
    else -> "$bytes بايت"
}

/**
 * ورقة اختيار القارئ — مشتركة بين شاشة الفهرسة وشاشة القراءة.
 *
 * القرّاء بنمط «آية بآية» أوّلاً لأنّهم وحدهم من يتيح تمييز الآية الجارية،
 * وهو أهمّ ما في الصفحة. والفرق مكتوب صراحةً تحت كل قارئ من النمط الآخر
 * كي لا يظنّ المستخدم أنّ الميزة تعطّلت عنده.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReciterSheet(
    riwaya: com.ali.menbaradkshk.data.Riwaya,
    currentId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "اختر القارئ — ${riwaya.name}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            textAlign = TextAlign.Center,
        )
        // تنبيه صريح حين لا تملك الرواية أيّ قارئ «آية بآية» — قالون اليوم
        // كذلك: لا تلاوة آية-بآية منشورة له. قول ذلك مرّة خيرٌ من أن يجرّب
        // المستخدم النقر على الآيات فيظنّ التطبيق معطّلاً.
        if (!riwaya.hasPerAyah) {
            Text(
                "تلاوات هذه الرواية بملفّ سورة كاملة، فلا يتوفّر فيها تمييز " +
                    "الآية الجارية ولا البدء من آية بعينها. والنصّ والقراءة " +
                    "يعملان كاملَين.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(riwaya.reciters, key = { it.id }) { option ->
                ListItem(
                    modifier = Modifier.clickable { onPick(option.id) },
                    leadingContent = {
                        Icon(
                            Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            tint = if (option.id == currentId) {
                                Teal
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = {
                        Text(
                            option.name,
                            fontWeight = if (option.id == currentId) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    // شارة موجبة لمن يدعم التمييز خيرٌ من عبارة سلبيّة تحت
                    // كل من لا يدعمه: أقصر، وتوجّه الاختيار إلى الأفضل.
                    trailingContent = if (option.perAyah) {
                        {
                            Text(
                                "آية بآية",
                                style = MaterialTheme.typography.labelSmall,
                                color = Teal,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/// شرائح الروايات — حفص أوّلها دائماً (ترتيب الفهرس نفسه).
@Composable
private fun RiwayaSelector(index: QuranIndex, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(index.riwayat, key = { it.id }) { riwaya ->
            FilterChip(
                selected = riwaya.id == selected,
                onClick = { onSelect(riwaya.id) },
                label = { Text(riwaya.name) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Teal,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

/**
 * 🔍 حقل البحث — سطرٌ واحد يشرح نفسه بالمثال لا بالمصطلح.
 *
 * النصّ التوضيحيّ يذكر **ما يُكتب فيه** («اسم سورة، أو رقم، أو كلمة من آية»)
 * لأنّ كلمة «بحث» وحدها لا تخبر أحداً أنّ الرقم يعمل هنا أيضاً — وهذه أنفع
 * قدراته لمن يفتح على جزء بعينه كل يوم.
 */
@Composable
private fun QuranSearchField(
    query: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // يفتح بلوحة المفاتيح جاهزة: من ضغط «بحث» يريد الكتابة الآن، ونقرةٌ
    // إضافية على الحقل ضريبةٌ بلا سبب.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(focus),
        singleLine = true,
        placeholder = {
            // ⚠️ قصيرٌ عمداً: النصّ الأطول كان يُقصّ بالنقاط في سطر واحد فلا
            // يصل آخره — والمقصوص لا يُعلّم أحداً. ثلاثة أمثلة تكفي للدلالة.
            Text("اسم سورة، أو رقم، أو كلمة من آية", maxLines = 1)
        },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        // زرٌّ واحد لفعلين متدرّجين: يمسح ما كُتب، فإن كان فارغاً أغلق البحث
        // كلّه. فلا يبقى المستخدم حبيس حقلٍ لا يعرف كيف يخرج منه.
        trailingIcon = {
            IconButton(onClick = { if (query.isEmpty()) onClose() else onChange("") }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = if (query.isEmpty()) "إغلاق البحث" else "مسح البحث",
                )
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Teal,
            focusedLeadingIconColor = Teal,
        ),
    )
}

/**
 * نتائج البحث — قائمة واحدة بثلاثة أنواع من النتائج مرتّبة بالأقرب إلى
 * قصد الكاتب: المواضع المطابقة (سورة/جزء/صفحة) أوّلاً لأنّها يقينيّة، ثم
 * الآيات المطابقة نصّاً.
 *
 * ⚠️ بحث نصّ الآيات **بمهلة ٣٥٠ م.ث وخارج خيط الواجهة**: المرور على ٦٢٣٦
 * آية مع كل حرف يُكتب كان يعني تقطيعاً محسوساً في الكتابة نفسها. والمهلة
 * تجعل العمل يقع مرّة واحدة بعد أن يرفع الكاتب يده.
 */
@Composable
private fun QuranSearchResults(
    index: QuranIndex,
    text: List<String>,
    query: String,
    vm: AppViewModel,
) {
    val direct = remember(query, index) { com.ali.menbaradkshk.data.searchQuranIndex(index, query) }
    var ayat by remember { mutableStateOf<List<QuranHit.AyahHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, text) {
        ayat = emptyList()
        searching = true
        kotlinx.coroutines.delay(350L)
        ayat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.ali.menbaradkshk.data.searchQuranText(text, query)
        }
        searching = false
    }

    if (direct.isEmpty() && ayat.isEmpty() && !searching) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                "لا نتائج لـ«$query».\nجرّب اسم السورة بلا «ال»، أو كلمة أخرى من الآية.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(direct.size, key = { "d$it" }) { i ->
            when (val hit = direct[i]) {
                is QuranHit.SurahHit -> ListItem(
                    modifier = Modifier.clickable {
                        vm.open(Route.QuranSurah(hit.surah.number))
                    },
                    leadingContent = { SurahNumberBadge(hit.surah.number) },
                    headlineContent = {
                        Text("سورة ${hit.surah.name}", fontWeight = FontWeight.Bold)
                    },
                    supportingContent = {
                        Text(
                            "${hit.surah.placeLabel} • ${hit.surah.ayahs} آية",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                is QuranHit.MarkHit -> {
                    val surah = index.surahAt(hit.start)
                    ListItem(
                        modifier = Modifier.clickable { vm.openQuranAtFlatAyah(hit.start) },
                        leadingContent = { SurahNumberBadge(hit.number) },
                        headlineContent = {
                            Text("${hit.label} ${hit.number}", fontWeight = FontWeight.Bold)
                        },
                        supportingContent = {
                            Text(
                                "يبدأ من سورة ${surah.name} — الآية ${hit.start - surah.start + 1}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
                is QuranHit.AyahHit -> Unit // لا تأتي من بحث الفهرس
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
        }
        if (ayat.isNotEmpty()) {
            item {
                Text(
                    "آيات فيها «$query»",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        items(ayat, key = { it.flat }) { hit ->
            val surah = index.surahAt(hit.flat)
            ListItem(
                modifier = Modifier.clickable { vm.openQuranAtFlatAyah(hit.flat) },
                headlineContent = {
                    Text(hit.text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        "سورة ${surah.name} — الآية ${hit.flat - surah.start + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Teal,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
        }
    }
}

@Composable
private fun SurahList(index: QuranIndex, vm: AppViewModel) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(index.surahs, key = { it.number }) { surah ->
            ListItem(
                // ⚠️ النقر والنسخ على **الصفّ كلّه** لا على نصّ العنوان وحده:
                // وضع `copyable` على النصّ الداخلي كان يبتلع نقرة الفتح
                // (يستهلكها الابن فلا تصل إلى الصفّ) فلا تُفتح السورة أصلاً.
                modifier = Modifier.copyableOnLongPress(
                    text = { "سورة ${surah.name}" },
                    onClick = { vm.open(Route.QuranSurah(surah.number)) },
                ),
                leadingContent = { SurahNumberBadge(surah.number) },
                headlineContent = {
                    Text("سورة ${surah.name}", fontWeight = FontWeight.Bold)
                },
                supportingContent = {
                    Text(
                        "${surah.placeLabel} • ${surah.ayahs} آية",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
        }
    }
}

/// قائمة موحّدة للأجزاء والأحزاب والصفحات: ثلاثتها «رقم + أوّل آية»، فبناء
/// ثلاث شاشات متطابقة لها تكرارٌ بلا فائدة.
@Composable
private fun MarkList(index: QuranIndex, marks: List<QuranMark>, label: String, vm: AppViewModel) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(marks, key = { it.number }) { mark ->
            val surah = index.surahAt(mark.start)
            ListItem(
                modifier = Modifier.clickable {
                    vm.open(Route.QuranSurah(surah.number, mark.start))
                },
                leadingContent = { SurahNumberBadge(mark.number) },
                headlineContent = { Text("$label ${mark.number}", fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Text(
                        "يبدأ من سورة ${surah.name} — الآية ${mark.start - surah.start + 1}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
        }
    }
}

/**
 * ✴️ شارة الرقم — **نجمة مثمّنة** (خاتم سليمان) كالتي تحفّ أرقام السور في
 * المصاحف المطبوعة، مرسومة بمربّعين متقاطعين.
 *
 * **لماذا رسمٌ لا صورة؟** لأنّ الصورة وزنٌ في الحزمة وحدّةٌ ثابتة لا تتبع
 * كثافة الشاشة، والمربّعان خطّان لا غير: تكلفتهما رسمُ مسارين في إطارٍ لا
 * يُعاد إلا حين يتغيّر الحجم أو اللون (`Canvas` لا يُعيد التركيب).
 *
 * والدائرة السابقة لم تكن خطأً، لكنّ النجمة لغةُ المصحف نفسها — والانتماء
 * البصريّ إلى الشيء الذي تعرضه ليس زينة.
 */
@Composable
private fun SurahNumberBadge(number: Int) {
    val outline = SecondaryGold
    val fill = Teal.copy(alpha = .08f)
    Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f - 1.5.dp.toPx()
            val stroke = 1.4.dp.toPx()
            // مربّعان: أحدهما بزوايا ٤٥° عن الآخر — فيتقاطعان نجمةً ثمانيّة.
            fun square(offsetDegrees: Float) = Path().apply {
                for (i in 0 until 4) {
                    val angle = Math.toRadians((offsetDegrees + i * 90f).toDouble())
                    val x = center.x + radius * kotlin.math.cos(angle).toFloat()
                    val y = center.y + radius * kotlin.math.sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            val a = square(0f)
            val b = square(45f)
            drawPath(a, fill)
            drawPath(b, fill)
            drawPath(a, outline, style = Stroke(stroke))
            drawPath(b, outline, style = Stroke(stroke))
        }
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 🕌 شاشة قراءة سورة — النصّ مع التلاوة المتزامنة.
 *
 * **المزامنة بلا ملفّات توقيتات**: كل آية ملفّ صوتيّ مستقلّ في قائمة
 * التشغيل، فموضع المشغّل في القائمة هو رقم الآية الجارية بالضبط. البطاقة
 * الموافقة تُضاء ويُمرَّر إليها تلقائياً — دقّة تامّة وكلفة صفر.
 *
 * ولأنّ ذلك يعمل عبر خدمة التشغيل نفسها، تعمل التلاوة في الخلفية ومع
 * إشعار التحكّم وأزرار السمّاعة ومؤقّت النوم بلا شيفرة إضافيّة.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuranSurahScreen(
    vm: AppViewModel,
    surahNumber: Int,
    startAyah: Int?,
    playback: PlaybackUiState,
) {
    val index by vm.quranIndex.collectAsState()
    val text by vm.quranText.collectAsState()
    val riwayaId by vm.riwaya.collectAsState()
    val revision by vm.store.revision.collectAsState()

    LaunchedEffect(Unit) { vm.loadQuran() }

    val loaded = index
    if (loaded == null || text.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val surah = loaded.surahs.firstOrNull { it.number == surahNumber } ?: return
    val riwaya = loaded.riwaya(riwayaId)
    val fontSp = remember(revision) { vm.store.quranFontSp() }
    val bold = remember(revision) { vm.store.quranBold() }
    val savedReciterId = remember(revision, riwaya.id) { vm.store.quranReciter(riwaya.id) }
    val reciter = riwaya.reciters.firstOrNull { it.id == savedReciterId }
        ?: riwaya.defaultReciter

    // ⚠️ حالة التمرير مربوطة برقم السورة: الانتقال من سورة إلى أخرى (من
    // «ما يُشغَّل الآن» مثلاً) يبقي نفس موضع النداء في التركيب، فكانت حالة
    // التمرير القديمة تُستعمل لسورة جديدة فيقفز النصّ إلى موضع لا معنى له.
    val listState = rememberSaveable(surahNumber, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var reciterSheet by remember { mutableStateOf(false) }

    // ⚠️ إزاحة الفهارس: عنوان السورة عنصرٌ أوّل **دائماً**، والبسملة عنصرٌ
    // ثانٍ في كل السور عدا الفاتحة والتوبة. وكل حسابات التمرير وحفظ الموضع
    // تمرّ من هنا — فأيّ عنصرٍ يُضاف إلى رأس القائمة يجب أن يُحسب فيها، وإلا
    // أخطأ التمرير وحفظُ الموضع بمقدار ما أُهمل.
    val headerOffset = 1 + if (surah.number != 1 && surah.number != 9) 1 else 0

    /// ⭐ الآيات المعلَّمة في هذه السورة — تُقرأ مرّة لكل مراجعة مخزن لا مع كل
    /// آية تُرسم، فالبحث في القائمة داخل [AyahRow] كان سيتكرّر آلاف المرّات.
    val bookmarks = remember(revision) { vm.store.quranBookmarks().toSet() }

    // الآية الجارية: فهرس عنصر المشغّل، وهو صالح فقط ما دام المُشغَّل تلاوةً
    // من هذه السورة — وإلا فلا تمييز (قد يكون درساً يعمل في الخلفية).
    // وقارئ «السورة كاملة» قائمته عنصر واحد، ففهرسه صفرٌ دائماً: تمييزه كان
    // يُضيء الآية الأولى طوال السورة — تمييزٌ كاذب أسوأ من لا تمييز.
    val playingThisSurah = playback.mediaId.startsWith("q:$surahNumber:")
    val activeAyah = if (playingThisSurah && reciter?.perAyah == true) playback.itemIndex else -1

    // تمرير تلقائي إلى الآية الجارية — بلا هذا تصير المزامنة زينةً لا تُرى
    // لأنّ القارئ يتجاوز حدود الشاشة بعد آيات قليلة.
    LaunchedEffect(activeAyah, surahNumber) {
        if (activeAyah >= 0) {
            runCatching { listState.animateScrollToItem(activeAyah + headerOffset) }
        }
    }

    // فتح على آية بعينها (من الفهرس أو من «تابع القراءة»).
    LaunchedEffect(startAyah, surahNumber) {
        val target = startAyah?.minus(surah.start)?.coerceIn(0, surah.ayahs - 1) ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(target + headerOffset) }
    }

    // حفظ موضع القراءة: أوّل آية ظاهرة على الشاشة.
    //
    // ⚠️ بمهلة قصيرة لا مع كل عنصر يمرّ: الكتابة تمسّ القرص **وترفع رقم
    // مراجعة المخزن** الذي تراقبه شاشات التطبيق كلّها، فتمريرة واحدة على
    // البقرة كانت تُطلق مئات الكتابات وموجات إعادة تركيب — تقطيعٌ محسوس بلا
    // أي فائدة، إذ لا يعني الموضعُ شيئاً إلا حين يستقرّ القارئ عنده.
    LaunchedEffect(listState, surahNumber) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { first ->
                kotlinx.coroutines.delay(600L)
                vm.store.setQuranLastAyah(surah.start + (first - headerOffset).coerceAtLeast(0))
            }
    }

    // ⬇️ حالة التنزيل — تُقرأ من القرص لا من علامة محفوظة (انظر المستودع).
    val downloadProgress by vm.quranDownloads.progress.collectAsState()
    val downloadRevision by vm.quranDownloads.revision.collectAsState()
    val downloaded = remember(downloadRevision, reciter?.id, surah.number) {
        reciter?.let { vm.quranDownloads.isSurahDownloaded(it, surah.number, surah.ayahs) } == true
    }
    val downloadingThis = downloadProgress?.let {
        it.surah == surah.number && it.reciterId == reciter?.id
    } == true

    // 🖼️ نمط العرض: مكتوب أم مصوَّر. الاختيار محفوظ، ويُقرأ من المخزن مع كل
    // مراجعة كبقيّة تفضيلات المصحف.
    //
    // ⛔ ومشروط برواية حفص: الصور صور مصحف حفص المدني، وعرضها تحت اسم ورش أو
    // قالون نسبةُ رسمٍ إلى غير روايته — خطأٌ في كتاب الله لا عيبُ واجهة.
    // فالشرط هنا حارسٌ أخير حتى لو بُدّلت الرواية من شاشة أخرى.
    val mushafSupported = com.ali.menbaradkshk.data.MushafRepository.supportsRiwaya(riwaya.id)
    val imageMode = remember(revision, riwaya.id) {
        vm.store.quranImageMode() && mushafSupported
    }
    val mushafPages by vm.mushafPages.collectAsState()
    /// ظهور الأشرطة في العرض المصوَّر — تُبدَّل بنقرةٍ على الصفحة، وتبدأ
    /// **مخفيّة** كي يرى القارئ المصحف أوّل ما يفتحه لا الأدوات.
    var chromeVisible by rememberSaveable(surahNumber) { mutableStateOf(false) }
    val mushafFlat by vm.mushafFlatNumbering.collectAsState()
    val mushafAspect by vm.mushafAspect.collectAsState()
    val pageStarts by vm.pageStarts.collectAsState()
    LaunchedEffect(imageMode, riwaya.id) { if (imageMode) vm.loadMushaf() }
    // الشريط العلوي للتطبيق يُخفى معها: الشاشة كلّها للمصحف.
    LaunchedEffect(imageMode, chromeVisible) {
        vm.setQuranImmersive(imageMode && !chromeVisible)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.setQuranImmersive(false) }
    }

    // الآية الجارية بفهرسها المسطّح — العرض المصوَّر يتعدّى حدود السورة
    // (الصفحة الواحدة قد تجمع سورتين) فيلزمه الفهرس المطلق لا رقم الآية.
    val activeFlatAyah = if (activeAyah >= 0) surah.start + activeAyah else -1

    // الصفحة المعروضة الآن — تربط العرضين: منها نعرف أين نضع القارئ حين
    // يعود إلى المكتوب.
    var currentPage by rememberSaveable(surahNumber) { mutableStateOf(0) }

    // فتحٌ جديد على آية بعينها داخل السورة نفسها (الجزء ٢ ثم الجزء ٣ مثلاً)
    // يُعيد ضبط الصفحة: بلا ذلك تبقى صفحة الفتح الأوّل لأنّ الشاشة لم تُركَّب
    // من جديد.
    LaunchedEffect(startAyah, pageStarts) {
        if (startAyah != null && pageStarts.isNotEmpty()) {
            currentPage = com.ali.menbaradkshk.data.MushafGeometry
                .pageOfAyah(pageStarts, startAyah)
        }
    }
    /// موضع يُطلب التمرير إليه في النصّ بعد العودة من المصوَّر داخل السورة
    /// نفسها (خارجها ننتقل بشاشة جديدة).
    var textTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(textTarget, imageMode) {
        val target = textTarget ?: return@LaunchedEffect
        if (imageMode) return@LaunchedEffect
        runCatching { listState.scrollToItem(target + headerOffset) }
        textTarget = null
    }

    // 📋 قائمة إجراءات الآية — تُفتح بالضغط المطوّل على الآية.
    var ayahSheet by remember { mutableStateOf<Int?>(null) }
    var confirmDeleteSurah by remember { mutableStateOf(false) }

    /**
     * ⚠️ البسملة تُؤخذ من **نصّ الرواية نفسها** (الفاتحة، الآية ١) لا من ثابتٍ
     * مكتوب في الشيفرة.
     *
     * رسمها يختلف بين الروايات: حفص «بِسْمِ ٱللَّهِ…» بألف الوصل، وورش وقالون
     * «بِسْمِ اِ۬للَّهِ…». وكان الثابت المكتوب لا يطابق **أيّاً** منها (ألفٌ
     * عاديّة)، فكانت البسملة فوق كل سورة رسماً لا يُنسب إلى الرواية المعروضة —
     * وهو خطأ في كتاب الله لا عيبُ واجهة.
     *
     * والنصّ مضمون غيرُ فارغ: الشاشة لا تُركَّب أصلاً قبل تحميله (انظر أعلاه).
     */
    val basmala = text[0]

    /// نصّ السورة كاملاً للنسخ/المشاركة — يُبنى عند الطلب فقط.
    fun surahText(): String = buildString {
        append("سورة ").append(surah.name).append(" — ").append(riwaya.name).append('\n')
        if (surah.number != 1 && surah.number != 9) append(basmala).append('\n')
        for (i in 0 until surah.ayahs) {
            append(text.getOrElse(surah.start + i) { "" })
            append(" ﴿").append(i + 1).append("﴾\n")
        }
        append("\n— من تطبيق منبر ادكصهك")
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // 🖼️ في العرض المصوَّر تُخفى الأشرطة افتراضياً وتُستدعى بنقرة على
        // الصفحة: صفحة المصحف نسبتها ١:١٫٤٣، فكل شريط يقتطع من ارتفاعها
        // يقتطع من **عرضها** أضعافه — وكانت الصفحة تظهر في أقلّ من نصف
        // الشاشة. والمصحف يُقرأ لا يُدار، فالأصل أن تملأ الصفحةُ الشاشة
        // وتختفي الأدوات حتى تُطلب.
        if (!imageMode || chromeVisible) QuranReaderBar(
            surah = surah,
            riwayaName = riwaya.name,
            reciterName = reciter?.name.orEmpty(),
            fontSp = fontSp,
            playing = playingThisSurah && playback.playing,
            currentFont = { vm.store.quranFontSp() },
            onFont = { vm.store.setQuranFontSp(it) },
            bold = bold,
            onBold = { vm.store.setQuranBold(!bold) },
            onReciter = { reciterSheet = true },
            onPlay = {
                if (playingThisSurah) {
                    vm.playback.toggle()
                } else {
                    reciter?.let { vm.playQuran(surah, it) }
                }
            },
            downloaded = downloaded,
            downloading = downloadingThis,
            downloadFraction = downloadProgress?.fraction ?: 0f,
            // ⚠️ الحذف **لا** يقع بنقرة واحدة: نقرة عابرة على أيقونة
            // «منزَّلة» كانت تمحو تنزيلاً كلّف المستخدم بياناته ووقته بلا أن
            // يقصد. الآن تفتح النقرة سؤالاً صريحاً، والحذف قرارٌ لا حادث.
            onDownload = {
                val r = reciter ?: return@QuranReaderBar
                when {
                    downloadingThis -> vm.cancelSurahDownload()
                    downloaded -> confirmDeleteSurah = true
                    else -> vm.downloadSurah(surah, r)
                }
            },
            onShare = { vm.shareText(surahText(), "مشاركة سورة ${surah.name}") },
            imageMode = imageMode,
            // 🔁 التبديل يحفظ الموضع في الاتجاهين — لا يعيد المستخدم إلى أوّل
            // السورة: من المكتوب ننتقل إلى صفحة الآية التي أمامه، ومن
            // المصوَّر نعود إلى أوّل آية في الصفحة التي كان يقرؤها.
            // ✅ يظهر دائماً: الروايات الثلاث كلّها لها مصحف مصوَّر ملوّن رسميّ
            // مستضاف عندنا (انظر [MushafRepository.IMAGE_BASE]).
            showModeToggle = true,
            onToggleMode = {
                if (!mushafSupported) {
                    // نقول السبب ونعرض الحلّ في ضغطة واحدة — لا نُخفي الزرّ
                    // فيظنّ المستخدم أنّ الميزة غير موجودة أصلاً.
                    vm.showUndo(
                        "المصحف المصوَّر متاح برواية حفص فقط.",
                        actionLabel = "بدّل إلى حفص",
                    ) {
                        vm.setRiwaya(com.ali.menbaradkshk.data.QuranRepository.DEFAULT_RIWAYA)
                        vm.setQuranImageMode(true)
                    }
                } else if (!imageMode) {
                    val visible = surah.start +
                        (listState.firstVisibleItemIndex - headerOffset).coerceAtLeast(0)
                    val anchor = if (activeFlatAyah >= 0) activeFlatAyah else visible
                    currentPage = if (pageStarts.isNotEmpty()) {
                        com.ali.menbaradkshk.data.MushafGeometry.pageOfAyah(pageStarts, anchor)
                    } else {
                        0 // يُحسب عند العرض حين تجهز بدايات الصفحات
                    }
                    vm.setQuranImageMode(true)
                } else {
                    val first = com.ali.menbaradkshk.data.MushafGeometry
                        .firstAyahOfPage(pageStarts, currentPage.coerceAtLeast(1))
                    vm.setQuranImageMode(false)
                    // الصفحة قد تكون في سورة أخرى (تُفتح شاشتها)، وإلا فيكفي
                    // التمرير داخل السورة نفسها.
                    if (loaded.surahAt(first).number != surahNumber) {
                        vm.openQuranAtFlatAyah(first)
                    } else {
                        textTarget = (first - surah.start).coerceIn(0, surah.ayahs - 1)
                    }
                }
            },
        )
        // 🎛️ لوحة التحكّم أثناء التلاوة — **بأزرار مشغّل التطبيق نفسها**:
        // السابق/تشغيل كبير ملوّن/التالي، بالألوان والأحجام ذاتها التي
        // يعرفها المستخدم من شاشة الدرس. ولا تظهر إلا أثناء تلاوة هذه
        // السورة، فتبقى صفحة القراءة نظيفة حين لا تلاوة.
        if (playingThisSurah && (!imageMode || chromeVisible)) {
            QuranPlayerControls(
                playing = playback.playing,
                loading = playback.loading,
                onPrevious = { vm.playback.previous() },
                onToggle = { vm.playback.toggle() },
                onNext = { vm.playback.next() },
                onSleep = { vm.playback.setSleepTimer(it) },
                // شريط الموضع يلزم القارئ بملفّ السورة الكاملة وحده: مع
                // «آية بآية» يكفي النقر على الآية للانتقال إليها.
                seek = if (reciter?.perAyah == false) {
                    Triple(playback.positionMs, playback.durationMs) { ms: Long ->
                        vm.playback.seekTo(ms)
                    }
                } else {
                    null
                },
            )
        }
        if (!imageMode || chromeVisible) HorizontalDivider()

        if (imageMode) {
            if (mushafPages.isEmpty() || pageStarts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // ⚠️ ترتيب المراسي مقصود: الآية المتلوّة، ثم الآية المطلوبة
                // عند الفتح (من الأجزاء والأحزاب و«تابع القراءة»)، ثم موضع
                // النصّ. وبلا الوسطى كان فتحُ «الجزء ٢» يقع على أوّل السورة:
                // قائمة النصّ لا تُركَّب أصلاً في العرض المصوَّر فيبقى موضعها
                // صفراً — عطلٌ يظهر في كل مدخل من مداخل الفهرسة الأربعة.
                val anchor = when {
                    activeFlatAyah >= 0 -> activeFlatAyah
                    startAyah != null -> startAyah
                    else -> surah.start +
                        (listState.firstVisibleItemIndex - headerOffset).coerceAtLeast(0)
                }
                // 🔢 **جسر الترقيمين**: مصحف حفص يرقّم بالفهرس المسطّح، ومصحفا
                // ورش وقالون بعدّهما المدنيّ (`سورة×1000 + آية`). فنحوّل مرساة
                // التطبيق — وهي دائماً بعدّ حفص — إلى ترميز المصحف المعروض.
                //
                // ⚠️ والتحويل **تقريبيّ للتنقّل لا للتمييز**: العدّان يفترقان في
                // مواضع معدودة، فيصحّ أن يفتح على الصفحة ولا يصحّ أن يُضيء آية
                // بعينها. ولذلك يبقى التمييز محصوراً بحفص (وهي وحدها التي لها
                // قرّاء آية-بآية أصلاً)، فلا يقع إضاءةٌ على غير موضعها.
                val anchorSurah = loaded.surahAt(anchor)
                val mushafRef = if (mushafFlat) {
                    anchor
                } else {
                    com.ali.menbaradkshk.data.MushafGeometry.riwayaRef(
                        anchorSurah.number,
                        anchor - anchorSurah.start + 1,
                    )
                }
                val activeRef = if (mushafFlat) activeFlatAyah else -1
                QuranMushafView(
                    // ⚠️ `weight` لا `fillMaxSize`: داخل عمودٍ يأخذ الأخيرُ
                    // ما بقي من الارتفاع صراحةً، فلا يتأثّر بظهور الأشرطة
                    // واختفائها.
                    modifier = Modifier.weight(1f),
                    riwayaId = riwaya.id,
                    pages = mushafPages,
                    pageAspect = mushafAspect,
                    pageStarts = pageStarts,
                    initialPage = if (currentPage > 0) {
                        currentPage
                    } else {
                        com.ali.menbaradkshk.data.MushafGeometry
                            .pageOfRef(mushafPages, mushafRef)
                            .takeIf { it > 0 }
                            ?: com.ali.menbaradkshk.data.MushafGeometry
                                .pageOfAyah(pageStarts, anchor)
                    },
                    activeAyah = activeRef,
                    localPage = { vm.quranDownloads.localPage(riwaya.id, it) },
                    onAyahTap = { ref ->
                        val r = reciter ?: return@QuranMushafView
                        // في مصحف الرواية يعود المرجع بترميزها، فنشتقّ منه
                        // السورة ونبدأ التلاوة من أوّلها — وقرّاء ورش وقالون
                        // كلّهم بملفّ سورة كاملة أصلاً، فلا يُفقد شيء.
                        val flat = if (mushafFlat) {
                            ref
                        } else {
                            val surahNumber =
                                com.ali.menbaradkshk.data.MushafGeometry.surahOfRef(ref)
                            loaded.surahs.firstOrNull { it.number == surahNumber }?.start ?: 0
                        }
                        vm.playQuranAtFlatAyah(flat, r)
                        if (!r.perAyah) {
                            vm.showMessage(
                                "${r.name} تلاوته بملفّ سورة كاملة، فلا تبدأ من آية " +
                                    "بعينها ولا يظهر تمييز الآية الجارية.",
                            )
                        }
                    },
                    onChromeToggle = { chromeVisible = !chromeVisible },
                    onPageSettled = { page ->
                        currentPage = page
                        // موضع القراءة يُحفظ من المصوَّر أيضاً، فـ«تابع
                        // القراءة» يعمل مهما كان النمط الذي يقرأ به.
                        vm.store.setQuranLastAyah(
                            com.ali.menbaradkshk.data.MushafGeometry
                                .firstAyahOfPage(pageStarts, page),
                        )
                    },
                )
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            // 🤏 التكبير بالإصبعين على النصّ كلّه — انظر [pinchToZoomFont].
            modifier = Modifier.fillMaxSize().pinchToZoomFont { factor ->
                vm.store.setQuranFontSp(vm.store.quranFontSp() * factor)
            },
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SurahHeader(surah) }
            // البسملة تُعرض مستقلّة في كل السور عدا التوبة (٩)، والفاتحة
            // بسملتها آيةٌ من السورة نفسها فلا تُكرَّر.
            if (surah.number != 1 && surah.number != 9) {
                item {
                    // فسحةٌ أوسع حولها وحجمٌ أهدأ قليلاً من الآيات: البسملة
                    // مفتاحٌ لا آية من السورة، وفصلُها بالفراغ يقول ذلك بلا
                    // كلام. ولونها لون الهويّة الأساس فيصحّ تباينه في السمتين
                    // (جوهر سماويّ في الفاتحة، ذهب في الداكنة).
                    Text(
                        basmala,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
                        textAlign = TextAlign.Center,
                        fontSize = (fontSp * 0.92f).sp,
                        lineHeight = (fontSp * 1.8f).sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(surah.ayahs, key = { it }) { i ->
                AyahRow(
                    number = i + 1,
                    text = text.getOrElse(surah.start + i) { "" },
                    fontSp = fontSp,
                    bold = bold,
                    active = i == activeAyah,
                    bookmarked = (surah.start + i) in bookmarks,
                    // النقر على آية: يبدأ منها إن كان القارئ «آية بآية».
                    // وإن كان بملفّ سورة كاملة فالبدء من آية مستحيل تقنياً —
                    // فنقول ذلك صراحةً ونشغّل السورة، بدل أن يظنّ المستخدم
                    // أنّ النقر لا يعمل أو أنّ التطبيق معطّل.
                    onPlayFromHere = {
                        val r = reciter ?: return@AyahRow
                        vm.playQuran(surah, r, fromAyah = i + 1)
                        if (!r.perAyah) {
                            // لا نكتفي بقول «غير متاح»: إمّا نقترح **قارئاً
                            // بعينه** في الرواية نفسها يدعم البدء من آية —
                            // وننقل إليه بضغطة واحدة — وإمّا نقول صراحةً إنّ
                            // الرواية كلّها ليس فيها من يدعمه. الغموض هنا
                            // يجعل المستخدم يظنّ التطبيق معطّلاً.
                            val alternative = riwaya.reciters.firstOrNull { it.perAyah }
                            if (alternative != null) {
                                vm.showUndo(
                                    "${r.name} تلاوته بملفّ سورة كاملة. " +
                                        "جرّب ${alternative.name} للبدء من أي آية.",
                                    actionLabel = "بدّل",
                                ) {
                                    vm.store.setQuranReciter(riwaya.id, alternative.id)
                                    vm.playQuran(surah, alternative, fromAyah = i + 1)
                                }
                            } else {
                                vm.showMessage(
                                    "لا يتوفّر في رواية ${riwaya.name} قارئ بتلاوة " +
                                        "آية بآية، فتبدأ التلاوة من أوّل السورة.",
                                )
                            }
                        }
                    },
                    onActions = { ayahSheet = i },
                )
            }
        }
    }

    // شريط تقدّم رفيع أسفل الشاشة أثناء التنزيل — يُطمئن بلا أن يحجب النصّ.
    if (downloadingThis) {
        LinearProgressIndicator(
            progress = { downloadProgress?.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(3.dp),
            color = Teal,
        )
    }
    }

    // 📋 إجراءات آية بعينها — أربعة أفعال واضحة بأسماء صريحة، لا أيقونات
    // مبهمة: كثير من مستخدمي التطبيق لا يقرؤون العربية بطلاقة، والأيقونة
    // وحدها تُخمَّن أمّا الصفّ المكتوب فيُقرأ مرّة ويُحفظ.
    ayahSheet?.let { i ->
        val ayahNumber = i + 1
        val body = text.getOrElse(surah.start + i) { "" }
        val full = "$body ﴿$ayahNumber﴾\n[سورة ${surah.name} — الآية $ayahNumber]"
        ModalBottomSheet(onDismissRequest = { ayahSheet = null }) {
            Text(
                "الآية $ayahNumber من سورة ${surah.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
            )
            ListItem(
                modifier = Modifier.clickable {
                    ayahSheet = null
                    reciter?.let { vm.playQuran(surah, it, fromAyah = ayahNumber) }
                },
                leadingContent = { Icon(Icons.Filled.PlayArrow, null, tint = Teal) },
                headlineContent = { Text("تلاوة من هذه الآية") },
            )
            ListItem(
                modifier = Modifier.clickable {
                    ayahSheet = null
                    copyToClipboard(context, full)
                },
                leadingContent = { Icon(Icons.Filled.ContentCopy, null, tint = Teal) },
                headlineContent = { Text("نسخ الآية") },
            )
            ListItem(
                modifier = Modifier.clickable {
                    ayahSheet = null
                    vm.shareText("$full\n\n— من تطبيق منبر ادكصهك", "مشاركة الآية")
                },
                leadingContent = { Icon(Icons.Filled.Share, null, tint = Teal) },
                headlineContent = { Text("مشاركة الآية") },
            )
            ListItem(
                modifier = Modifier.clickable {
                    ayahSheet = null
                    vm.store.setQuranLastAyah(surah.start + i)
                    vm.showMessage("حُفظ موضع القراءة عند الآية $ayahNumber.")
                },
                leadingContent = { Icon(Icons.Filled.Bookmark, null, tint = Teal) },
                headlineContent = { Text("علّم موضع القراءة هنا") },
            )
            // ⭐ علامة دائمة — غير «موضع القراءة» الذي يتحرّك مع التمرير.
            // والفرق مكتوب في السطر التوضيحيّ لا مفهومٌ من الأيقونة، فالاثنان
            // متجاوران وأشباه المعاني تحتاج فرقاً صريحاً.
            val marked = (surah.start + i) in bookmarks
            ListItem(
                modifier = Modifier.clickable {
                    ayahSheet = null
                    val added = vm.store.toggleQuranBookmark(surah.start + i)
                    vm.showMessage(
                        if (added) {
                            "أُضيفت الآية $ayahNumber إلى «علاماتي»."
                        } else {
                            "أُزيلت العلامة عن الآية $ayahNumber."
                        },
                    )
                },
                leadingContent = {
                    Icon(
                        if (marked) Icons.Filled.Star else Icons.Filled.StarBorder,
                        null,
                        tint = SecondaryGold,
                    )
                },
                headlineContent = {
                    Text(if (marked) "إزالة العلامة" else "أضف إلى علاماتي")
                },
                supportingContent = {
                    Text(
                        "علامة تبقى حيث وضعتها، تجدها في أوّل صفحة المصحف.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (reciterSheet) {
        ReciterSheet(
            riwaya = riwaya,
            currentId = reciter?.id,
            onPick = {
                vm.store.setQuranReciter(riwaya.id, it)
                reciterSheet = false
            },
            onDismiss = { reciterSheet = false },
        )
    }

    if (confirmDeleteSurah) {
        ConfirmDeleteDownload(
            title = "حذف تلاوة سورة ${surah.name}؟",
            body = "ستحتاج إنترنت لسماعها بعد الحذف. النصّ يبقى متاحاً دائماً بلا إنترنت.",
            onConfirm = {
                confirmDeleteSurah = false
                reciter?.let { vm.deleteSurahDownload(surah, it) }
            },
            onDismiss = { confirmDeleteSurah = false },
        )
    }
}

/**
 * تأكيد حذف تلاوة منزَّلة.
 *
 * **لماذا سؤال لا فعل مباشر؟** لأنّ التنزيل كلّف صاحبه بياناتٍ ووقتاً، وقد
 * يكون على شبكة لا يملك مثلها الآن. فمحوُه بنقرة عابرة خسارةٌ لا رجعة فيها
 * في اللحظة، والسؤال ثمنه نقرة واحدة فقط.
 */
@Composable
private fun ConfirmDeleteDownload(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إبقاء") } },
    )
}

/**
 * ذهب الزخرفة المقروء في السمتين.
 *
 * ⚠️ ليس `SecondaryGold` (Gold400): تباينه على الأبيض ٣.٦٩ فقط — يصلح حدّاً
 * مرسوماً ولا يصلح **نصّاً**. فالقاعدة المكتوبة في [Theme.kt] صريحة: Gold900
 * للنصّ على الفاتح، ودرجاته الفاتحة للداكن. والسمة تُستنتج من إضاءة الخلفيّة
 * فلا يحتاج كل مستدعٍ أن يمرّر وضع السمة إليه.
 */
@Composable
private fun quranOrnamentGold(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Gold200 else Gold900

/// شريط أدوات القارئ: تشغيل، اختيار القارئ، وتكبير/تصغير الخطّ.
@Composable
private fun QuranReaderBar(
    surah: Surah,
    riwayaName: String,
    reciterName: String,
    fontSp: Float,
    playing: Boolean,
    /// آخر حجم من المخزن — تحتاجه حلقة الضغط المستمرّ (انظر [FontStepButton]).
    currentFont: () -> Float,
    onFont: (Float) -> Unit,
    bold: Boolean,
    onBold: () -> Unit,
    onReciter: () -> Unit,
    onPlay: () -> Unit,
    downloaded: Boolean,
    downloading: Boolean,
    downloadFraction: Float,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    imageMode: Boolean,
    /// يُخفى المبدّل كلّياً حين لا يكون للمصحف المصوَّر مصدرُ صورٍ مرخَّص.
    showModeToggle: Boolean,
    onToggleMode: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "إيقاف التلاوة" else "تشغيل التلاوة",
                tint = Teal,
                modifier = Modifier.size(30.dp),
            )
        }
        // ⬇️ حالة واحدة لثلاثة أفعال في زرّ واحد: نزِّل / ألغِ / احذف.
        // ثلاثة أزرار لثلاث حالات لا يقع منها إلا واحدة = ضجيج بصريّ.
        IconButton(onClick = onDownload) {
            when {
                downloading -> Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { downloadFraction },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Teal,
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "إلغاء التنزيل",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                downloaded -> Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "منزَّلة — اضغط للحذف",
                    tint = GreenBrand,
                )
                else -> Icon(
                    Icons.Filled.Download,
                    contentDescription = "تنزيل للعمل بلا إنترنت",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onShare) {
            Icon(
                Icons.Filled.Share,
                contentDescription = "مشاركة السورة",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            // ⚠️ «مكّية • كذا آية» يظهر هنا **فقط في العرض المصوَّر**: في
            // المكتوب صار رأس السورة يقوله بخطّ أكبر وأوضح، وتكراره في سطرٍ
            // صغير فوقه ضجيجٌ لا يفيد أحداً.
            if (imageMode) {
                Text(
                    "${surah.placeLabel} • ${surah.ayahs} آية",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onReciter,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    " $riwayaName — ${reciterName.ifBlank { "اختر قارئاً" }}",
                    style = MaterialTheme.typography.bodySmall,
                    // سطر واحد دائماً: التفافه إلى سطرين كان يزحم شريط
                    // الأدوات ويدفع أزراره، وأسماء القرّاء تطول كثيراً.
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // 🔁 مبدّل العرض — **مكتوب بالكلمات لا بأيقونة**: جمهور المصحف فيه
        // كبار سنّ، والأيقونة تُخمَّن أمّا الكلمة فتُقرأ. ويُكتب فيه **ما
        // سينتقل إليه** لا ما هو فيه، فالزرّ وعدٌ بالنتيجة لا وصفٌ للحال.
        if (showModeToggle) TextButton(onClick = onToggleMode) {
            Icon(
                if (imageMode) Icons.Filled.Article else Icons.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Teal,
            )
            Text(
                if (imageMode) " مكتوب" else " مصوَّر",
                style = MaterialTheme.typography.bodySmall,
                color = Teal,
                fontWeight = FontWeight.Bold,
            )
        }
        // مكبّر الخطّ: **الاستمرار بالضغط يواصل التكبير** حتى الحجم المطلوب،
        // والنقرة المفردة تبقى للضبط الدقيق. ولا يظهر في العرض المصوَّر لأنّ
        // الصفحة صورةٌ لا نصّ — والتكبير فيها بالإصبعين.
        if (!imageMode) {
            // 🅱️ «عريض» — **كلمةٌ لا أيقونة**، ومكتوبةٌ بالوزن الذي تصفه فترى
            // العينُ أثرها قبل أن تقرأها. وهو العنصر الجديد الوحيد في الشريط:
            // التكبير بالإصبعين إيماءةٌ بلا زرّ، فثلاث طرق للتكبير لا تعني
            // ثلاثة أزرار.
            TextButton(
                onClick = onBold,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    "عريض",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    // لون الحالة من السمة لا `Teal` الثابت: الجوهر الغامق
                    // يذوب في الخلفيّة الداكنة، و`primary` يتبدّل معها.
                    color = if (bold) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            FontStepButton(
                label = "−",
                enabled = fontSp > LocalStore.QURAN_FONT_MIN,
                onStep = { onFont(currentFont() - (currentFont() * 0.10f).coerceAtLeast(1f)) },
                size = 40,
                fontSize = 20,
            )
            FontStepButton(
                label = "+",
                enabled = fontSp < LocalStore.QURAN_FONT_MAX,
                onStep = { onFont(currentFont() + (currentFont() * 0.10f).coerceAtLeast(1f)) },
                size = 40,
                fontSize = 24,
            )
        }
    }
}

/**
 * 🎛️ تحكّم التلاوة — **نسخة مطابقة لصفّ تحكّم مشغّل التطبيق**.
 *
 * الأزرار نفسها بالألوان والأحجام نفسها (السابق/التالي أزرقان ٤٠ نقطة، وزرّ
 * التشغيل دائرة ٧٢ نقطة خضراء أثناء التشغيل وبرتقاليّة عند التوقّف). التطابق
 * مقصود: المستخدم تعلّم هذه الأزرار في شاشة الدرس، فتعليمه لغةً ثانية في
 * المصحف تعقيدٌ بلا مقابل.
 *
 * ويُضاف شريط الموضع ومؤقّت النوم فقط حين يفيدان.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QuranPlayerControls(
    playing: Boolean,
    loading: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSleep: (Int) -> Unit,
    seek: Triple<Long, Long, (Long) -> Unit>?,
) {
    var sleepSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        seek?.let { (position, duration, onSeek) ->
            if (duration > 0L) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        clock(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.Slider(
                        value = position.coerceIn(0L, duration).toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        clock(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { sleepSheet = true }) {
                Icon(Icons.Filled.Bedtime, contentDescription = "مؤقّت نوم", tint = Teal)
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "السابق",
                    tint = BlueBrand,
                    modifier = Modifier.size(40.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(if (playing) GreenBrand else OrangeBrand, CircleShape)
                    .clickable(enabled = !loading, onClick = onToggle),
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
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "التالي",
                    tint = BlueBrand,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.size(48.dp))
        }
    }

    if (sleepSheet) {
        ModalBottomSheet(onDismissRequest = { sleepSheet = false }) {
            Text(
                "مؤقّت نوم",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
            )
            listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                ListItem(
                    modifier = Modifier.clickable {
                        onSleep(minutes)
                        sleepSheet = false
                    },
                    headlineContent = { Text("بعد $minutes دقيقة") },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 🤏 التكبير بإصبعين على نصّ المصحف.
 *
 * **لماذا معالجةٌ يدويّة لا `detectTransformGestures`؟** لأنّ تلك تلتقط
 * السحب بإصبع واحد أيضاً، فتبتلع تمرير القائمة — والقارئ يمرّر أضعافَ ما
 * يكبّر. فهنا لا نلمس الحدث إلا حين يكون على الشاشة **إصبعان**، وعندها فقط
 * نستهلكه فلا تتحرّك القائمة تحت الإصبعين.
 *
 * ⚠️ والعتبة (٦٪ تراكميّاً) ليست تجميلاً: بلا تراكمٍ كانت كل حركة صغيرة
 * تكتب في المخزن وترفع رقم المراجعة الذي تراقبه شاشات التطبيق كلّها — عشرات
 * الكتابات في ضمّة إصبعين واحدة.
 */
private fun Modifier.pinchToZoomFont(onZoom: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var accumulated = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    accumulated *= event.calculateZoom()
                    if (accumulated > 1.06f || accumulated < 0.94f) {
                        onZoom(accumulated)
                        accumulated = 1f
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * 🕌 رأس السورة — إطارٌ هندسيّ خفيف باسمها وعدد آياتها ومكيّة/مدنيّة.
 *
 * **لماذا أصلاً؟** لأنّ الشاشة كانت تبدأ بالنصّ مباشرة، فمن فتحها من علامة
 * أو من إشعار لا يعرف أين هو. والاسم في شريط الأدوات صغيرٌ مزاحمٌ بالأزرار.
 *
 * وخطوطه خطّان أفقيّان وإطارٌ ذهبيّ رفيع لا صورة: الزخرفة المرسومة تتبع كثافة
 * الشاشة ولا تزن في الحزمة شيئاً، وتبقى صادقة في السمتين لأنّ الذهب لونُ
 * الهويّة في كلتيهما.
 */
@Composable
private fun SurahHeader(surah: Surah) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
            .border(1.dp, SecondaryGold.copy(alpha = .55f), RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoldRule()
                Text(
                    "سورة ${surah.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                GoldRule()
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${surah.placeLabel} • ${surah.ayahs} آية",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/// خطّ ذهبيّ رفيع يحفّ اسم السورة — يتلاشى نحو طرفه فلا يبدو قطعاً حادّاً.
@Composable
private fun GoldRule() {
    Box(
        Modifier
            .width(28.dp)
            .height(1.dp)
            .background(SecondaryGold.copy(alpha = .7f)),
    )
}

/**
 * بطاقة آية واحدة.
 *
 * الآية الجارية تُميَّز بخلفيّة هادئة وشريط ذهبيّ رفيع على حافّتها — لا بلون
 * صارخ: النصّ هو البطل، والتمييز خدمةٌ له لا منافس. والنقر على الآية يبدأ
 * التلاوة منها، والضغط المطوّل يفتح بقيّة الأفعال.
 */
@Composable
private fun AyahRow(
    number: Int,
    text: String,
    fontSp: Float,
    bold: Boolean,
    active: Boolean,
    bookmarked: Boolean,
    onPlayFromHere: () -> Unit,
    onActions: () -> Unit,
) {
    // ⚠️ انتقال لونيّ لا قفزة: التمييز ينتقل من آية إلى أخرى مع كل آية تُتلى،
    // والتبدّل الفوريّ كان يومض في العين كوميض الإعلانات. ٢٥٠ م.ث تكفي لتُقرأ
    // الحركة اتّصالاً لا انقطاعاً، وهي حركة لونٍ واحدة لا إعادة تخطيط.
    val target = if (active) SecondaryGold.copy(alpha = .14f) else Color.Transparent
    val background by animateColorAsState(target, tween(250), label = "ayahBackground")
    val stripe by animateColorAsState(
        if (active) SecondaryGold else Color.Transparent,
        tween(250),
        label = "ayahStripe",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .background(background)
            // شريط الحافّة يُرسم رسماً لا بعنصرٍ ثالث في التخطيط: عمودٌ إضافيّ
            // في كل آية كلفةٌ في القياس والتركيب بلا مقابل بصريّ.
            .drawBehind {
                if (stripe.alpha == 0f) return@drawBehind
                val w = 3.dp.toPx()
                val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                drawRect(
                    color = stripe,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        if (rtl) size.width - w else 0f,
                        0f,
                    ),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                )
            }
            // نقرة = تلاوة من هنا (الفعل الأكثر طلباً، فبأقلّ كلفة).
            // ضغطة مطوّلة = بقيّة الأفعال في ورقة واحدة — بلا أزرار مبعثرة
            // حول كل آية، وهو ما يُبقي صفحة المصحف نظيفة كالمصحف الورقي.
            .combinedClickable(
                onClick = onPlayFromHere,
                onLongClick = onActions,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val ornament = quranOrnamentGold()
        Text(
            // رقم الآية داخل النصّ نفسه بين قوسي الزخرفة — كما في المصحف
            // الورقي، لا في عمود جانبيّ يقطع انسياب القراءة. ويُلوَّن بلون
            // الزخرفة ويصغر قليلاً: يُميَّز عن كلام الله بلا أن يخرج من سطره.
            buildAnnotatedString {
                append(text)
                append(' ')
                withStyle(SpanStyle(color = ornament, fontSize = 0.72.em)) {
                    append("﴿${com.ali.menbaradkshk.data.arabicIndicDigits(number)}﴾")
                    // ⭐ نجمةٌ صغيرة داخل السطر تدلّ على الآية المعلَّمة —
                    // بلا أيقونة على الحافّة تزاحم شريط الآية الجارية.
                    if (bookmarked) append(" ★")
                }
            },
            fontSize = fontSp.sp,
            // ⭐ الوزن العريض — أهمّ من الحجم لضعيف البصر: الحرف الرفيع
            // المكبَّر يبقى باهتاً، والعرض يفصله عن الخلفيّة. وهو وزنٌ أصليّ
            // من عائلة Amiri (`amiri_bold`) لا تغليظٌ مصطنع.
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            // ارتفاع السطر نسبيّ (٢×): الرسم العثماني بعلاماته يحتاج فسحة،
            // وبقيمة ثابتة كان النصّ المكبَّر يتراكب فيصير غير مقروء.
            lineHeight = (fontSp * 2f).sp,
            textAlign = TextAlign.Justify,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
