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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ali.menbaradkshk.data.Dhikr
import com.ali.menbaradkshk.data.LocalStore

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

/**
 * صفحة «الأذكار» — قائمة الأقسام.
 *
 * تعمل كاملةً دون إنترنت: النصوص مُصرَّفة مع التطبيق ([Adhkar])، والتقدّم
 * والعدّادات في التخزين المحلّي. لا شبكة ولا حساب ولا أذونات إضافية.
 */
@Composable
fun AdhkarScreen(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val streak = remember(revision) { vm.store.adhkarStreak() }

    val sections = remember {
        buildList {
            add(Triple(Adhkar.MORNING_ID, "أذكار الصباح", "تُقال بعد الفجر إلى طلوع الشمس"))
            add(Triple(Adhkar.EVENING_ID, "أذكار المساء", "تُقال بعد العصر إلى المغرب"))
            Adhkar.groups.forEach { add(Triple(it.id, it.title, it.subtitle)) }
        }
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 10.dp)) {
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
                            "مداومتك على الأذكار: $streak ${if (streak == 2) "يومان" else "أيام"}",
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
                        LinearProgressIndicator(
                            progress = { progress },
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
            LinearProgressIndicator(
                progress = {
                    if (items.isEmpty()) 0f else completed / items.size.toFloat()
                },
                modifier = Modifier.weight(1f).height(6.dp),
                color = Teal,
            )
            IconButton(onClick = {
                vm.store.resetAdhkarSection(sectionId, items.size)
            }) {
                Icon(Icons.Filled.Refresh, "إعادة من البداية", tint = Teal)
            }
        }

        // 🔎 مكبّر خطّ الأذكار — في متناول اليد داخل الشاشة نفسها لا مدفوناً
        // في الإعدادات: مَن يحتاجه (كبير السنّ) هو أقلّ الناس بحثاً في
        // القوائم. زرّان كبيران بحرفَي «أ» بحجمين مختلفين — رمز مفهوم بلا
        // قراءة، وهذا مقصود: كثير من المستخدمين لا يقرأ العربية جيّداً.
        AdhkarFontControls(vm, fontSp)

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
            modifier = Modifier.weight(1f),
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
                    onCopy = {
                        copyToClipboard(
                            context,
                            items[index].text + "\n\n" + items[index].source,
                        )
                    },
                    onTap = {
                        val current = vm.store.adhkarDone(sectionId, index) ?: 0L
                        val target = items[index].repeat.toLong()
                        if (current >= target) return@DhikrCard
                        val next = current + 1
                        vm.store.setAdhkarDone(sectionId, index, next)
                        // اهتزازة خفيفة عند كل عدّة، وأوضح عند إتمام الذكر.
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
                    },
                    onShare = {
                        runCatching {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    items[index].text + "\n\n" + items[index].source +
                                        "\n— من تطبيق منبر ادكصهك",
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
 * 🔎 شريط تكبير خطّ الأذكار.
 *
 * ثلاثة أزرار فقط: تصغير، الحجم الحالي (نقرة تُعيده إلى الافتراضي)، تكبير.
 * الخطوة 3sp — ناعمة بما يكفي ليصل المستخدم إلى مقاسه بالضبط، وكبيرة بما
 * يكفي ليشعر بالفرق من أوّل ضغطة فلا يظنّ الزرّ معطّلاً.
 */
@Composable
private fun AdhkarFontControls(vm: AppViewModel, current: Float) {
    val atMin = current <= LocalStore.ADHKAR_FONT_MIN
    val atMax = current >= LocalStore.ADHKAR_FONT_MAX
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(
            onClick = { vm.store.setAdhkarFontSp(current - FONT_STEP_SP) },
            enabled = !atMin,
        ) {
            Text(
                "أ",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (atMin) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f) else Teal,
            )
        }
        // النقر على الرقم يُرجع الحجم الافتراضي — مخرج آمن لمن كبّر أكثر
        // ممّا ينبغي ولا يعرف كيف يعود.
        TextButton(onClick = { vm.store.setAdhkarFontSp(LocalStore.ADHKAR_FONT_DEFAULT) }) {
            Text(
                "${current.toInt()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { vm.store.setAdhkarFontSp(current + FONT_STEP_SP) },
            enabled = !atMax,
        ) {
            Text(
                "أ",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (atMax) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f) else Teal,
            )
        }
    }
}

private const val FONT_STEP_SP = 3f

@Composable
private fun DhikrCard(
    dhikr: Dhikr,
    done: Long,
    fontSp: Float,
    onTap: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
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
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha }
                    // ضغطة مطوّلة على نصّ الذكر تنسخه — بلا زرّ إضافي.
                    .copyableOnLongPress(text = { dhikr.text }, onClick = onTap),
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
            Text(
                "${target - done}",
                style = MaterialTheme.typography.titleMedium,
                color = Teal,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}


/**
 * شاشة تذكيرات الأذكار: أربعة تذكيرات محلّية بحتة بتوقيت الجهاز، كلٌّ منها
 * يُفعَّل ويُوقَّت وحده. لا خادم ولا شبكة — جدولة WorkManager يومية واحدة لكلّ
 * تذكير مفعَّل، فلا أثر يُذكر على البطارية.
 */
@Composable
fun AdhkarRemindersScreen(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val kinds = listOf(
        Quad("morning", "تذكير أذكار الصباح", Icons.Filled.WbSunny, 6 to 30),
        Quad("evening", "تذكير أذكار المساء", Icons.Filled.WbTwilight, 17 to 30),
        Quad("sleep", "تذكير أذكار النوم", Icons.Filled.Bedtime, 22 to 0),
        Quad("wake", "تذكير أذكار الاستيقاظ", Icons.Filled.LightMode, 5 to 30),
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "تذكيرات لطيفة بتوقيت جهازك، تعمل دون إنترنت. يمكنك إيقاف أيٍّ منها متى شئت.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        kinds.forEach { (kind, title, icon, default) ->
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
                        onCheckedChange = { vm.setAdhkarReminder(kind, it) },
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

private data class Quad(
    val kind: String,
    val title: String,
    val icon: ImageVector,
    val default: Pair<Int, Int>,
)

private fun timeLabel(hour: Int, minute: Int): String {
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val period = if (hour < 12) "ص" else "م"
    return "%d:%02d %s".format(java.util.Locale.ROOT, h12, minute, period)
}
