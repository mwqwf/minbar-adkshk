@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.menbaradkshk.data.Adhkar
import com.ali.menbaradkshk.data.AdhkarReminders
import com.ali.menbaradkshk.data.Dhikr
import com.ali.menbaradkshk.data.LocalStore

/**
 * تمييز «يوم» بالعربيّة — دالّة واحدة لكل مواضع السلاسل في التطبيق.
 *
 * ⚠️ كانت كل شاشة تجتهد بقاعدة: بطاقة الأذكار تكتب «يومان» للاثنين و«أيام»
 * لما سواه، ودرج الإعدادات «يوم» للواحد و«أيام» لما سواه — فتقرأ في شاشتين
 * صيغتين لعددٍ واحد، وكلتاهما تقول «١٥ أيام» والصواب «١٥ يومًا».
 */
fun daysLabel(n: Int): String = when {
    n == 1 -> "يوم"
    n == 2 -> "يومان"
    n in 3..10 -> "أيام"
    else -> "يومًا"
}

/// الصيغة الكاملة بالرقم عند الحاجة فقط: «يوم واحد»/«يومان»/«3 أيام»/
/// «15 يوماً» — إلحاق [daysLabel] بالرقم دائماً كان ينتج «2 يومان» و«1 يوم»،
/// والمثنّى والمفرد في العربيّة يستغنيان عن الرقم. القاعدة نفسها المستعملة
/// في بقيّة المعدودات (util/TextUtils.kt).
fun daysCountLabel(n: Int): String =
    com.ali.menbaradkshk.util.arabicCountLabel(n, "يوم واحد", "يومان", "أيام", "يوماً")

/// أيقونة ولون لكل قسم — تمييز بصري سريع بلا صور (خفّة).
private fun iconFor(id: String): ImageVector = when (id) {
    Adhkar.MORNING_ID -> Icons.Filled.WbSunny
    Adhkar.EVENING_ID -> Icons.Filled.WbTwilight
    "sleep" -> Icons.Filled.Bedtime
    "wake" -> Icons.Filled.LightMode
    "prayer" -> Icons.Filled.Mosque
    "tasbih" -> Icons.Filled.Favorite
    "salat_nabi" -> Icons.Filled.Favorite
    "distress" -> Icons.Filled.DarkMode
    "home_mosque" -> Icons.Filled.CleaningServices
    "food" -> Icons.Filled.Restaurant
    "travel" -> Icons.Filled.TravelExplore
    "weather" -> Icons.Filled.WaterDrop
    else -> Icons.Filled.Widgets
}

// ----------------------------------------------------------------------------
// 🕒 ذكر الوقت الحاضر
// ----------------------------------------------------------------------------

/// القسم الذي يوافق الساعة الحاضرة، ومعه سببٌ مكتوب يُقرأ على البطاقة.
private class TimelyAdhkar(val id: String, val title: String, val reason: String)

private fun currentHourOfDay(): Int =
    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

/**
 * 🕒 أيّ الأذكار يوافق هذه الساعة؟
 *
 * **حدود الأوقات ولماذا هي هكذا** (بتوقيت جهاز المستخدم، وهي حدودٌ واسعة
 * عمداً لأنّ التطبيق لا يحسب مواقيت الصلاة ولا يعرف موقع المستخدم):
 *
 * - **٤ ← ١٠:٥٩ صباحاً → أذكار الصباح**: وقتها من بعد الفجر إلى الضحى،
 *   والفجر لا يتقدّم الرابعة ولا يمتدّ الضحى بعد الحادية عشرة في هذه البلاد.
 * - **١٥ ← ١٩:٥٩ → أذكار المساء**: وقتها من بعد العصر إلى ما بعد المغرب،
 *   والعصر لا يدخل قبل الثالثة ولا يتأخّر المغرب عن الثامنة.
 * - **٢٠ ← ٣:٥٩ → أذكار النوم**: الليل كلّه، فمن نام مبكراً أو تأخّر يجدها.
 * - **١١ ← ١٤:٥٩ (الظهيرة)** لا بطاقة: ليس في هذا الوقت ذكرٌ مؤقّت، وعرضُ
 *   «أذكار الصباح» فيه خطأٌ صريح. غيابُ البطاقة أصدق من بطاقةٍ في غير وقتها،
 *   والصفحة تبقى كاملة تحتها.
 */
