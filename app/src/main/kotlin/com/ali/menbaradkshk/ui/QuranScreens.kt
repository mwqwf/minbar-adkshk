package com.ali.menbaradkshk.ui

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.menbaradkshk.data.LocalStore
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
    val lastAyah = remember(revision) { vm.store.quranLastAyah() }

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

        // «تابع القراءة» — يعيد المستخدم إلى موضعه بالضبط. أهمّ زرّ في
        // الشاشة لمن يقرأ ورده يومياً، فهو أعلاها.
        if (lastAyah > 0) {
            val surah = loaded.surahAt(lastAyah)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable {
                        vm.open(Route.QuranSurah(surah.number, lastAyah))
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Teal.copy(alpha = .12f)),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Teal)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("تابع القراءة", fontWeight = FontWeight.Bold, color = Teal)
                        Text(
                            "سورة ${surah.name} — الآية ${lastAyah - surah.start + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 🎧 القارئ المفضَّل وتنزيل المصحف كاملاً — بطاقة واحدة تجمع أهمّ
        // ما يجهله المستخدم عن هذه الصفحة. صياغتها فعلٌ صريح لا وصفٌ عامّ
        // («اختر قارئك المفضَّل» لا «إعدادات التلاوة»)، لأنّ أغلب المستخدمين
        // لا يفتحون ما لا يفهمون عنوانه.
        ReciterAndOfflineCard(vm, loaded, riwaya)

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
}

private val TAB_TITLES = listOf("السور", "الأجزاء", "الأحزاب", "الصفحات")

