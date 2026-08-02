@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.catch

private data class RootTab(
    val route: Route,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val rootTabs = listOf(
    RootTab(Route.Home, "الرئيسية", Icons.Outlined.Home, Icons.Filled.Home),
    RootTab(Route.Library, "المكتبة", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    RootTab(Route.MyLists, "قوائمي", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    RootTab(Route.Downloads, "تنزيلاتي", Icons.Outlined.Download, Icons.Filled.Download),
    RootTab(Route.Favorites, "المفضّلة", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
)

/** مفتاح حالة يميّز محتوى المسار، ويتجاهل startAtMs المؤقت لنفس الدرس. */
private fun routeStateKey(route: Route): String = when (route) {
    Route.Home -> "Home"
    Route.Library -> "Library"
    Route.MyLists -> "MyLists"
    Route.Downloads -> "Downloads"
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
}

@Composable
fun MinbarApp(vm: AppViewModel, requestNotifications: () -> Unit) {
    val route by vm.route.collectAsState()
    val content by vm.content.state.collectAsState()
    val playback by vm.playback.state.collectAsState()
    val message by vm.message.collectAsState()
    val showSettings by vm.showSettings.collectAsState()
    val transcriptContribution by vm.transcriptContribution.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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

    Scaffold(
        topBar = {
            if (!fullScreen) {
                CenterAlignedTopAppBar(
                    title = { Text(titleFor(route, vm)) },
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
                                NotificationBell(vm)
                                MySubmissionsButton(vm)
                                IconButton(onClick = { vm.open(Route.Search()) }) {
                                    Icon(Icons.Filled.Search, "بحث")
                                }
                                IconButton(onClick = vm::openSettings) {
                                    Icon(Icons.Filled.MoreVert, "الإعدادات")
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
                    // المشغّل المصغّر: التبويبات الجذرية + شاشة دروس القسم الفرعي.
                    if (isRoot || route is Route.Subcategory) {
                        MiniPlayer(
                            state = playback,
                            onOpen = {
                                playback.mediaId.takeIf(String::isNotBlank)
                                    ?.let { vm.open(Route.Lesson(it)) }
                            },
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
                    }
                }
            }
        },
    ) { padding ->
        // حافظ حالة الشاشات عبر التنقل: بلاه كان تبديل الشاشة (مثلاً مشاركة
        // صورة تفتح «ساهم بالنص») يدمّر rememberSaveable لنموذج «شارك درساً»
        // المفتوح فيمسح ملفات المستخدم وعنوانه بصمت.
        val screenStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        Box(Modifier.padding(padding)) {
            screenStateHolder.SaveableStateProvider(routeStateKey(route)) {
                when (val current = route) {
                Route.Home -> HomeScreen(vm, content, playback)
                Route.Library -> LibraryScreen(vm, content)
                Route.MyLists -> MyListsScreen(vm, playback)
                Route.Downloads -> DownloadsScreen(vm)
                Route.Favorites -> FavoritesScreen(vm, playback)
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
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(vm, requestNotifications)
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

private fun titleFor(route: Route, vm: AppViewModel): String = when (route) {
    Route.Home -> "منبر ادكصهك"
    Route.Library -> "المكتبة"
    Route.MyLists -> "قوائمي"
    Route.Downloads -> "تنزيلاتي"
    Route.Favorites -> "المفضّلة"
    is Route.Category -> vm.content.state.value.categoryById[route.id]?.name ?: "الأقسام"
    is Route.Subcategory -> vm.content.state.value.subcategoryById[route.id]?.name ?: "الدروس"
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
}