private fun timelyAdhkarFor(hour: Int): TimelyAdhkar? = when (hour) {
    in 4..10 -> TimelyAdhkar(Adhkar.MORNING_ID, "أذكار الصباح", "وقتها الآن — بعد الفجر إلى الضحى")
    in 15..19 -> TimelyAdhkar(Adhkar.EVENING_ID, "أذكار المساء", "وقتها الآن — بعد العصر إلى المغرب")
    in 20..23, in 0..3 -> TimelyAdhkar("sleep", "أذكار النوم", "قبل أن تنام")
    else -> null
}

/// بطاقة ذكر الوقت — أبرز ما في الصفحة: لونُ هوية وأيقونة كبيرة وسطرٌ يقول
/// **لماذا** ظهرت الآن، وهدف لمس يغطّي البطاقة كلّها.
@Composable
private fun TimelyAdhkarCard(
    title: String,
    reason: String,
    icon: ImageVector,
    done: Int,
    total: Int,
    onOpen: () -> Unit,
) {
    val accent = brandTintOnSurface(Teal)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier.clickable(onClick = onOpen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).background(accent.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (total > 0 && done >= total) {
                Icon(Icons.Filled.Check, "مكتمل", tint = accent)
            } else if (total > 0) {
                Text("$done/$total", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * صفحة «الأذكار» — قائمة الأقسام.
 *
 * تعمل كاملةً دون إنترنت: النصوص مُصرَّفة مع التطبيق ([Adhkar])، والتقدّم
 * والعدّادات في التخزين المحلّي. لا شبكة ولا حساب ولا أذونات إضافية.
 */
@Composable
fun AdhkarScreen(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    // السلسلة الحيّة لا المخزَّنة: المخزَّنة تبقى على قيمتها بعد انقطاع يوم،
    // فكانت البطاقة تُهنّئ على مداومة منقطعة.
    val streak = remember(revision) { vm.store.adhkarStreakLive() }

    val sections = remember {
        buildList {
            add(Triple(Adhkar.MORNING_ID, "أذكار الصباح", "تُقال بعد الفجر إلى طلوع الشمس"))
            add(Triple(Adhkar.EVENING_ID, "أذكار المساء", "تُقال بعد العصر إلى المغرب"))
            Adhkar.groups.forEach { add(Triple(it.id, it.title, it.subtitle)) }
        }
    }

    // 🕒 «الأذكار تعرف وقتها»: الصفحة كانت تعرض الأقسام كلّها على حال واحدة
    // في كل ساعة، والتطبيق يعرف الساعة. فيُقدَّم ذكرُ الوقت بطاقةً أولى —
    // **تقديمٌ لا حذف**: البقيّة تحته كما هي بلا تغيير.
    // الساعة تُقرأ كل خمس دقائق كي تتبدّل البطاقة والصفحة مفتوحة.
    var hour by remember { mutableIntStateOf(currentHourOfDay()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5 * 60_000L)
            hour = currentHourOfDay()
        }
    }
    val timely = remember(hour) { timelyAdhkarFor(hour) }

    LazyColumn(contentPadding = PaddingValues(vertical = 10.dp)) {
        if (timely != null) {
            item(key = "timely") {
                val items = remember(timely.id) { Adhkar.itemsFor(timely.id) }
                val totals = remember(timely.id) { items.map { it.repeat } }
                val done = remember(revision, timely.id) { vm.store.adhkarCompleted(timely.id, totals) }
                TimelyAdhkarCard(
                    title = timely.title,
                    reason = timely.reason,
                    icon = iconFor(timely.id),
                    done = done,
                    total = items.size,
                    onOpen = { vm.open(Route.AdhkarSection(timely.id)) },
                )
            }
        }

        if (streak > 1) {
            item(key = "streak") {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f),
                    ),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.LocalFireDepartment, null, tint = OrangeBrand)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            // «يومان» لا «2 يومان» (انظر daysCountLabel أعلاه).
                            "مداومتك على الأذكار: ${daysCountLabel(streak)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
        item(key = "reminders") {
            ListItem(
                modifier = Modifier.clickable { vm.open(Route.AdhkarReminders) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Filled.Notifications, null, tint = Gold) },
                headlineContent = { Text("تذكيرات الأذكار") },
                supportingContent = { Text("الصباح والمساء والنوم والاستيقاظ — بتوقيت جهازك") },
            )
        }

        items(sections, key = { it.first }) { (id, title, subtitle) ->
            val items = remember(id) { Adhkar.itemsFor(id) }
            val totals = remember(id) { items.map { it.repeat } }
            val done = remember(revision, id) { vm.store.adhkarCompleted(id, totals) }
            val progress = if (items.isEmpty()) 0f else done / items.size.toFloat()

            Card(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.clickable { vm.open(Route.AdhkarSection(id)) }) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Box(
                                Modifier.size(42.dp).background(Teal.copy(alpha = .12f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(iconFor(id), null, tint = Teal) }
                        },
                        headlineContent = {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = { Text(subtitle) },
                        trailingContent = {
                            if (done >= items.size && items.isNotEmpty()) {
                                Icon(Icons.Filled.Check, "مكتمل", tint = Teal)
                            } else {
                                Text(
                                    "$done/${items.size}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    if (progress > 0f) {
                        // المُغلِّف المشترك لا المؤشّر الخام: الخام يرسم فجوة
                        // المقبض ونقطة النهاية في M3 الجديد، فتفترق شرائط
                        // الأذكار شكلاً عن بقيّة شرائط التطبيق بلا سبب.
                        ClassicLinearProgress(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Teal,
                        )
                    }
                }
            }
        }

        item(key = "note") {
            Text(
                "الأذكار كلّها ثابتة في الصحيحين أو صحّحها أهل العلم، ومذكورٌ تخريج كلّ ذكر معه.",
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * شاشة قسم واحد: بطاقة لكل ذكر، والنقر على البطاقة يُنقص العدّاد — وهو النمط
 * الذي استقرّت عليه تطبيقات الأذكار الجيّدة (مساحة نقر كبيرة بلا زرّ صغير).
 * عند اكتمال الذكر يُطوى تلقائياً إلى حالة «تمّ» وينتقل التركيز لما بعده.
 */
@Composable
fun AdhkarSectionScreen(vm: AppViewModel, sectionId: String) {
    val revision by vm.store.revision.collectAsState()
    val items = remember(sectionId) { Adhkar.itemsFor(sectionId) }
    val view = LocalView.current
    val context = LocalContext.current

    val completed = remember(revision, sectionId) {
        vm.store.adhkarCompleted(sectionId, items.map { it.repeat })
    }
    val allDone = items.isNotEmpty() && completed >= items.size
    val fontSp = remember(revision) { vm.store.adhkarFontSp() }
    val bold = remember(revision) { vm.store.adhkarBold() }
    // الذكر المفتوح مكبَّراً بملء الشاشة (فهرسه في القائمة).
    // ⚠️ محفوظ لا مُتذكَّر فقط: النشاط بلا `configChanges`، فتدوير الشاشة أو
    // تغيير حجم خطّ النظام كان يُغلق الشاشة المكبَّرة على من يقرأ فيها.
    var zoomed by rememberSaveable { mutableStateOf<Int?>(null) }
    // ⛔ «إعادة من البداية» تمحو عدّ اليوم بلا رجعة، وقاعدة التطبيق أن لا حذف
    // بنقرة واحدة — فيسبقه تأكيد كما في حذف القائمة وحذف البيانات.
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    /// عدُّ ذكرٍ بفهرسه — يستعمله الصفّ العاديّ والشاشة المكبَّرة معاً، فلا
    /// يفترق سلوك العدّاد بينهما ولا يُنسى تحديث السلسلة في أحدهما.
    fun count(index: Int) {
        val current = vm.store.adhkarDone(sectionId, index) ?: 0L
        val target = items[index].repeat.toLong()
        if (current >= target) return
        val next = current + 1
        vm.store.setAdhkarDone(sectionId, index, next)
        runCatching {
            view.performHapticFeedback(
                if (next >= target) {
                    android.view.HapticFeedbackConstants.LONG_PRESS
                } else {
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP
                },
            )
        }
        if (next >= target) {
            val all = vm.store.adhkarCompleted(sectionId, items.map { it.repeat })
            if (all >= items.size) vm.store.noteAdhkarCompletion()
        }
    }

    zoomed?.let { index ->
        DhikrZoomDialog(
            dhikr = items[index],
            done = remember(revision, index) { vm.store.adhkarDone(sectionId, index) ?: 0L },
            // ⚠️ مقياس مستقلّ عن خطّ القائمة. كان يشاركه مع أرضيّة ٦٠، فكان
            // التصغير يُبتلع بالأرضيّة ولا يتحرّك شيء («تجمُّد»)، وكان
            // التكبير يُضخّم القائمة كلّها بعد الخروج بلا أن يطلب أحد ذلك.
            fontSp = remember(revision) { vm.store.adhkarZoomFontSp() },
            currentFont = { vm.store.adhkarZoomFontSp() },
            bold = bold,
            onCount = { count(index) },
            onFont = { vm.store.setAdhkarZoomFontSp(it) },
            onToggleBold = { vm.store.setAdhkarBold(!vm.store.adhkarBold()) },
            onDismiss = { zoomed = null },
        )
    }

    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("إعادة العدّ") },
            text = { Text("إعادة عدّ هذا القسم من الصفر؟") },
            confirmButton = {
                TextButton(onClick = {
                    vm.store.resetAdhkarSection(sectionId, items.size)
                    confirmReset = false
                }) { Text("إعادة") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("إلغاء") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "أتممتَ $completed من ${items.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            ClassicLinearProgress(
                progress = if (items.isEmpty()) 0f else completed / items.size.toFloat(),
                modifier = Modifier.weight(1f).height(6.dp),
                color = Teal,
            )
            IconButton(onClick = { confirmReset = true }) {
                Icon(Icons.Filled.Refresh, "إعادة من البداية", tint = Teal)
            }
        }

        // 🔎 مكبّر خطّ الأذكار — في متناول اليد داخل الشاشة نفسها لا مدفوناً
        // في الإعدادات: مَن يحتاجه (كبير السنّ) هو أقلّ الناس بحثاً في
        // القوائم. زرّان كبيران بـ«−» و«+» ورقمُ الحجم بينهما — رموز مفهومة
        // بلا قراءة، وهذا مقصود: كثير من المستخدمين لا يقرأ العربية جيّداً.
        AdhkarFontControls(vm, fontSp, bold)

        if (allDone) {
            Text(
                "تقبّل الله منك 🤍",
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                textAlign = TextAlign.Center,
                color = Teal,
                fontWeight = FontWeight.Bold,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                // 🔍 طريقة تكبير ثانية: الإصبعان. الأزرار تكفي من يعرفها،
                // والإيماءة تخدم من يجرّبها بلا تعلّم — وهي لا تبتلع التمرير
                // (انظر [pinchFontSize])، فلا نخسر شيئاً بإضافتها.
                .pinchFontSize(
                    current = { vm.store.adhkarFontSp() },
                    onChange = { vm.store.setAdhkarFontSp(it) },
                ),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items.size, key = { it }) { index ->
                // ⚠️ قراءة `revision` **داخل** عنصر القائمة شرطٌ لا تحسين:
                // محتوى عنصر LazyColumn نطاق تركيب مستقلّ، فلو قُرئ العدّاد
                // من المخزن بلا قراءة حالة هنا لبقيت البطاقة كما هي بعد كل
                // نقرة — يزيد العدّ في التخزين ولا يتحرّك شيء على الشاشة.
                val done = remember(revision, index) {
                    vm.store.adhkarDone(sectionId, index) ?: 0L
                }
                DhikrCard(
                    dhikr = items[index],
                    done = done,
                    fontSp = fontSp,
                    bold = bold,
                    onCopy = { copyToClipboard(context, dhikrText(items[index])) },
                    onTap = { count(index) },
                    onZoom = { zoomed = index },
                    onShare = {
                        runCatching {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    dhikrText(items[index]) + "\n— من تطبيق منبر ادكصهك",
                                )
                            context.startActivity(
                                android.content.Intent.createChooser(send, "مشاركة الذكر"),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * 🔎 شريط تكبير خطّ الأذكار — يصل إلى **أقصى حجم ممكن** (١٢٠ نقطة).
 *
 * ثلاثة أزرار فقط: تصغير، الحجم الحالي (نقرة تُعيده إلى الافتراضي)، تكبير.
 * وإبقاء الإصبع على زرّ التكبير يواصل الخطوات متسارعةً حتى يبلغ مقاسه، كي لا
 * يضطرّ كبير السنّ (وهو المقصود بالميزة) إلى عشرات الضغطات.
 *
 * والخطوة **نسبيّة لا ثابتة**: [FONT_STEP_RATIO] من الحجم الحالي. الخطوة
 * الثابتة تكون قفزةً فجّة عند ١٦ نقطة وزحفاً لا يُحسّ عند ١٠٠ — والنسبة تُبقي
 * الإحساس بالفرق واحداً على المدى كلّه.
 */
@Composable
private fun AdhkarFontControls(vm: AppViewModel, current: Float, bold: Boolean) {
    // ⚠️ لا دالّة خطوة هنا: الزرّان يحسبان خطوتهما من **آخر قيمة في المخزن**
    // لا من القيمة التي رُكِّبت بها الدالّة، وإلّا تجمّد التكبير عند خطوة
    // واحدة أثناء الضغط المستمرّ.
    //
    // والصفّ نفسه [ReadingFontRow] المشترك مع المصحف: شاشتا قراءة تضبطان
    // الشيء نفسه، فاختلاف شكلهما كان تعليماً للمستخدم مرّتين بلا فائدة.
    ReadingFontRow(
        current = { vm.store.adhkarFontSp() },
        value = current,
        min = LocalStore.ADHKAR_FONT_MIN,
        max = LocalStore.ADHKAR_FONT_MAX,
        default = LocalStore.ADHKAR_FONT_DEFAULT,
        onChange = { vm.store.setAdhkarFontSp(it) },
        bold = bold,
        onBold = { vm.store.setAdhkarBold(!bold) },
    )
}

/**
 * 🔍 «ذكرٌ واحد بملء الشاشة» — تكبير كل ذكر على حدة إلى أقصى حجم ممكن.
 *
 * **لماذا شاشة مستقلّة لا تكبير للقائمة كلّها؟** لأنّ الذاكر يردّد ذكراً
 * واحداً في المرّة. تكبير القائمة يجعل التمرير عملاً متواصلاً، أمّا هنا
 * فالذكر وحده أمام العين، والشاشة كلّها زرُّ عدٍّ — يضغط في أيّ مكان فيتقدّم
 * العدّاد بلا أن يبحث عن زرّ صغير. وهذا هو المقصود بالميزة أصلاً: من لا
 * يبصر جيّداً لا يجب أن يُطالَب بإصابة هدفٍ صغير.
 *
 * الحجم يبدأ كبيراً بلا ضبط ([LocalStore.ZOOM_FONT_DEFAULT] = ٩٠ نقطة)، ولا
 * ينزل عن ٤٠ ولا يتجاوز ١٦٠ — وهو محفوظ فيعود كما تركه صاحبه.
 */
@Composable
private fun DhikrZoomDialog(
    dhikr: Dhikr,
    done: Long,
    fontSp: Float,
    /// القيمة اللحظيّة من المخزن — حلقة التكرار أثناء الضغط تحتاج آخر حجم
    /// لا الحجم الذي رُكِّبت به الدالة، وإلا تجمّد التكبير عند خطوة واحدة.
    currentFont: () -> Float,
    bold: Boolean,
    onCount: () -> Unit,
    onFont: (Float) -> Unit,
    onToggleBold: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = dhikr.repeat.toLong()
    val finished = done >= target
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "إغلاق", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = onToggleBold) {
                        Text(
                            "عريض",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                            color = if (bold) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // «أ−»/«أ+» لا «−»/«+»: الرمز وحده يحتمل أن يكون عدّاً أو
                    // صوتاً، والحرف يقطع بأنّ المقصود حجم الخطّ.
                    FontStepButton(
                        label = "أ−",
                        enabled = fontSp > LocalStore.ZOOM_FONT_MIN,
                        onStep = { onFont(currentFont() - (currentFont() * FONT_STEP_RATIO).coerceAtLeast(2f)) },
                        // حرفان لا رمزٌ واحد: الحجم أصغر كي يسع الدائرة.
                        fontSize = 21,
                    )
                    Spacer(Modifier.width(8.dp))
                    FontStepButton(
                        label = "أ+",
                        enabled = fontSp < LocalStore.ZOOM_FONT_MAX,
                        onStep = { onFont(currentFont() + (currentFont() * FONT_STEP_RATIO).coerceAtLeast(2f)) },
                        fontSize = 21,
                    )
                }
                // الشاشة كلّها منطقة عدّ: لا هدف صغير يُصاب.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(enabled = !finished, onClick = onCount)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        // الطريقة الثالثة هنا أيضاً: الإصبعان يكبّران الذكر
                        // مباشرةً بلا مغادرة الشاشة ولا بحث عن زرّ.
                        .pinchFontSize(current = currentFont, onChange = onFont)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        dhikr.text,
                        fontSize = fontSp.sp,
                        lineHeight = (fontSp * 1.7f).sp,
                        // ⭐ الوزن العريض شقيقُ الحجم لا زينةً بعده: التكبير
                        // يمدّ الحرف والوزن يُثخّنه، وبالثخانة يفترق عن
                        // الخلفيّة في العين الضعيفة.
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CounterBadge(done = done, target = target, finished = finished)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (finished) "تمّ — تقبّل الله" else "اضغط في أيّ مكان للعدّ  ($done من ${dhikr.repeat})",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (finished) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// زرّ الخطوة مشترك بين الأذكار والمصحف — انظر [FontStepButton] في ملفّه.

/// صيغة نصّ الذكر عند إخراجه من التطبيق — **واحدة** للنسخ بالزرّ والنسخ
/// بالضغط المطوّل والمشاركة. ⚠️ كان الضغط المطوّل يخرج بالنصّ عارياً بلا
/// تخريج، فيجد الناسخ التخريج مرّةً ويفقده أخرى بلا أن يدري لماذا.
private fun dhikrText(d: Dhikr) = d.text + "\n\n" + d.source

@Composable
private fun DhikrCard(
    dhikr: Dhikr,
    done: Long,
    fontSp: Float,
    bold: Boolean,
    onTap: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onZoom: () -> Unit,
) {
    val target = dhikr.repeat.toLong()
    val finished = done >= target
    val alpha by animateFloatAsState(if (finished) 0.55f else 1f, label = "dhikr-alpha")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (finished) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                dhikr.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSp.sp,
                    // ارتفاع السطر يتبع الحجم نسبياً (١.٧٥×): ثابتاً كان
                    // النصّ المكبَّر يتراكب على نفسه ويصير غير مقروء أصلاً.
                    lineHeight = (fontSp * 1.75f).sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha }
                    // ضغطة مطوّلة على نصّ الذكر تنسخه — بلا زرّ إضافي، وبنفس
                    // صيغة زرّ النسخ: النسخ واحد وإن اختلف طريقه.
                    .copyableOnLongPress(text = { dhikrText(dhikr) }, onClick = onTap),
                textAlign = TextAlign.Justify,
            )
            if (dhikr.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    dhikr.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Teal,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dhikr.source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // الذكر المكرَّر: عدّاده الخاصّ به ظاهر بالأرقام أيضاً
                    // (١٧ من ١٠٠) لا بالمتبقّي وحده — أوضح للمداومة الطويلة.
                    if (dhikr.repeat > 1) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$done من ${dhikr.repeat}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (finished) Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // 🔍 تكبير هذا الذكر وحده بملء الشاشة — الميزة التي يحتاجها
                // كبير السنّ: لا يريد تكبير القائمة كلّها، بل أن يرى الذكر
                // الذي يردّده الآن بأكبر خطّ ممكن ويعدّه وهو مكبَّر.
                IconButton(onClick = onZoom) {
                    Icon(
                        Icons.Filled.ZoomIn,
                        "تكبير هذا الذكر",
                        modifier = Modifier.size(22.dp),
                        tint = Teal,
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        "نسخ الذكر",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        "مشاركة",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CounterBadge(done = done, target = target, finished = finished)
            }
        }
    }
}

/// عدّاد دائري: يعرض المتبقّي ما دام الذكر مكرَّراً، و✓ عند إتمامه.
///
/// ⚠️ **كلمة «باقٍ» تحت الرقم**: كان السطر يقول «١٧ من ١٠٠» والشارة في الصفّ
/// نفسه تقول «٨٣» — رقمان متناقضان ظاهراً لشيء واحد أمام قارئٍ لا يعنيه
/// التقنية. وأُبقي المتبقّي (لا المُنجَز) لأنّه المعنى المفيد أثناء التسبيح
/// («كم بقي؟»)، ولأنّ الذكر غير المكرَّر لا سطر له فتبدأ شارته بـ«١» لا «٠».
/// فكلمةٌ واحدة تُبيّنه أبسطُ للقارئ من تكرار الرقم نفسه مرّتين.
@Composable
private fun CounterBadge(done: Long, target: Long, finished: Boolean) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(if (finished) Teal else Teal.copy(alpha = .14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (finished) {
            Icon(Icons.Filled.Check, "تمّ", tint = Color.White)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${target - done}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Teal,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "باقٍ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Teal.copy(alpha = .8f),
                )
            }
        }
    }
}


/**
 * شاشة تذكيرات الأذكار: أربعة تذكيرات محلّية بحتة بتوقيت الجهاز، كلٌّ منها
 * يُفعَّل ويُوقَّت وحده. لا خادم ولا شبكة — جدولة WorkManager يومية واحدة لكلّ
 * تذكير مفعَّل، فلا أثر يُذكر على البطارية.
 */
@Composable
fun AdhkarRemindersScreen(vm: AppViewModel, requestNotifications: () -> Unit) {
    val revision by vm.store.revision.collectAsState()
    // العناوين والأيقونات هنا، أمّا **المواعيد** فمن [AdhkarReminders] وحده —
    // المجدوِل يقرأ منه أيضاً، فلا يفترق المعروض عمّا يُجدوَل.
    val kinds = listOf(
        Triple("morning", "تذكير أذكار الصباح", Icons.Filled.WbSunny),
        Triple("evening", "تذكير أذكار المساء", Icons.Filled.WbTwilight),
        Triple("sleep", "تذكير أذكار النوم", Icons.Filled.Bedtime),
        Triple("wake", "تذكير أذكار الاستيقاظ", Icons.Filled.LightMode),
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "تذكيرات لطيفة بتوقيت جهازك، تعمل دون إنترنت. يمكنك إيقاف أيٍّ منها متى شئت.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        kinds.forEach { (kind, title, icon) ->
            val default = AdhkarReminders.defaultFor(kind)
            val enabled = remember(revision, kind) { vm.store.adhkarReminder(kind) }
            val hour = remember(revision, kind) {
                vm.store.adhkarReminderHour(kind, default.first)
            }
            val minute = remember(revision, kind) {
                vm.store.adhkarReminderMinute(kind, default.second)
            }
            var picking by rememberSaveable(kind) { mutableIntStateOf(0) }

            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(icon, null, tint = Teal) },
                headlineContent = { Text(title) },
                supportingContent = {
                    Text(
                        if (enabled) "يوميًا في ${timeLabel(hour, minute)}" else "موقوف",
                    )
                },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        // ⚠️ الإذن يُطلب عند التفعيل كما في مفتاح الإشعارات
                        // بالدرج: تذكيرٌ «مفعَّل» بلا إذنٍ يُجدوَل ثم يُبتلع
                        // صامتاً عند العرض، فيظنّ المستخدم أنّ الميزة معطوبة.
                        onCheckedChange = {
                            if (it) requestNotifications()
                            vm.setAdhkarReminder(kind, it)
                        },
                    )
                },
                modifier = Modifier.clickable(enabled = enabled) { picking = 1 },
            )
            if (picking == 1) {
                val state = androidx.compose.material3.rememberTimePickerState(
                    initialHour = hour,
                    initialMinute = minute,
                )
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { picking = 0 },
                    title = { Text(title) },
                    text = { androidx.compose.material3.TimePicker(state = state) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            vm.setAdhkarReminderTime(kind, state.hour, state.minute)
                            picking = 0
                        }) { Text("حفظ") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { picking = 0 }) {
                            Text("إلغاء")
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun timeLabel(hour: Int, minute: Int): String {
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val period = if (hour < 12) "ص" else "م"
    return "%d:%02d %s".format(java.util.Locale.ROOT, h12, minute, period)
}
