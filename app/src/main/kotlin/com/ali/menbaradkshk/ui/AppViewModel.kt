package com.ali.menbaradkshk.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ali.menbaradkshk.data.ContentRepository
import com.ali.menbaradkshk.data.DownloadRepository
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.data.NotificationItem
import com.ali.menbaradkshk.data.NotificationsRepository
import com.ali.menbaradkshk.data.SubmissionRepository
import com.ali.menbaradkshk.media.PlaybackController
import com.ali.menbaradkshk.notification.BackgroundScheduler
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface Route {
    // تبويبات الشريط السفلي الخمسة.
    data object Home : Route
    data object Library : Route
    data object MyLists : Route
    data object Downloads : Route
    data object Favorites : Route

    // شاشات تُفتح فوق التبويبات.
    data class Category(val id: String) : Route
    data class Subcategory(val id: String) : Route
    data class Lesson(val id: String, val startAtMs: Long? = null) : Route
    data class Search(val initial: String = "") : Route
    data class Playlist(val id: String) : Route
    data object Radio : Route
    data object Car : Route
    data object Stats : Route
    data object Contribute : Route
    data object MySubmissions : Route
    data object Notifications : Route
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val store = LocalStore.get(application)
    val content = ContentRepository.get(application)
    val downloads = DownloadRepository.get(application)
    val submissions = SubmissionRepository.get(application)
    val playback = PlaybackController(application)
    private val notificationsRepository = NotificationsRepository(submissions)

    private val backStack = mutableListOf<Route>()
    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route.asStateFlow()

    /// بثّ الإشعارات الحيّ (عام + خاص + قرارات المساهمات) — يغذّي الجرس والشاشة.
    val notifications: StateFlow<List<NotificationItem>> = notificationsRepository.stream()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /// ورقة الإعدادات السفلية (زر ⋮ في الشريط العلوي — نمط الأصل).
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    /// حالة طابور التحميل الخلفي (يحدّثها عامل WorkManager) — تظهر في
    /// شاشة الدروس وصفحة التنزيلات معاً.
    val bulkDownload: StateFlow<com.ali.menbaradkshk.data.DownloadQueueState?> =
        downloads.queueState

    /// يضيف الدروس إلى طابور التحميل الخلفي: يستمر مع التنقل داخل التطبيق،
    /// وإغلاق الشاشة، والخروج من التطبيق، ويستأنف تلقائياً عند عودة الاتصال.
    fun downloadLessons(label: String, lessons: List<com.ali.menbaradkshk.data.Lesson>) {
        val pending = lessons.filter { it.audioUrl.isNotBlank() && !downloads.isDownloaded(it.id) }
        if (pending.isEmpty()) {
            showMessage("كل دروس هذا القسم محمّلة بالفعل.")
            return
        }
        store.addToDownloadQueue(pending.map { it.id }, label)
        com.ali.menbaradkshk.data.DownloadScheduler.enqueue(getApplication())
        showMessage(
            "أُضيف ${pending.size} درساً إلى التحميل — يستمر في الخلفية حتى مع إغلاق التطبيق.",
        )
    }

    init {
        refresh(false)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch { content.refresh(force) }
    }

    fun open(route: Route) {
        if (_route.value == route) return
        backStack += _route.value
        _route.value = route
        when (route) {
            Route.Notifications -> store.setNotificationLastSeenMs(System.currentTimeMillis())
            Route.MySubmissions -> store.setSubmissionsLastSeenMs(System.currentTimeMillis())
            is Route.Category -> store.incrementCategoryVisit(route.id)
            is Route.Subcategory -> store.incrementSubcategoryVisit(route.id)
            else -> Unit
        }
    }

    /// يفتح المشغّل بقائمة التشغيل المعطاة ويبدأ التشغيل إن لم يكن الدرس فعّالاً
    /// (نمط PlayerScreen الأصلي: setPlaylist + playLesson عند الحاجة فقط).
    fun openPlayer(lesson: com.ali.menbaradkshk.data.Lesson, playlist: List<com.ali.menbaradkshk.data.Lesson>) {
        if (playback.state.value.mediaId != lesson.id) {
            playback.play(lesson, playlist.ifEmpty { listOf(lesson) })
        }
        open(Route.Lesson(lesson.id))
    }

    fun openRoot(route: Route) {
        backStack.clear()
        _route.value = route
    }

    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        _route.value = previous
        return true
    }

    fun openSettings() {
        _showSettings.value = true
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    fun handleDeepLink(uri: Uri?) {
        if (uri == null) return
        if (uri.host == "my-submissions" || uri.path?.contains("my-submissions") == true) {
            open(Route.MySubmissions)
            return
        }
        val segments = uri.pathSegments
        val lessonIndex = segments.indexOf("lesson")
        if (lessonIndex >= 0 && segments.size > lessonIndex + 1) {
            val id = segments[lessonIndex + 1]
            val seconds = uri.getQueryParameter("t")?.toLongOrNull()
            viewModelScope.launch {
                content.refresh(false)
                open(Route.Lesson(id, seconds?.times(1_000L)))
            }
        }
    }

    fun toggleFavorite(id: String) {
        store.toggleFavorite(id)
        content.refreshPersonalization()
    }

    fun toggleFollow(id: String) {
        val wasFollowing = store.isFollowingSubcategory(id)
        store.toggleFollowSubcategory(id)
        viewModelScope.launch {
            runCatching {
                if (wasFollowing) {
                    FirebaseMessaging.getInstance().unsubscribeFromTopic("sec_$id").await()
                } else if (store.notificationsEnabled()) {
                    FirebaseMessaging.getInstance().subscribeToTopic("sec_$id").await()
                }
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        store.setNotificationsEnabled(enabled)
        BackgroundScheduler.scheduleAll(getApplication())
        viewModelScope.launch {
            runCatching {
                if (enabled) {
                    FirebaseMessaging.getInstance().subscribeToTopic("content").await()
                    store.followedSubcategories().forEach {
                        FirebaseMessaging.getInstance().subscribeToTopic("sec_$it").await()
                    }
                } else {
                    FirebaseMessaging.getInstance().unsubscribeFromTopic("content").await()
                    store.followedSubcategories().forEach {
                        FirebaseMessaging.getInstance().unsubscribeFromTopic("sec_$it").await()
                    }
                }
            }.onFailure { store.setNotificationsEnabled(!enabled) }
        }
    }

    fun setContinueReminderEnabled(enabled: Boolean) {
        store.setContinueReminderEnabled(enabled)
        BackgroundScheduler.scheduleContinue(getApplication())
    }

    fun setWardTime(hour: Int, minute: Int) {
        store.setWardTime(hour, minute)
        BackgroundScheduler.scheduleWard(getApplication())
    }

    fun disableWard() {
        store.disableWard()
        BackgroundScheduler.scheduleWard(getApplication())
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        store.setAutoDownloadEnabled(enabled)
        BackgroundScheduler.scheduleAutoDownload(getApplication())
    }

    fun setAutoDownloadTarget(target: String?) {
        store.setAutoDownloadTarget(target)
        BackgroundScheduler.scheduleAutoDownload(getApplication())
    }

    fun setAutoDownloadWifiOnly(enabled: Boolean) {
        store.setAutoDownloadWifiOnly(enabled)
        BackgroundScheduler.scheduleAutoDownload(getApplication())
    }

    fun showMessage(value: String) {
        _message.value = value
    }

    fun consumeMessage() {
        _message.value = null
    }

    suspend fun deleteMyData() {
        submissions.deleteCloudIdentityData()
        downloads.deleteAll()
        store.clearPersonalData()
        content.refreshPersonalization()
        _message.value = "حُذفت بياناتك بنجاح."
    }

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }
}