/**
 * 🎧 «قارئك المفضَّل» + «نزّل المصحف كاملاً».
 *
 * ميزتان قويّتان لا يكتشفهما أحد إن بقيتا مدفونتين في شاشة القراءة: عدد
 * القرّاء (عشرات لكل رواية) وإمكان العمل بلا إنترنت. فنعرضهما هنا في سطرين
 * صريحين — لا شرح ولا إعدادات، فعلان اثنان فقط.
 *
 * وزرّ التنزيل الكامل يذكر **الحجم المنزَّل فعلاً** لا وعداً مبهماً: من حقّ
 * صاحب الهاتف أن يعرف ما يشغله قبل أن يشغله وبعده.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReciterAndOfflineCard(
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
    var sheet by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val running = progress != null

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .40f),
        ),
    ) {
        Column {
            ListItem(
                modifier = Modifier.clickable { sheet = true },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
                leadingContent = { Icon(Icons.Filled.RecordVoiceOver, null, tint = Teal) },
                headlineContent = { Text("قارئك المفضَّل", fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Text(
                        "${reciter?.name ?: "اختر قارئاً"} • ${riwaya.reciters.size} قارئاً متاحاً",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable {
                    val r = reciter ?: return@clickable
                    if (running) vm.cancelSurahDownload() else vm.downloadWholeMushaf(r)
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                ),
                leadingContent = {
                    Icon(
                        if (running) Icons.Filled.Close else Icons.Filled.Download,
                        null,
                        tint = if (running) MaterialTheme.colorScheme.error else Teal,
                    )
                },
                headlineContent = {
                    Text(
                        if (running) "إيقاف التنزيل" else "نزّل المصحف كاملاً",
                        fontWeight = FontWeight.Bold,
                    )
                },
                supportingContent = {
                    val label = when {
                        running -> progress?.let {
                            "سورة ${it.surah} — ${it.done} من ${it.total}"
                        }.orEmpty()
                        bytes > 0L -> "المنزَّل: ${formatSize(bytes)} • يعمل بلا إنترنت"
                        else -> "ليعمل بلا إنترنت — يُكمل من حيث توقّف إن انقطع"
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall)
                },
                // الحذف بسؤال صريح لا بنقرة — القاعدة نفسها في كل التطبيق.
                trailingContent = if (bytes > 0L && !running) {
                    {
                        TextButton(onClick = { confirmDeleteAll = true }) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }

    if (sheet) {
        ReciterSheet(
            riwaya = riwaya,
            currentId = reciter?.id,
            onPick = {
                vm.store.setQuranReciter(riwaya.id, it)
                sheet = false
            },
            onDismiss = { sheet = false },
        )
    }

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

/// شارة رقم بنمط النجمة المثمّنة المألوفة في المصاحف — مبسّطة إلى دائرة
/// بحدّ ذهبيّ تتّسق مع هويّة التطبيق.
@Composable
private fun SurahNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(1.5.dp, SecondaryGold, CircleShape)
            .background(Teal.copy(alpha = .08f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Teal,
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
    val savedReciterId = remember(revision, riwaya.id) { vm.store.quranReciter(riwaya.id) }
    val reciter = riwaya.reciters.firstOrNull { it.id == savedReciterId }
        ?: riwaya.defaultReciter

    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var reciterSheet by remember { mutableStateOf(false) }

    // الآية الجارية: فهرس عنصر المشغّل، وهو صالح فقط ما دام المُشغَّل تلاوةً
    // من هذه السورة — وإلا فلا تمييز (قد يكون درساً يعمل في الخلفية).
    val playingThisSurah = playback.mediaId.startsWith("q:$surahNumber:")
    val activeAyah = if (playingThisSurah) playback.itemIndex else -1

    // تمرير تلقائي إلى الآية الجارية — بلا هذا تصير المزامنة زينةً لا تُرى
    // لأنّ القارئ يتجاوز حدود الشاشة بعد آيات قليلة.
    LaunchedEffect(activeAyah) {
        if (activeAyah >= 0) {
            runCatching { listState.animateScrollToItem(activeAyah) }
        }
    }

    // فتح على آية بعينها (من الفهرس أو من «تابع القراءة»).
    LaunchedEffect(startAyah, surahNumber) {
        val target = startAyah?.minus(surah.start)?.coerceIn(0, surah.ayahs - 1) ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(target) }
    }

    // حفظ موضع القراءة: أوّل آية ظاهرة على الشاشة.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { vm.store.setQuranLastAyah(surah.start + it) }
    }

    // ⬇️ حالة التنزيل — تُقرأ من القرص لا من علامة محفوظة (انظر المستودع).
    val downloadProgress by vm.quranDownloads.progress.collectAsState()
    val downloadRevision by vm.quranDownloads.revision.collectAsState()
    val downloaded = remember(downloadRevision, reciter?.id, surah.number) {
        reciter?.let { vm.quranDownloads.isSurahDownloaded(it.id, surah.number, surah.ayahs) } == true
    }
    val downloadingThis = downloadProgress?.let {
        it.surah == surah.number && it.reciterId == reciter?.id
    } == true

    // 📋 قائمة إجراءات الآية — تُفتح بالضغط المطوّل على الآية.
    var ayahSheet by remember { mutableStateOf<Int?>(null) }
    var confirmDeleteSurah by remember { mutableStateOf(false) }

    /// نصّ السورة كاملاً للنسخ/المشاركة — يُبنى عند الطلب فقط.
    fun surahText(): String = buildString {
        append("سورة ").append(surah.name).append(" — ").append(riwaya.name).append('\n')
        if (surah.number != 1 && surah.number != 9) append(BASMALA).append('\n')
        for (i in 0 until surah.ayahs) {
            append(text.getOrElse(surah.start + i) { "" })
            append(" ﴿").append(i + 1).append("﴾\n")
        }
        append("\n— من تطبيق منبر ادكصهك")
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        QuranReaderBar(
            surah = surah,
            riwayaName = riwaya.name,
            reciterName = reciter?.name.orEmpty(),
            fontSp = fontSp,
            playing = playingThisSurah && playback.playing,
            currentFont = { vm.store.quranFontSp() },
            onFont = { vm.store.setQuranFontSp(it) },
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
        )
        // 🎛️ لوحة التحكّم أثناء التلاوة — **بأزرار مشغّل التطبيق نفسها**:
        // السابق/تشغيل كبير ملوّن/التالي، بالألوان والأحجام ذاتها التي
        // يعرفها المستخدم من شاشة الدرس. ولا تظهر إلا أثناء تلاوة هذه
        // السورة، فتبقى صفحة القراءة نظيفة حين لا تلاوة.
        if (playingThisSurah) {
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
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // البسملة تُعرض مستقلّة في كل السور عدا التوبة (٩)، والفاتحة
            // بسملتها آيةٌ من السورة نفسها فلا تُكرَّر.
            if (surah.number != 1 && surah.number != 9) {
                item {
                    Text(
                        BASMALA,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        textAlign = TextAlign.Center,
                        fontSize = (fontSp * 0.95f).sp,
                        color = Teal,
                    )
                }
            }
            items(surah.ayahs, key = { it }) { i ->
                AyahRow(
                    number = i + 1,
                    text = text.getOrElse(surah.start + i) { "" },
                    fontSp = fontSp,
                    active = i == activeAyah,
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

private const val BASMALA = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"

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
    onReciter: () -> Unit,
    onPlay: () -> Unit,
    downloaded: Boolean,
    downloading: Boolean,
    downloadFraction: Float,
    onDownload: () -> Unit,
    onShare: () -> Unit,
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
            Text(
                "${surah.placeLabel} • ${surah.ayahs} آية",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        // مكبّر الخطّ: **الاستمرار بالضغط يواصل التكبير** حتى الحجم المطلوب،
        // والنقرة المفردة تبقى للضبط الدقيق.
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

private const val QURAN_FONT_STEP = 3f

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
 * بطاقة آية واحدة.
 *
 * الآية الجارية تُميَّز بخلفيّة هادئة وحدّ جانبيّ ذهبيّ — لا بلون صارخ:
 * النصّ هو البطل، والتمييز خدمةٌ له لا منافس. والنقر على الآية يبدأ التلاوة
 * منها، والضغط المطوّل ينسخها.
 */
@Composable
private fun AyahRow(
    number: Int,
    text: String,
    fontSp: Float,
    active: Boolean,
    onPlayFromHere: () -> Unit,
    onActions: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (active) SecondaryGold.copy(alpha = .14f) else Color.Transparent)
            // نقرة = تلاوة من هنا (الفعل الأكثر طلباً، فبأقلّ كلفة).
            // ضغطة مطوّلة = بقيّة الأفعال في ورقة واحدة — بلا أزرار مبعثرة
            // حول كل آية، وهو ما يُبقي صفحة المصحف نظيفة كالمصحف الورقي.
            .combinedClickable(
                onClick = onPlayFromHere,
                onLongClick = onActions,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            // رقم الآية داخل النصّ نفسه بين قوسي الزخرفة — كما في المصحف
            // الورقي، لا في عمود جانبيّ يقطع انسياب القراءة.
            "$text ﴿$number﴾",
            fontSize = fontSp.sp,
            // ارتفاع السطر نسبيّ (٢×): الرسم العثماني بعلاماته يحتاج فسحة،
            // وبقيمة ثابتة كان النصّ المكبَّر يتراكب فيصير غير مقروء.
            lineHeight = (fontSp * 2f).sp,
            textAlign = TextAlign.Justify,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
