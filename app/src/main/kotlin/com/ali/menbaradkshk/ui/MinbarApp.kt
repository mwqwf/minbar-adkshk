@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.AppConfigRepository
import com.ali.menbaradkshk.data.ContentState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.catch

private data class RootTab(
    val route: Route,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/// الشريط السفلي خمسة تبويبات: «المفضّلة» اندمجت في «قوائمي» (صارت تبويباً
/// داخلها)، وحلّت مكانها «الأذكار» — فبقي العدد كما هو ولم يُفقد شيء.
private val rootTabs = listOf(
    RootTab(Route.Home, "الرئيسية", Icons.Outlined.Home, Icons.Filled.Home),
    RootTab(Route.Library, "المكتبة", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    RootTab(Route.Adhkar, "الأذكار", Icons.Outlined.Star, Icons.Filled.Star),
    RootTab(Route.MyLists, "قوائمي", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    // «المصحف» حلّت محلّ «تنزيلاتي» في الشريط السفلي: المصحف يُفتح كل يوم
    // فيستحقّ مكاناً دائماً، والتنزيلات تُزار عند الحاجة فانتقلت إلى
    // الإجراءات السريعة في الرئيسية بلا فقدان أيّ وظيفة.
    RootTab(Route.Quran, "المصحف", Icons.Outlined.MenuBook, Icons.Filled.MenuBook),
)

/** مفتاح حالة يميّز محتوى المسار، ويتجاهل startAtMs المؤقت لنفس الدرس. */
private fun routeStateKey(route: Route): String = when (route) {
    Route.Home -> "Home"
    Route.Library -> "Library"
    Route.MyLists -> "MyLists"
    Route.Adhkar -> "Adhkar"
    is Route.AdhkarSection -> "AdhkarSection:${route.id}"
    Route.AdhkarReminders -> "AdhkarReminders"
    Route.Downloads -> "Downloads"
    Route.Quran -> "Quran"
    is Route.QuranSurah -> "QuranSurah:${route.number}"
    Route.Favorites -> "Favorites"
    is Route.Category -> "Category:${route.id}"
    is Route.Subcategory -> "Subcategory:${route.id}"
    is Route.Lesson -> "Lesson:${route.id}"
    is Route.Search -> "Search:${route.initial}"
    is Route.Playlist -> "Playlist:${route.id}"
    Route.Radio -> "Radio"
    Route.Car -> "Car"
    Route.Stats -> "Stats"
    Route.Contribute -> "Contribute"
    Route.ContributeTranscript -> "ContributeTranscript"
    Route.MySubmissions -> "MySubmissions"
    Route.Notifications -> "Notifications"
    Route.About -> "About"
}

@Composable
fun MinbarApp(vm: AppViewModel, requestNotifications: () -> Unit) {
    val route by vm.route.collectAsState()
    val content by vm.content.state.collectAsState()
    val playback by vm.playback.state.collectAsState()
    val message by vm.message.collectAsState()
    val showSettings by vm.showSettings.collectAsState()
    val transcriptContribution by vm.transcriptContribution.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 🔔 تذكير التحديث — **شاشة كاملة** لا شريطاً ولا إشعاراً: أغلب
    // المستخدمين لا ينظرون إلى الإشعارات ولا إلى شريط صغير، فتبقى نسخ قديمة
    // تعمل بمزايا ناقصة. وهي مع ذلك متزنة: لا تحجب التطبيق («لاحقاً» متاح
    // دائماً)، ولا تتكرّر للنسخة الاختياريّة أكثر من مرّة كل ٢٤ ساعة.
    var updateDeferred by rememberSaveable { mutableStateOf(false) }
    val pendingUpdate = updateStatus.takeIf { it !is AppConfigRepository.Status.None }
    if (pendingUpdate != null && !updateDeferred && vm.shouldPromptUpdate(pendingUpdate)) {
        UpdateScreen(
            status = pendingUpdate,
            onUpdate = { vm.openStoreFor(pendingUpdate) },
            onLater = {
                updateDeferred = true
                vm.noteUpdatePromptShown(pendingUpdate)
            },
        )
        return
    }

    // 📣 التذكير عند فتح درس — يعمل فوق الشاشة لا بدلاً منها، ويُقيَّم مرّة
    // واحدة لكل درس يُفتَح (مفتاح الدرس)، فالتنقّل داخل المشغّل لا يعيده.
    var nudgeShownFor by remember { mutableStateOf<String?>(null) }
    var nudgeVisible by remember { mutableStateOf(false) }
    val lessonRoute = route as? Route.Lesson
    LaunchedEffect(lessonRoute?.id, updateStatus) {
        val lessonId = lessonRoute?.id
        if (lessonId == null) {
            nudgeVisible = false
            return@LaunchedEffect
        }
        if (nudgeShownFor == lessonId) return@LaunchedEffect
        if (!vm.shouldNudgeOnLesson(updateStatus)) return@LaunchedEffect
        nudgeShownFor = lessonId
        vm.noteLessonNudgeShown()
        nudgeVisible = true
    }
    if (nudgeVisible) {
        LessonUpdateNudge(
            status = updateStatus,
            onUpdate = {
                nudgeVisible = false
                vm.openStore("")
            },
            onLater = { nudgeVisible = false },
            onMute = {
                nudgeVisible = false
                vm.muteLessonNudge(updateStatus)
            },
        )
    }

    // تأكيد التحميل الجماعي — حوار واحد على مستوى التطبيق: كل الشاشات
    // تمرّ بـ`requestBulkDownload`، فلا يبقى مدخل يُنزّل قسماً بلا سؤال.
    val pendingBulk by vm.pendingBulkDownload.collectAsState()
    pendingBulk?.let { request ->
        ConfirmBulkDownloadDialog(
            label = request.label,
            count = request.count,
            onConfirm = { vm.confirmBulkDownload() },
            onDismiss = { vm.dismissBulkDownload() },
        )
    }

    val isRoot = rootTabs.any { it.route == route }
    val navigationBlocked = route == Route.ContributeTranscript &&
        transcriptContribution.submitting
    // المشغّل ووضع القيادة شاشتان بملء الشاشة بلا أشرطة (نمط الأصل).
    val fullScreen = route is Route.Lesson || route == Route.Car

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    // ↩️ شريط «تراجع» للأفعال القابلة للرجوع (الإزالة من قائمة تشغيل مثلاً).
    val undo by vm.undo.collectAsState()
    LaunchedEffect(undo) {
        val pending = undo ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = pending.text,
            actionLabel = pending.actionLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) pending.action()
        vm.consumeUndo()
    }
    // فشل التشغيل كان صامتاً خارج شاشة المشغّل (صفوف القوائم/المشغّل المصغّر):
    // نعرضه عالمياً مع «إعادة المحاولة». شاشة المشغّل لها شريطها الثابت فلا نكرّره.
    // نعرض الرسالة مرة واحدة لكل خطأ؛ كل محاولة جديدة تُصفّر الخطأ أولاً فيُعاد عرضه.
    var shownError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playback.error, route) {
        val error = playback.error
        if (error == null) {
            shownError = null
            return@LaunchedEffect
        }
        if (route is Route.Lesson || error == shownError) return@LaunchedEffect
        shownError = error
        val result = snackbar.showSnackbar(
            message = error,
            actionLabel = "إعادة المحاولة",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.playback.retry()
    }
    BackHandler(enabled = !isRoot && !navigationBlocked) { vm.back() }
    BackHandler(enabled = navigationBlocked) {
        vm.showMessage("انتظر اكتمال إرسال المساهمة قبل الرجوع.")
    }

    // 🗂️ الإعدادات — درج جانبي حقيقي (بنمط تويتر/تلجرام): يُفتح بزرّه في
    // الشريط العلوي أو بالسحب من حافة الشاشة في التبويبات الجذرية.
    // حالة الفتح تبقى في AppViewModel (openSettings/closeSettings كما كانت)،
    // والمزامنة ثنائية الاتجاه: الحالة تحرّك الدرج، وسحب المستخدم يعيد الحالة.
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )
    // بلا حَارسَي isClosed/isOpen: أثناء الحركة كلاهما false، فكان «رجوع»
    // في منتصف حركة الفتح يلغيها ولا يستدعي close() — درج عالق في المنتصف.
    // الاستدعاء غير المشروط آمن (لا-عمل إن كان في وضعه المطلوب أصلاً).
    LaunchedEffect(showSettings) {
        if (showSettings) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        androidx.compose.runtime.snapshotFlow { drawerState.currentValue }
            .collect { value ->
                if (value == androidx.compose.material3.DrawerValue.Closed &&
                    vm.showSettings.value
                ) {
                    vm.closeSettings()
                } else if (value == androidx.compose.material3.DrawerValue.Open &&
                    !vm.showSettings.value
                ) {
                    vm.openSettings()
                }
            }
    }
    BackHandler(enabled = showSettings) { vm.closeSettings() }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        // السحب لفتح الدرج متاح في التبويبات الجذرية فقط (كتلجرام في شاشته
        // الرئيسية)؛ داخل المشغّل والشاشات الفرعية تبقى الإيماءات لأصحابها،
        // ويظلّ السحب للإغلاق متاحاً دائماً ما دام الدرج مفتوحاً.
        gesturesEnabled = showSettings || (isRoot && !fullScreen),
        drawerContent = { SettingsDrawerContent(vm, requestNotifications) },
    ) {
    Scaffold(
        topBar = {
            if (!fullScreen) {
                CenterAlignedTopAppBar(
                    title = { Text(titleFor(route, vm, content)) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = if (isDarkTheme(vm.store.themeMode())) AppBarBackgroundDark else AppBarBackgroundLight,
                        titleContentColor = AppBarForeground,
                        navigationIconContentColor = AppBarForeground,
                        actionIconContentColor = AppBarForeground,
                    ),
                    navigationIcon = {
                        if (!isRoot) {
                            IconButton(
                                onClick = { vm.back() },
                                enabled = !navigationBlocked,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                            }
                        }
                    },
                    actions = {
                        when (route) {
                            Route.Home -> {
                                if (content.syncing) {
                                    Box(Modifier.padding(14.dp)) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = AppBarForeground,
                                        )
                                    }
                                }
                                StreakChip(vm)
                                NotificationBell(vm)
                                MySubmissionsButton(vm)
                                // «تنزيلاتي» هنا بطلب صريح: مكانها الطبيعيّ مع
                                // بقيّة مداخل «ما يخصّني» (الإشعارات
                                // والمساهمات)، لا مع شرائح الاستماع في
                                // الإجراءات السريعة.
                                IconButton(onClick = { vm.open(Route.Downloads) }) {
                                    Icon(Icons.Filled.Download, "تنزيلاتي")
                                }
                                IconButton(onClick = { vm.open(Route.Search()) }) {
                                    Icon(Icons.Filled.Search, "بحث")
                                }
                                IconButton(onClick = vm::openSettings) {
                                    Icon(Icons.Filled.Menu, "الإعدادات")
                                }
                            }
                            Route.Contribute -> {
                                IconButton(onClick = { vm.open(Route.MySubmissions) }) {
                                    Icon(Icons.Filled.HistoryEdu, "مساهماتي")
                                }
                            }
                            else -> Unit
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // 📤 «شارك درساً» يظهر في تبويب الرئيسية فقط (نمط الأصل).
            if (route == Route.Home) {
                ExtendedFloatingActionButton(
                    onClick = { vm.open(Route.Contribute) },
                    icon = { Icon(Icons.Filled.MicExternalOn, contentDescription = null) },
                    text = { Text("شارك درساً") },
                )
            }
        },
        bottomBar = {
            if (!fullScreen) {
                Column {
                    // المشغّل المصغّر: التبويبات الجذرية + دروس القسم الفرعي
                    // + **قراءة سورة**. المصحف يستعمل مشغّل التطبيق نفسه، فمن
                    // غير المفهوم أن يختفي شريطه المعتاد هناك وحده: الشكل
                    // والأزرار (تشغيل/التالي/فتح) تبقى واحدة في كل مكان.
                    if (isRoot || route is Route.Subcategory || route is Route.QuranSurah) {
                        MiniPlayer(
                            state = playback,
                            // المشغّل المصغّر يخدم الدروس **والمصحف** معاً:
                            // معرّف الآية ليس معرّف درس، وفتحه كدرس كان
                            // ينتهي بشاشة «الدرس غير متاح» أثناء التلاوة.
                            onOpen = { vm.openNowPlaying() },
                            onToggle = vm.playback::toggle,
                            onNext = { vm.playback.next() },
                            onRetry = { vm.playback.retry() },
                            onDismissError = { vm.playback.clearError() },
                        )
                    }
                    if (isRoot) {
                        // مؤشّر التحديد تركوازي فاتح/داكن كما في navigationBarTheme الأصلي.
                        val dark = isDarkTheme(vm.store.themeMode())
                        NavigationBar {
                            rootTabs.forEach { tab ->
                                val selected = route == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { vm.openRoot(tab.route) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = if (dark) {
                                            androidx.compose.ui.graphics.Color(0xFF31575A)
                                        } else {
                                            androidx.compose.ui.graphics.Color(0xFFDCEBE9)
                                        },
                                        selectedIconColor = if (dark) SecondaryGold else Teal,
                                        selectedTextColor = if (dark) SecondaryGold else Teal,
                                    ),
                                    icon = {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.icon,
                                            contentDescription = tab.label,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    } else {
                        // العرض حتى حافة الشاشة: شريط التنقّل السفلي يستهلك
                        // حشوة شريط النظام بنفسه، فبغيابه لا يستهلكها شيء
                        // ويسكن آخر المحتوى (والمشغّل المصغّر) خلف شريط
                        // التنقّل. هذه المساحة تحجزها بلا أي تغيير في السمة.
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsBottomHeight(WindowInsets.navigationBars),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // حافظ حالة الشاشات عبر التنقل: بلاه كان تبديل الشاشة (مثلاً مشاركة
        // صورة تفتح «ساهم بالنص») يدمّر rememberSaveable لنموذج «شارك درساً»
        // المفتوح فيمسح ملفات المستخدم وعنوانه بصمت.
        val screenStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        Column(Modifier.padding(padding)) {
        Box(Modifier.weight(1f)) {
            screenStateHolder.SaveableStateProvider(routeStateKey(route)) {
                when (val current = route) {
                Route.Home -> HomeScreen(vm, content, playback)
                Route.Library -> LibraryScreen(vm, content)
                Route.MyLists -> MyListsScreen(vm, playback)
                Route.Adhkar -> AdhkarScreen(vm)
                is Route.AdhkarSection -> AdhkarSectionScreen(vm, current.id)
                Route.AdhkarReminders -> AdhkarRemindersScreen(vm)
                Route.Downloads -> DownloadsScreen(vm)
                Route.Quran -> QuranIndexScreen(vm)
                is Route.QuranSurah -> QuranSurahScreen(vm, current.number, current.ayah, playback)
                Route.Favorites -> MyListsScreen(vm, playback, initialTab = 0)
                is Route.Category -> CategoryScreen(vm, current.id, content)
                is Route.Subcategory -> LessonsScreen(vm, current.id, playback)
                is Route.Search -> SearchScreen(vm, current.initial, playback)
                is Route.Lesson -> {
                    val lesson = content.lessonById[current.id]
                    if (lesson == null) {
                        EmptyState(
                            "الدرس غير متاح",
                            if (content.loading) "جارٍ تحميل المحتوى…" else "ربما أزيل أو لم يُنشر بعد.",
                        )
                    } else {
                        PlayerScreen(vm, lesson, current.startAtMs, playback)
                    }
                }
                is Route.Playlist -> PlaylistDetailScreen(vm, current.id)
                Route.Radio -> RadioScreen(vm)
                Route.Car -> CarScreen(vm, playback)
                Route.Stats -> StatsScreen(vm)
                Route.Contribute -> ContributeScreen(vm)
                Route.ContributeTranscript -> TranscriptContributeScreen(vm)
                Route.MySubmissions -> MySubmissionsScreen(vm)
                Route.Notifications -> NotificationsScreen(vm)
                Route.About -> AboutScreen(vm)
                }
            }
        }
        }
    }
    }
}

/**
 * شاشة «حدِّث التطبيق» بملء الشاشة.
 *
 * **لماذا ملء الشاشة؟** الشريط الصغير والإشعار كلاهما يُتجاهَل عملياً.
 * **ولماذا متزنة؟** الحجب الكامل يحوّل تطبيق استماع إلى رهينة تحديث؛ والدروس
 * المنزَّلة يجب أن تبقى قابلة للاستماع بلا إنترنت ومهما قدُم الإصدار — فزرّ
 * المتابعة موجود دائماً.
 */
@Composable
private fun UpdateScreen(
    status: AppConfigRepository.Status,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val required = status is AppConfigRepository.Status.Required
    val custom = when (status) {
        is AppConfigRepository.Status.Required -> status.message
        is AppConfigRepository.Status.Optional -> status.message
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                if (required) "دعم هذا الإصدار يوشك أن يتوقّف" else "تتوفّر نسخة أحدث",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                custom.ifBlank {
                    if (required) {
                        "نسختك من منبر ادكصهك قديمة وسيتوقّف دعمها قريباً. " +
                            "حدِّثها لتبقى المزايا كلّها تعمل كما ينبغي."
                    } else {
                        "حدِّث منبر ادكصهك لئلّا تفقد ميزات مهمّة وإصلاحات جديدة."
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "تنزيلاتك وقوائمك ومواضع استماعك تبقى كما هي.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("تحديث الآن", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text(if (required) "متابعة الآن" else "لاحقاً")
            }
        }
    }
}

/**
 * 📣 تذكير التحديث عند فتح درس — الطبقة التي تصل إلى **كل** مستخدم.
 *
 * الطبقات الأخرى تفترض قارئاً يتابع: الإشعار يُهمَل، وشاشة التذكير الكاملة
 * لا تظهر إلا مرّة كل ٢٤ ساعة عند الإقلاع. لكن فتح درس هو الفعل الذي يقوم به
 * كل مستخدم كل يوم، فهو المكان الوحيد المضمون.
 *
 * **البساطة مقصودة إلى أقصى حدّ**: أيقونة كبيرة، سطران قصيران، زرّ أخضر
 * عريض واحد. أغلب المستخدمين لا يقرؤون العربية جيّداً ولا يعرفون التقنية —
 * فكل كلمة زائدة أو خيار إضافي يعني تحديثاً لا يحدث. والزرّ يفتح المتجر
 * مباشرة بلا رابط ولا خطوة وسيطة.
 *
 * ولا يُحجَب شيء: «ليس الآن» يمرّر إلى الدرس فوراً، والحدّ مرّتان في اليوم.
 */
@Composable
private fun LessonUpdateNudge(
    status: AppConfigRepository.Status,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onMute: () -> Unit,
) {
    val required = status is AppConfigRepository.Status.Required
    androidx.compose.ui.window.Dialog(onDismissRequest = onLater) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(58.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "حدِّث التطبيق",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (required) {
                        "نسختك قديمة، وقد يتوقّف دعمها قريباً فلا تعمل كما ينبغي."
                    } else {
                        "صدرت نسخة أحدث. نسختك الحاليّة قد يتوقّف دعمها قريباً."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "تنزيلاتك وقوائمك تبقى كما هي.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("تحديث الآن", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                    Text("ليس الآن")
                }
                // مخرج صريح لمن حسم أمره — يسكت هذه النسخة وحدها، ويعود
                // التذكير حين تصدر نسخة أحدث منها.
                TextButton(onClick = onMute, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "لا تُذكّرني مجدداً",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/// شريحة السلسلة 🔥 في الشريط العلوي — كانت مدفونة في ورقة الإعدادات
/// و«حصادك» فقط، فلا يراها المستخدم حيث تحفّزه فعلاً. تظهر من يومين فصاعداً،
/// والنقر يفتح «حصادك».
@Composable
private fun StreakChip(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val streak = remember(revision) { vm.store.streakDays() }
    if (streak < 2) return
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(AppBarForeground.copy(alpha = 0.16f))
            .clickable { vm.open(Route.Stats) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = "سلسلة الاستماع: $streak يوماً",
            tint = GoldOnDark,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$streak",
            color = AppBarForeground,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/// زر الجرس مع عدّاد غير المقروء — نمط NotificationBell الأصلي.
@Composable
private fun NotificationBell(vm: AppViewModel) {
    val items by vm.notifications.collectAsState()
    val revision by vm.store.revision.collectAsState()
    val unread = remember(items, revision) {
        val lastSeen = vm.store.notificationLastSeenMs()
        visibleNotifications(vm, items).count { it.createdAtMs > lastSeen }
    }
    IconButton(onClick = { vm.open(Route.Notifications) }) {
        if (unread > 0) {
            BadgedBox(badge = { Badge { Text("$unread") } }) {
                Icon(Icons.Filled.NotificationsNone, contentDescription = "الإشعارات")
            }
        } else {
            Icon(Icons.Filled.NotificationsNone, contentDescription = "الإشعارات")
        }
    }
}

/// زر «مساهماتي» بجوار الجرس — يظهر لمن لديه هوية مساهمات فقط،
/// وعليه نقطة إذا حُسمت مساهمة بعد آخر زيارة للشاشة.
@Composable
private fun MySubmissionsButton(vm: AppViewModel) {
    var user by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (user == null) return

    val flow = remember { vm.submissions.mine().catch { emit(emptyList()) } }
    val submissions by flow.collectAsState(initial = emptyList())
    val revision by vm.store.revision.collectAsState()
    val hasNewDecision = remember(submissions, revision) {
        val seen = vm.store.submissionsLastSeenMs()
        submissions.any { it.status != "pending" && it.decidedAtMs > seen }
    }
    IconButton(onClick = { vm.open(Route.MySubmissions) }) {
        if (hasNewDecision) {
            BadgedBox(badge = { Badge(modifier = Modifier.size(8.dp)) }) {
                Icon(Icons.Filled.Outbox, contentDescription = "مساهماتي")
            }
        } else {
            Icon(Icons.Filled.Outbox, contentDescription = "مساهماتي")
        }
    }
}

// يأخذ `content` المُجمَّع من الواجهة (لا `state.value` مباشرة) كي يتفاعل
// العنوان مع وصول البيانات/تغيّرها بدل التجمّد على أول قراءة.
private fun titleFor(route: Route, vm: AppViewModel, content: ContentState): String = when (route) {
    Route.Home -> "منبر ادكصهك"
    Route.Library -> "المكتبة"
    Route.MyLists -> "قوائمي"
    Route.Adhkar -> "الأذكار"
    is Route.AdhkarSection -> com.ali.menbaradkshk.data.Adhkar.titleFor(route.id)
    Route.AdhkarReminders -> "تذكيرات الأذكار"
    Route.Downloads -> "تنزيلاتي"
    Route.Quran -> "المصحف الكامل"
    // اسم السورة نفسه عنواناً — أوضح من كلمة «المصحف» المكرَّرة.
    is Route.QuranSurah -> vm.quranSurahName(route.number)
    Route.Favorites -> "المفضّلة"
    is Route.Category -> content.categoryById[route.id]?.name ?: "الأقسام"
    is Route.Subcategory -> content.subcategoryById[route.id]?.name ?: "الدروس"
    is Route.Lesson -> "الآن يُشغَّل"
    is Route.Search -> "بحث"
    is Route.Playlist -> vm.store.playlists().firstOrNull { it.id == route.id }?.name ?: "قائمة التشغيل"
    Route.Radio -> "إذاعة منبر"
    Route.Car -> "وضع القيادة"
    Route.Stats -> "حصادك"
    Route.Contribute -> "شارك درساً"
    Route.ContributeTranscript -> "ساهم بالنص"
    Route.MySubmissions -> "مساهماتي"
    Route.Notifications -> "الإشعارات"
    Route.About -> "حول التطبيق"
}
