package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
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
        ?: riwaya.reciters.firstOrNull()

    val listState = rememberLazyListState()
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

    // 💡 تلميح «اسمعها من المنبر» — يظهر بعد قراءة فعليّة ثم يختفي وحده.
    val content by vm.content.state.collectAsState()
    val recitation = remember(riwaya.id) {
        com.ali.menbaradkshk.data.QuranRecitationLink.forRiwaya(riwaya.id)
    }
    val firstVisible = listState.firstVisibleItemIndex
    val hizb = remember(loaded, surah.start, firstVisible) {
        com.ali.menbaradkshk.data.QuranRecitationLink.hizbAt(loaded, surah.start + firstVisible)
    }
    val hintLesson = remember(content.lessons, recitation, hizb) {
        recitation?.let {
            com.ali.menbaradkshk.data.QuranRecitationLink.lessonForHizb(
                content.lessons,
                it.subcategoryId,
                hizb,
            )
        }
    }
    var hintVisible by remember { mutableStateOf(false) }
    // شرط الظهور كلّه هنا: تلاوة معروفة لهذه الرواية، ودرس الحزب موجود فعلاً،
    // ولم يُوقِف المستخدم التلميح، ومضى وقتٌ كافٍ منذ آخر ظهور.
    LaunchedEffect(hintLesson, revision) {
        if (hintLesson == null || vm.store.quranHintMutedForever()) return@LaunchedEffect
        val since = System.currentTimeMillis() - vm.store.quranHintShownAtMs()
        if (since < HINT_INTERVAL_MS) return@LaunchedEffect
        // تأخير قبل الظهور: التلميح الذي يقفز في وجه القارئ فور فتحه الصفحة
        // مقاطعةٌ لا مساعدة. ننتظر حتى يستقرّ على القراءة فعلاً.
        kotlinx.coroutines.delay(HINT_DELAY_MS)
        vm.store.markQuranHintShown()
        hintVisible = true
        // ثمّ يختفي وحده ولو لم يُلمس — هذا شرط «الخفّة» الذي طُلب صراحةً.
        kotlinx.coroutines.delay(HINT_VISIBLE_MS)
        hintVisible = false
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        QuranReaderBar(
            surah = surah,
            riwayaName = riwaya.name,
            reciterName = reciter?.name.orEmpty(),
            fontSp = fontSp,
            playing = playingThisSurah && playback.playing,
            onFont = { vm.store.setQuranFontSp(it) },
            onReciter = { reciterSheet = true },
            onPlay = {
                if (playingThisSurah) {
                    vm.playback.toggle()
                } else {
                    reciter?.let { vm.playQuran(surah, it) }
                }
            },
        )
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
                    onPlayFromHere = { reciter?.let { vm.playQuran(surah, it, fromAyah = i + 1) } },
                )
            }
        }
    }

    // التلميح فوق النصّ لا داخله: إدراجه في القائمة كان يزحزح الآيات تحت
    // إصبع القارئ عند ظهوره واختفائه — وهذا أسوأ ما يفعله تلميح.
    androidx.compose.animation.AnimatedVisibility(
        visible = hintVisible && hintLesson != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = androidx.compose.animation.fadeIn() +
            androidx.compose.animation.slideInVertically { it },
        exit = androidx.compose.animation.fadeOut() +
            androidx.compose.animation.slideOutVertically { it },
    ) {
        ListenHintCard(
            reciterName = recitation?.reciter.orEmpty(),
            hizb = hizb,
            onListen = {
                hintVisible = false
                hintLesson?.let { lesson ->
                    val queue = content.lessons.filter { it.subcategoryId == lesson.subcategoryId }
                    vm.openPlayer(lesson, queue.ifEmpty { listOf(lesson) })
                }
            },
            onMute = {
                hintVisible = false
                vm.store.setQuranHintMutedForever(true)
            },
        )
    }
    }

    if (reciterSheet) {
        ModalBottomSheet(onDismissRequest = { reciterSheet = false }) {
            Text(
                "اختر القارئ — ${riwaya.name}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
            )
            riwaya.reciters.forEach { option ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.store.setQuranReciter(riwaya.id, option.id)
                        reciterSheet = false
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            tint = if (option.id == reciter?.id) Teal else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = { Text(option.name) },
                    supportingContent = if (option.perAyah) {
                        null
                    } else {
                        // صراحةً لا ضمناً: قارئ بملفّ سورة كاملة لا يمكن
                        // تمييز آياته، ومن حقّ المستخدم أن يعرف قبل الاختيار.
                        { Text("سورة كاملة — بلا تمييز آية بآية", style = MaterialTheme.typography.bodySmall) }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private const val BASMALA = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"

/// مهلة قبل ظهور التلميح داخل الجلسة — يقرأ المستخدم أوّلاً ثم يُدعى.
private const val HINT_DELAY_MS = 25_000L
/// مدّة بقائه على الشاشة ثم يختفي وحده.
private const val HINT_VISIBLE_MS = 9_000L
/// أقلّ فاصل بين ظهورين — «من حين لآخر» لا في كل مرّة.
private const val HINT_INTERVAL_MS = 6 * 60 * 60 * 1000L

/**
 * 💡 «تسمع هذا الحزب بصوت فلان؟» — بطاقة خفيفة أسفل الشاشة.
 *
 * تربط ما يقرؤه المستخدم الآن بتلاوةٍ مسجّلة **بالرواية نفسها** موجودة في
 * المنبر، وتفتح **الحزب المطابق بالضبط** لا رأس القسم — فالوصول خطوة واحدة.
 *
 * وهي خفيفة بثلاثة قيود مجتمعة: تتأخّر قبل الظهور، وتختفي وحدها ولو لم
 * تُلمَس، ولا تعود قبل ساعات. ومعها مخرج نهائيّ لمن لا يريدها أصلاً.
 */
@Composable
private fun ListenHintCard(
    reciterName: String,
    hizb: Int,
    onListen: () -> Unit,
    onMute: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Headphones, contentDescription = null, tint = Teal)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "اسمع الحزب $hizb بهذه الرواية",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (reciterName.isNotBlank()) {
                    Text(
                        "بصوت $reciterName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onListen) { Text("استمع") }
            IconButton(onClick = onMute) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "لا تُظهر هذا مجدداً",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/// شريط أدوات القارئ: تشغيل، اختيار القارئ، وتكبير/تصغير الخطّ.
@Composable
private fun QuranReaderBar(
    surah: Surah,
    riwayaName: String,
    reciterName: String,
    fontSp: Float,
    playing: Boolean,
    onFont: (Float) -> Unit,
    onReciter: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
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
                )
            }
        }
        // مكبّر الخطّ — الحرفان بحجمين مختلفين رمزٌ يُفهَم بلا قراءة.
        IconButton(
            onClick = { onFont(fontSp - QURAN_FONT_STEP) },
            enabled = fontSp > LocalStore.QURAN_FONT_MIN,
        ) {
            Text("أ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Teal)
        }
        IconButton(
            onClick = { onFont(fontSp + QURAN_FONT_STEP) },
            enabled = fontSp < LocalStore.QURAN_FONT_MAX,
        ) {
            Text("أ", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Teal)
        }
    }
}

private const val QURAN_FONT_STEP = 3f

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
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (active) SecondaryGold.copy(alpha = .14f) else Color.Transparent)
            .copyableOnLongPress(
                text = { "$text ﴿$number﴾" },
                onClick = onPlayFromHere,
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
