@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.ali.menbaradkshk.util.lessonsCountLabel

/// «الإعدادات» — درج جانبي حقيقي (بنمط تويتر/تلجرام) يُسحب من الجانب أو
/// يُفتح بزرّه المخصّص. البنود المتشابهة مجمَّعة في مواضيع قليلة، وكل موضوع
/// يفتح ورقة فرعية تضم بنوده كاملة — نفس الوظائف حرفياً بأزرار أقل.
@Composable
fun SettingsDrawerContent(vm: AppViewModel, requestNotifications: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()

    val streak = remember(revision) { vm.store.streakDays() }
    val notifOn = remember(revision) { vm.store.notificationsEnabled() }
    // حال النظام لا حال المفتاح: من رفض الإذن (أو رُفض له تلقائياً بعد
    // رفضين على أندرويد 13+) كان يقرأ «تصلك إشعارات المحتوى الجديد» أبداً
    // ولا يصله شيء، بلا سبب ظاهر ولا مدخل إلى إعدادات النظام.
    // `areNotificationsEnabled` يغطّي الإذن والحجب اليدوي معاً.
    val notifBlocked = remember(revision) {
        !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val themeMode = remember(revision) { vm.store.themeMode() }
    val fontScale = remember(revision) { vm.store.fontScale() }
    val autoDownload = remember(revision) { vm.store.autoDownloadEnabled() }
    val autoTarget = remember(revision) { vm.store.autoDownloadTarget() ?: "recent" }
    val wifiOnly = remember(revision) { vm.store.autoDownloadWifiOnly() }
    val continueReminder = remember(revision) { vm.store.continueReminderEnabled() }
    val wardEnabled = remember(revision) { vm.store.wardEnabled() }
    val wardHour = remember(revision) { vm.store.wardHour() }
    val wardMinute = remember(revision) { vm.store.wardMinute() }
    val quranWardPages = remember(revision) { vm.store.quranWardPages() }
    val quranWardHour = remember(revision) { vm.store.quranWardHour() }
    val quranWardMinute = remember(revision) { vm.store.quranWardMinute() }
    // عدد المتابَعات يُكتب في وصف هدف التنزيل «الأقسام التي أتابعها» كي لا
    // يبدو الخيار معطوباً لمن لا يتابع شيئاً.
    val followedCount = remember(revision) { vm.store.followedSubcategories().size }
    val weeklyGoal = remember(revision) { vm.store.weeklyGoalMinutes() }
    val downloadsMap = remember(revision) { vm.store.downloads() }
    val downloadsCount = downloadsMap.size
    // حجم التنزيلات = مسح للقرص: يُحسب على خيط الإدخال/الإخراج ومفتاحه خريطة
    // التنزيلات نفسها، فلا يتكرّر مع نبضات `revision` أثناء التشغيل.
    val downloadsBytes by produceState(0L, downloadsMap) {
        value = withContext(Dispatchers.IO) {
            downloadsMap.values.sumOf { java.io.File(it).length().coerceAtLeast(0L) }
        }
    }
    val favorites = remember(revision, content.lessons) { vm.content.favorites() }
    val continueList = remember(revision, content.lessons) { vm.content.continueListening() }

    // الموضوع المفتوح حالياً في ورقة فرعية: downloads / notifications / data.
    var group by remember { mutableStateOf<String?>(null) }
    var appearanceDialog by remember { mutableStateOf(false) }
    var wardTimeDialog by remember { mutableStateOf(false) }
    var quranWardSheet by remember { mutableStateOf(false) }
    var quranWardTimeDialog by remember { mutableStateOf(false) }
    var autoTargetSheet by remember { mutableStateOf(false) }
    var goalSheet by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var sectionSheet by remember { mutableStateOf(false) }

    // نسخة احتياطية محلية عبر منتقي ملفات النظام (بلا أي رفع للسحابة).
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.store.exportBackup().toByteArray())
                }
            }.onSuccess { vm.showMessage("حُفظت نسخة بياناتك.") }
                .onFailure { vm.showMessage("تعذّر حفظ النسخة.") }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }.orEmpty()
                vm.store.importBackup(text)
            }.onSuccess { restored ->
                if (restored < 0) vm.showMessage("الملف ليس نسخة احتياطية صالحة للتطبيق.")
                else {
                    vm.content.refreshPersonalization()
                    // المتابعات تعود بالاستعادة، أمّا اشتراكات مواضيعها فلا:
                    // كان المستخدم يرى نفسه «متابِعاً» ولا يصله شيء منها.
                    vm.resubscribeFollowedTopics()
                    vm.showMessage("استُعيدت بياناتك ($restored عنصراً).")
                }
            }.onFailure { vm.showMessage("تعذّر قراءة الملف.") }
        }
    }

    fun wardTimeLabel(): String = clockLabel(wardHour, wardMinute)

    val themeLabel = when (themeMode) {
        "light" -> "فاتح"
        "dark" -> "داكن"
        else -> "النظام"
    }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.86f),
        windowInsets = WindowInsets.statusBars,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "الإعدادات",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            if (streak > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AssistChip(
                        onClick = {},
                        leadingIcon = { Icon(Icons.Filled.LocalFireDepartment, null, tint = OrangeBrand) },
                        label = { Text("سلسلة استماع: $streak ${daysLabel(streak)}") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // المستوى الأول: موضوعات قليلة مجمَّعة — التفاصيل في أوراق فرعية.
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 32.dp),
            ) {
                SettingsTile(
                    icon = Icons.Filled.BrightnessMedium,
                    title = "المظهر وحجم النص",
                    subtitle = "$themeLabel — ${(fontScale * 100).toInt()}%",
                    onClick = { appearanceDialog = true },
                )
                SettingsTile(
                    icon = Icons.Filled.DownloadForOffline,
                    title = "التنزيلات",
                    subtitle = "$downloadsCount درساً — ${formatStorage(downloadsBytes)}" +
                        if (autoDownload) " · التلقائي مفعَّل" else "",
                    onClick = { group = "downloads" },
                )
                SettingsTile(
                    icon = if (notifOn && !notifBlocked) Icons.Filled.NotificationsActive
                    else Icons.Filled.NotificationsOff,
                    title = "الإشعارات والوِرد اليومي",
                    subtitle = buildString {
                        append(
                            when {
                                notifBlocked -> "محجوبة من النظام"
                                notifOn -> "الإشعارات تعمل"
                                else -> "الإشعارات موقوفة"
                            },
                        )
                        if (wardEnabled) append(" · الوِرد في ${wardTimeLabel()}")
                    },
                    onClick = { group = "notifications" },
                )
                SettingsTile(
                    icon = Icons.Filled.FolderShared,
                    title = "بياناتي",
                    subtitle = "الهدف الأسبوعي، النسخ الاحتياطي والاستعادة، الحذف",
                    onClick = { group = "data" },
                )
                SettingsTile(
                    icon = Icons.Filled.FactCheck,
                    title = "مساهماتي",
                    subtitle = "تابع قرار المشرفين وسبب النتيجة",
                    onClick = {
                        vm.closeSettings()
                        vm.open(Route.MySubmissions)
                    },
                )
                SettingsTile(
                    icon = Icons.Filled.Info,
                    title = "حول التطبيق",
                    subtitle = "التعريف، الوقف الخيري، نسخة الويب، المصدر المفتوح، الخصوصية",
                    onClick = {
                        vm.closeSettings()
                        vm.open(Route.About)
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "منبر ادكصهك — دروس صوتية",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- ورقة «التنزيلات» ----
    if (group == "downloads") {
        ModalBottomSheet(onDismissRequest = { group = null }) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp, end = 8.dp, bottom = 32.dp,
                ),
            ) {
                item(key = "title") { GroupTitle("التنزيلات") }
                item(key = "autodl") {
                    SettingsTile(
                        icon = Icons.Filled.DownloadForOffline,
                        title = "التنزيل التلقائي",
                        // محايدٌ عن الهدف: صارت الأهداف ثلاثة، والسطر التالي
                        // هو الذي يقول أيَّها اختار.
                        subtitle = "يحفظ الدروس تلقائياً للاستماع دون إنترنت",
                        trailing = {
                            Switch(
                                checked = autoDownload,
                                onCheckedChange = { enabled ->
                                    vm.setAutoDownloadEnabled(enabled)
                                    if (enabled) vm.setAutoDownloadTarget("recent")
                                },
                            )
                        },
                    )
                }
                if (autoDownload) {
                    item(key = "autodl-target") {
                        SettingsTile(
                            icon = Icons.Filled.DownloadDone,
                            title = "ما الذي يُنزّل تلقائياً؟",
                            trailing = {
                                Text(
                                    autoTargetLabel(autoTarget),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            // ⚠️ ورقةٌ لا تبديلٌ بالنقر: صارت الأهداف ثلاثة،
                            // والتدوير بينها يُخفي الخيارَين اللذين لا يظهران.
                            onClick = { autoTargetSheet = true },
                        )
                    }
                    item(key = "autodl-wifi") {
                        SettingsTile(
                            icon = Icons.Filled.Wifi,
                            title = "عبر Wi‑Fi فقط",
                            trailing = {
                                Switch(checked = wifiOnly, onCheckedChange = vm::setAutoDownloadWifiOnly)
                            },
                        )
                    }
                }
                item(key = "dl-favs") {
                    SettingsTile(
                        icon = Icons.Filled.Favorite,
                        title = "تحميل المفضّلة كلها",
                        subtitle = lessonsCountLabel(favorites.size),
                        onClick = {
                            if (favorites.isEmpty()) vm.showMessage("لا توجد دروس في المفضّلة بعد.")
                            else vm.requestBulkDownload("المفضّلة", favorites)
                        },
                    )
                }
                item(key = "dl-continue") {
                    SettingsTile(
                        icon = Icons.Filled.Headphones,
                        title = "تحميل دروس «تابع الاستماع»",
                        subtitle = "${lessonsCountLabel(continueList.size)} لم تكمله",
                        onClick = {
                            if (continueList.isEmpty()) vm.showMessage("لا توجد دروس غير مكتملة.")
                            else vm.requestBulkDownload("تابع الاستماع", continueList)
                        },
                    )
                }
                item(key = "dl-section") {
                    SettingsTile(
                        icon = Icons.Filled.LibraryAdd,
                        title = "تحميل قسم كامل",
                        subtitle = "قسم رئيسي بكل فروعه أو قسم فرعي بعينه",
                        onClick = { sectionSheet = true },
                    )
                }
                item(key = "dl-manage") {
                    SettingsTile(
                        icon = Icons.Filled.DownloadDone,
                        title = "إدارة التنزيلات",
                        subtitle = "$downloadsCount درساً — ${formatStorage(downloadsBytes)}",
                        onClick = {
                            group = null
                            vm.closeSettings()
                            // ⛔ `open` لا `openRoot`: «تنزيلاتي» ليست تبويباً
                            // جذرياً، و`openRoot` يمسح مكدّس الرجوع — فكانت
                            // الشاشة تُفتح بلا شريط سفليّ ولا سهم رجوع ولا
                            // زرّ إعدادات، وزرُّ الرجوع النظاميّ يُبتلع.
                            // المخرج الوحيد كان قتلَ التطبيق.
                            vm.open(Route.Downloads)
                        },
                    )
                }
            }
        }
    }

    // ---- ورقة «الإشعارات والوِرد» ----
    if (group == "notifications") {
        ModalBottomSheet(onDismissRequest = { group = null }) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp, end = 8.dp, bottom = 32.dp,
                ),
            ) {
                item(key = "title") { GroupTitle("الإشعارات والوِرد اليومي") }
                item(key = "notif") {
                    SettingsTile(
                        icon = if (notifOn && !notifBlocked) Icons.Filled.NotificationsActive
                        else Icons.Filled.NotificationsOff,
                        title = "الإشعارات",
                        subtitle = when {
                            notifBlocked -> "الإشعارات محجوبة من النظام — اضغط للسماح"
                            notifOn -> "تصلك إشعارات المحتوى الجديد"
                            else -> "الإشعارات موقوفة"
                        },
                        // المخرج الوحيد لمن حُجبت عنه: حوار الإذن لا يُعاد عرضه.
                        onClick = if (notifBlocked) {
                            { openAppNotificationSettings(context) }
                        } else {
                            null
                        },
                        trailing = {
                            Switch(
                                checked = notifOn,
                                onCheckedChange = { enabled ->
                                    if (enabled) requestNotifications()
                                    vm.setNotificationsEnabled(enabled)
                                },
                            )
                        },
                    )
                }
                if (notifOn) {
                    item(key = "notif-reminder") {
                        SettingsTile(
                            icon = Icons.Filled.HistoryToggleOff,
                            title = "تذكير «تابع الاستماع»",
                            subtitle = "إشعار محلي بلطف بدرس لم تكمله",
                            trailing = {
                                Switch(checked = continueReminder, onCheckedChange = vm::setContinueReminderEnabled)
                            },
                        )
                    }
                }
                item(key = "ward") {
                    SettingsTile(
                        icon = Icons.Filled.WbSunny,
                        title = "الوِرد اليومي",
                        subtitle = if (wardEnabled) "درس اليوم في ${wardTimeLabel()}"
                        else "درس مقترح كل يوم في وقت تختاره",
                        trailing = {
                            Switch(
                                checked = wardEnabled,
                                onCheckedChange = { enabled ->
                                    // الإذن يُطلب عند التفعيل كما في المفتاح
                                    // العام: وِردٌ مفعَّل بلا إذن يُجدوَل كل
                                    // يوم ثم يُبتلع صامتاً بلا سبب ظاهر.
                                    if (enabled) {
                                        requestNotifications()
                                        wardTimeDialog = true
                                    } else {
                                        vm.disableWard()
                                    }
                                },
                            )
                        },
                    )
                }
                if (wardEnabled) {
                    item(key = "ward-time") {
                        SettingsTile(
                            icon = Icons.Filled.Schedule,
                            title = "وقت الوِرد",
                            trailing = {
                                Text(wardTimeLabel(), style = MaterialTheme.typography.titleMedium)
                            },
                            onClick = { wardTimeDialog = true },
                        )
                    }
                }
                // 🕌 وِرد المصحف — نظير وِرد الدروس فوقه بالضبط: مفتاحٌ يفتح
                // وقتَه عند التفعيل، ثم مقدارُه ووقتُه سطرين تحته.
                item(key = "quran-ward") {
                    SettingsTile(
                        icon = Icons.Filled.MenuBook,
                        title = "وِرد المصحف اليومي",
                        subtitle = when {
                            quranWardPages <= 0 -> "مقدار يوميّ من المصحف وتذكير في وقت تختاره"
                            // مقدارٌ بلا تذكير حالٌ ممكنة (من ألغى منتقي
                            // الوقت)، فلا نَعِد بتذكيرٍ لن يأتي.
                            quranWardHour < 0 -> "${quranWardAmountLabel(quranWardPages)} يومياً"
                            else -> "${quranWardAmountLabel(quranWardPages)} يومياً — " +
                                "تذكير في ${clockLabel(quranWardHour, quranWardMinute)}"
                        },
                        trailing = {
                            Switch(
                                checked = quranWardPages > 0,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        // الإذن أوّلاً كوِرد الدروس، ثم صفحةٌ
                                        // واحدة مبدئياً — أقلُّ مقدارٍ يُداوَم
                                        // عليه، ورفعُه سطرٌ واحد تحته.
                                        requestNotifications()
                                        vm.setQuranWardPages(1)
                                        quranWardTimeDialog = true
                                    } else {
                                        vm.disableQuranWard()
                                    }
                                },
                            )
                        },
                    )
                }
                if (quranWardPages > 0) {
                    item(key = "quran-ward-amount") {
                        SettingsTile(
                            icon = Icons.Filled.Flag,
                            title = "مقدار الوِرد",
                            trailing = {
                                Text(
                                    quranWardAmountLabel(quranWardPages),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            onClick = { quranWardSheet = true },
                        )
                    }
                    item(key = "quran-ward-time") {
                        SettingsTile(
                            icon = Icons.Filled.Schedule,
                            title = "وقت تذكير المصحف",
                            trailing = {
                                Text(
                                    clockLabel(quranWardHour, quranWardMinute),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            onClick = { quranWardTimeDialog = true },
                        )
                    }
                }
            }
        }
    }

    // ---- ورقة «بياناتي» ----
    if (group == "data") {
        ModalBottomSheet(onDismissRequest = { group = null }) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp, end = 8.dp, bottom = 32.dp,
                ),
            ) {
                item(key = "title") { GroupTitle("بياناتي") }
                item(key = "goal") {
                    SettingsTile(
                        icon = Icons.Filled.Flag,
                        title = "الهدف الأسبوعي للاستماع",
                        subtitle = if (weeklyGoal > 0) "$weeklyGoal دقيقة أسبوعياً" else "غير محدَّد",
                        onClick = { goalSheet = true },
                    )
                }
                item(key = "backup") {
                    SettingsTile(
                        icon = Icons.Filled.Save,
                        title = "حفظ نسخة من بياناتي",
                        subtitle = "المفضّلة والقوائم والسجل والتقدّم — ملف على جهازك",
                        onClick = { exportLauncher.launch("minbar-backup.json") },
                    )
                }
                item(key = "restore") {
                    SettingsTile(
                        icon = Icons.Filled.Restore,
                        title = "استعادة نسخة",
                        subtitle = "استيراد ملف نسخة احتياطية سابق",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                }
                item(key = "delete") {
                    SettingsTile(
                        icon = Icons.Filled.DeleteForever,
                        title = "حذف بياناتي",
                        subtitle = "حذف المساهمات والبيانات الشخصية نهائياً",
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = { deleteDialog = true },
                    )
                }
            }
        }
    }

    // ---- المظهر وحجم النص (حوار واحد مدمج) ----
    if (appearanceDialog) {
        var value by remember { mutableFloatStateOf(fontScale.coerceIn(0.8f, 1.4f)) }
        AlertDialog(
            onDismissRequest = { appearanceDialog = false },
            title = { Text("المظهر وحجم النص") },
            text = {
                Column {
                    listOf("light" to "فاتح", "dark" to "داكن", "system" to "اتّباع النظام").forEach { (v, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.store.setThemeMode(v) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeMode == v,
                                onClick = { vm.store.setThemeMode(v) },
                            )
                            Text(label)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "حجم النص: ${(value * 100).toInt()}%",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        // ⚠️ الحفظ عند رفع الإصبع لا عند زرّ «حفظ»: السمة في
                        // الحوار نفسه تُطبَّق فور النقر، فكان الحوار الواحد
                        // يعمل بقاعدتين — يرى المستخدم أثر السمة ولا يرى أثر
                        // الحجم، فيغلق الحوار ظانّاً أنّ الاثنين حُفِظا.
                        onValueChangeFinished = { vm.store.setFontScale(value) },
                        valueRange = 0.8f..1.4f,
                        steps = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { appearanceDialog = false }) { Text("تمّ") }
            },
        )
    }

    // ---- ورقة «تحميل قسم كامل» ----
    if (sectionSheet) {
        ModalBottomSheet(onDismissRequest = { sectionSheet = false }) {
            Text(
                "تحميل قسم كامل",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            // تجميع واحد مُتذكَّر بدل ترشيح كل الدروس لكل قسم في كل recomposition.
            val lessonsByCategory = remember(content.lessons) {
                content.lessons.groupBy { it.categoryId }
            }
            val lessonsBySubcategory = remember(content.lessons) {
                content.lessons.groupBy { it.subcategoryId }
            }
            // والفروع كذلك: كانت تُرشَّح كلّها لكل قسم في كل recomposition —
            // نفس العلّة التي عُولجت في الدروس أعلاه، وقد سقطت منها سهواً.
            val subsByCategory = remember(content.subcategories) {
                content.subcategories.groupBy { it.categoryId }
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                content.categories.forEach { category ->
                    val categoryLessons = lessonsByCategory[category.id].orEmpty()
                    item(key = "cat-${category.id}") {
                        ListItem(
                            modifier = Modifier.clickable {
                                sectionSheet = false
                                vm.requestBulkDownload(category.name, categoryLessons)
                            },
                            leadingContent = {
                                Icon(iconForCategory(category.id), null, tint = colorForCategory(category.id))
                            },
                            headlineContent = { Text(category.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text("القسم كاملاً — ${lessonsCountLabel(categoryLessons.size)}") },
                        )
                    }
                    items(
                        subsByCategory[category.id].orEmpty(),
                        key = { "sub-${it.id}" },
                    ) { sub ->
                        val subLessons = lessonsBySubcategory[sub.id].orEmpty()
                        ListItem(
                            modifier = Modifier.padding(start = 24.dp).clickable {
                                sectionSheet = false
                                vm.requestBulkDownload(sub.name, subLessons)
                            },
                            headlineContent = { Text(sub.name) },
                            supportingContent = { Text(lessonsCountLabel(subLessons.size)) },
                        )
                    }
                }
            }
        }
    }

    // ---- وقت الوِرد ----
    if (wardTimeDialog) {
        val timeState = rememberTimePickerState(
            initialHour = if (wardEnabled && wardHour >= 0) wardHour else 6,
            initialMinute = if (wardEnabled && wardHour >= 0) wardMinute else 0,
        )
        AlertDialog(
            onDismissRequest = { wardTimeDialog = false },
            title = { Text("وقت الوِرد اليومي") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setWardTime(timeState.hour, timeState.minute)
                    wardTimeDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { wardTimeDialog = false }) { Text("إلغاء") }
            },
        )
    }

    // ---- وقت تذكير وِرد المصحف ----
    if (quranWardTimeDialog) {
        val timeState = rememberTimePickerState(
            initialHour = if (quranWardHour >= 0) quranWardHour else 6,
            initialMinute = if (quranWardHour >= 0) quranWardMinute else 0,
        )
        AlertDialog(
            onDismissRequest = { quranWardTimeDialog = false },
            title = { Text("وقت تذكير وِرد المصحف") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setQuranWardTime(timeState.hour, timeState.minute)
                    quranWardTimeDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { quranWardTimeDialog = false }) { Text("إلغاء") }
            },
        )
    }

    // ---- مقدار وِرد المصحف ----
    if (quranWardSheet) {
        ModalBottomSheet(onDismissRequest = { quranWardSheet = false }) {
            Text(
                "مقدار وِرد المصحف",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            QURAN_WARD_AMOUNTS.forEach { (pages, label) ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.setQuranWardPages(pages)
                        quranWardSheet = false
                    },
                    headlineContent = { Text(label) },
                    // مقابلُه بالصفحات لمن لا يعرف كم الحزب — ولا يُكتب لما
                    // اسمُه صفحةٌ أصلاً فيصير تكراراً.
                    supportingContent = if (pages > 2) {
                        { Text("${com.ali.menbaradkshk.util.quranPagesLabel(pages)} يومياً") }
                    } else {
                        null
                    },
                    trailingContent = if (quranWardPages == pages) {
                        { Icon(Icons.Filled.Check, null, tint = Teal) }
                    } else {
                        null
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- هدف التنزيل التلقائي ----
    if (autoTargetSheet) {
        ModalBottomSheet(onDismissRequest = { autoTargetSheet = false }) {
            Text(
                "ما الذي يُنزّل تلقائياً؟",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            AUTO_TARGETS.forEach { (value, label) ->
                // وصفٌ لكل هدف — و«الأقسام التي أتابعها» يقول عددها صراحةً كي
                // لا يبدو معطوباً لمن لم يتابع بعد.
                val hint = when {
                    value == "main" -> "ما يُقترح لك بحسب استماعك"
                    value != "followed" -> "آخر ما نُشر في المنبر"
                    followedCount > 0 -> "تتابع ${sectionsLabel(followedCount)}"
                    else -> "لا تتابع أقساماً بعد — تابِع قسماً ليعمل"
                }
                ListItem(
                    modifier = Modifier.clickable {
                        vm.setAutoDownloadTarget(value)
                        autoTargetSheet = false
                    },
                    headlineContent = { Text(label) },
                    supportingContent = { Text(hint) },
                    trailingContent = if (autoTarget == value) {
                        { Icon(Icons.Filled.Check, null, tint = Teal) }
                    } else {
                        null
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- الهدف الأسبوعي ----
    if (goalSheet) {
        ModalBottomSheet(onDismissRequest = { goalSheet = false }) {
            Text(
                "الهدف الأسبوعي",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            listOf(0, 30, 60, 120, 180, 300).forEach { minutes ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.store.setWeeklyGoalMinutes(minutes)
                        goalSheet = false
                    },
                    headlineContent = {
                        Text(if (minutes == 0) "بلا هدف" else "$minutes دقيقة أسبوعياً")
                    },
                    trailingContent = if (weeklyGoal == minutes) {
                        { Icon(Icons.Filled.Check, null, tint = Teal) }
                    } else {
                        null
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- حذف بياناتي ----
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("حذف بياناتي") },
            text = {
                Text(
                    "سيُحذف سجل مساهماتك وملفاتها من السحابة، ثم تُمسح المفضلة والقوائم والسجل والتنزيلات من هذا الجهاز. لا يمكن التراجع.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    scope.launch {
                        runCatching { vm.deleteMyData() }
                            .onFailure { vm.showMessage("تعذّر حذف البيانات. تحقق من الاتصال وحاول مجدداً.") }
                    }
                }) { Text("حذف نهائي") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false }) { Text("إلغاء") }
            },
        )
    }
}

/// أهداف التنزيل التلقائي الثلاثة: القيمة المخزَّنة واسمها المعروض. الترتيب
/// هو ترتيب الورقة، و«recent» أوّلها لأنّه الافتراض.
private val AUTO_TARGETS = listOf(
    "recent" to "أحدث الدروس",
    "main" to "خلاصتك المقترحة",
    "followed" to "الأقسام التي أتابعها",
)

private fun autoTargetLabel(value: String): String =
    AUTO_TARGETS.firstOrNull { it.first == value }?.second ?: AUTO_TARGETS.first().second

/**
 * مقادير وِرد المصحف الخمسة: عدد الصفحات واسمُه كما يقوله الناس.
 *
 * ⚠️ «ربع حزب» ٣ صفحات لا ٢٫٥: الوِرد يُقاس بالصفحة والصفحة لا تتجزّأ في
 * العدّ، ورفعُ الكسر أَولى من خفضه. وخمسةٌ لا أكثر عمداً — قائمةُ خياراتٍ
 * طويلة تجعل أبسط قرار عبئاً.
 */
private val QURAN_WARD_AMOUNTS = listOf(
    1 to "صفحة",
    2 to "صفحتان",
    3 to "ربع حزب",
    10 to "حزب",
    20 to "جزء",
)

private fun quranWardAmountLabel(pages: Int): String =
    QURAN_WARD_AMOUNTS.firstOrNull { it.first == pages }?.second
        ?: com.ali.menbaradkshk.util.quranPagesLabel(pages)

/// عدد الأقسام بصيغة عربيّة صحيحة («قسمين» لا «2 قسم») — بقاعدة
/// [com.ali.menbaradkshk.util.arabicCountLabel] الواحدة نفسها التي تصوغ
/// الصفحات، فلا صيغتا جمعٍ تفترقان.
private fun sectionsLabel(n: Int): String =
    com.ali.menbaradkshk.util.arabicCountLabel(n, "قسماً واحداً", "قسمين", "أقسام", "قسماً")

/// وقتٌ بصيغة ١٢ ساعة عربيّة — يتقاسمه وِردا الدروس والمصحف، فالساعة السالبة
/// تعني «بلا تذكير» في كليهما.
private fun clockLabel(hour: Int, minute: Int): String {
    if (hour < 0) return "—"
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val period = if (hour < 12) "ص" else "م"
    return "%d:%02d %s".format(Locale.ROOT, hour12, minute, period)
}

// Locale.ROOT صراحةً: صيغة الجهاز العربية كانت تخلط أرقاماً هندية وفاصلاً
// عشرياً «٫» مع «GB» اللاتينية في سطر RTL واحد.
private fun formatStorage(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(Locale.ROOT, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(Locale.ROOT, bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(Locale.ROOT, bytes / 1_024.0)
    else -> "$bytes B"
}

/// عنوان الورقة الفرعية للموضوع المجمَّع.
@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
        color = Teal,
    )
}

/// يفتح إعدادات إشعارات التطبيق في النظام — المخرج الوحيد لمن حُجبت عنه
/// الإشعارات، إذ لا يُعرض حوار الإذن بعد الرفض مرّتين.
private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        // ما قبل أندرويد 8: لا شاشة إشعارات للتطبيق، فصفحة التطبيق نفسها.
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        )
    }
    runCatching {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/// بند إعدادات بنمط SettingsTile في نبراس: أيقونة + عنوان (+وصف) + عنصر جانبي.
@Composable
private fun SettingsTile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: androidx.compose.ui.graphics.Color = Teal,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp)) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    )
}
