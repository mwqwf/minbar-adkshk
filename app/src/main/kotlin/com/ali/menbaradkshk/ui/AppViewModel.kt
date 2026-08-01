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
import com.ali.menbaradkshk.data.SubmissionDraft
import com.ali.menbaradkshk.data.SubmissionRepository
import com.ali.menbaradkshk.data.TranscriptRepository
import com.ali.menbaradkshk.media.PlaybackController
import com.ali.menbaradkshk.notification.BackgroundScheduler
import com.ali.menbaradkshk.util.AudioMerger
import com.ali.menbaradkshk.util.AudioTranscodeMerger
import com.ali.menbaradkshk.util.Mp3FormatException
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

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
    data object ContributeTranscript : Route
    data object MySubmissions : Route
    data object Notifications : Route
}

/// صورة/نص وصلا من تطبيق خارجي عبر «المشاركة» لميزة «ساهم بالنص».
data class SharedTranscriptState(
    val preparing: Boolean = false,
    val text: String = "",
    val images: List<Uri> = emptyList(),
    val error: String = "",
)

/// ملفات صوتية وصلت من تطبيق خارجي عبر «المشاركة»، بانتظار شاشة المساهمة.
data class SharedAudioState(
    val preparing: Boolean = false,
    val files: List<PickedFile> = emptyList(),
    val error: String = "",
    /// عدد الملفات التي شاركها المستخدم فعلياً قبل قصّها على حدّ الدمج —
    /// يبقى محفوظاً كي لا يظنّ أن درسه وصل كاملاً حين يتجاوز الحدّ.
    val originalCount: Int = 0,
)

/// حالة رفع المساهمة — تعيش في الـViewModel كي لا يلغيها تدوير الشاشة.
data class ContributionState(
    val submitting: Boolean = false,
    val merging: Boolean = false,
    val progress: Int = 0,
    val error: String = "",
    val done: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val store = LocalStore.get(application)
    val content = ContentRepository.get(application)
    val downloads = DownloadRepository.get(application)
    val submissions = SubmissionRepository.get(application)
    val transcripts = TranscriptRepository.get(application)
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

    /// حالة شاشة «شارك درساً» (دمج/رفع/خطأ/نجاح).
    private val _contribution = MutableStateFlow(ContributionState())
    val contribution: StateFlow<ContributionState> = _contribution.asStateFlow()

    /// ملفات «المشاركة الخارجية» بانتظار أن تستهلكها شاشة المساهمة.
    private val _sharedAudio = MutableStateFlow(SharedAudioState())
    val sharedAudio: StateFlow<SharedAudioState> = _sharedAudio.asStateFlow()

    private val _sharedTranscript = MutableStateFlow(SharedTranscriptState())
    val sharedTranscript: StateFlow<SharedTranscriptState> = _sharedTranscript.asStateFlow()

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

    /// يستبدل المسار الحالي دون لمس مكدّس الرجوع — لاستهلاك معاملات تُنفَّذ
    /// مرة واحدة فقط (مثل `startAtMs` القادم من رابط «لحظة»).
    fun replaceRoute(route: Route) {
        _route.value = route
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

    /// «شارك إلى منبر» من تطبيق خارجي: يفتح نموذج المساهمة فوراً، ثم ينسخ
    /// الملفات الواردة إلى كاش التطبيق. النسخ مقصود: إذن قراءة `content://`
    /// القادم من تطبيق آخر مؤقّت ولا يقبل `takePersistableUriPermission`،
    /// فينتهي مع النيّة وقد يسقط الرفع بعده — النسخة المحليّة تُبقيه سليماً.
    fun receiveSharedAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_route.value != Route.Contribute) open(Route.Contribute)
        _sharedAudio.value = SharedAudioState(preparing = true, originalCount = uris.size)
        // الحدّ الأقصى للدمج يُطبَّق هنا، لكنّ العدد الأصلي يُحفظ ويُبلَّغ صراحةً
        // كي لا يسقط الزائد بصمت ويظنّ المستخدم درسه كاملاً.
        val accepted = uris.take(AudioMerger.maxFiles)
        val overflow = uris.size - accepted.size
        viewModelScope.launch {
            val context = getApplication<Application>()
            val prepared = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared_intake").apply { mkdirs() }
                // نسخ مشاركات سابقة لم تُستعمل تُحذف بعد يوم كي لا يتضخّم الكاش.
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { old ->
                    if (old.lastModified() < cutoff) runCatching { old.delete() }
                }
                accepted.mapNotNull { uri ->
                    runCatching {
                        val name = displayNameOf(context, uri)
                        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(80)
                        val target = File(dir, "${System.nanoTime()}_$safe")
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            target.outputStream().use(input::copyTo)
                        }
                        PickedFile(Uri.fromFile(target), name)
                    }.getOrNull()
                }
            }
            _sharedAudio.value = if (prepared.isEmpty()) {
                SharedAudioState(
                    error = "تعذّرت قراءة الملف المشارَك — اختره من زر اختيار الملفات بالأعلى.",
                    originalCount = uris.size,
                )
            } else {
                SharedAudioState(files = prepared, originalCount = uris.size)
            }
            if (prepared.isNotEmpty()) {
                val unreadable = accepted.size - prepared.size
                val notes = buildList {
                    if (overflow > 0) {
                        add(
                            "شاركتَ ${uris.size} ملفاً والحدّ الأقصى ${AudioMerger.maxFiles} ملفات " +
                                "للدرس الواحد — أُدرجت أول ${accepted.size}، وأرسل البقية في مساهمة أخرى.",
                        )
                    }
                    if (unreadable > 0) {
                        add("تعذّرت قراءة $unreadable من الملفات المشارَكة — أضِفها من زر اختيار الملفات.")
                    }
                }
                if (notes.isNotEmpty()) showMessage(notes.joinToString(" "))
            }
        }
    }

    /// تستدعيها شاشة المساهمة بعد إدراج الملفات الواردة في قائمتها.
    fun consumeSharedAudio() {
        _sharedAudio.value = SharedAudioState()
    }

    /// «شارك إلى منبر» صورةً أو نصاً: يفتح «ساهم بالنص» (باختيار الدرس)
    /// مع الحمولة الواردة. الصور تُنسخ لكاش التطبيق لنفس سبب نسخ الصوتيات
    /// (إذن قراءة content:// الخارجي مؤقّت وينتهي مع النيّة).
    fun receiveSharedTranscript(text: String, imageUris: List<Uri>) {
        if (text.isBlank() && imageUris.isEmpty()) return
        if (_route.value != Route.ContributeTranscript) open(Route.ContributeTranscript)
        val accepted = imageUris.take(TranscriptRepository.MAX_IMAGES)
        if (accepted.isEmpty()) {
            _sharedTranscript.value = SharedTranscriptState(text = text.trim())
            return
        }
        _sharedTranscript.value = SharedTranscriptState(preparing = true, text = text.trim())
        viewModelScope.launch {
            val context = getApplication<Application>()
            val prepared = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared_pages").apply { mkdirs() }
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { old ->
                    if (old.lastModified() < cutoff) runCatching { old.delete() }
                }
                accepted.mapNotNull { uri ->
                    runCatching {
                        val target = File(dir, "${System.nanoTime()}_page.jpg")
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            target.outputStream().use(input::copyTo)
                        }
                        Uri.fromFile(target)
                    }.getOrNull()
                }
            }
            _sharedTranscript.value = if (prepared.isEmpty() && text.isBlank()) {
                SharedTranscriptState(
                    error = "تعذّرت قراءة الصورة المشارَكة — أرفقها من زر الصور داخل النموذج.",
                )
            } else {
                SharedTranscriptState(text = text.trim(), images = prepared)
            }
            if (imageUris.size > accepted.size) {
                showMessage(
                    "شاركتَ ${imageUris.size} صور والحدّ ${TranscriptRepository.MAX_IMAGES} " +
                        "— أُدرجت أول ${accepted.size}.",
                )
            }
        }
    }

    fun consumeSharedTranscript() {
        _sharedTranscript.value = SharedTranscriptState()
    }

    /// يدمج الملفات (عند تعددها) ثم يرفع المساهمة داخل `viewModelScope`،
    /// فيستمر الرفع رغم تدوير الشاشة أو إعادة إنشاء النشاط.
    fun submitContribution(
        files: List<PickedFile>,
        title: String,
        category: com.ali.menbaradkshk.data.Category,
        subcategory: com.ali.menbaradkshk.data.Subcategory,
        submitterName: String,
        note: String,
        // إقرارا الحقوق والضوابط اختياريان: يُنقَلان كما اختارهما المستخدم
        // ليراهما المشرف عند المراجعة، ولا يمنعان الإرسال إطلاقاً.
        rightsConfirmed: Boolean = false,
        contentPolicyAccepted: Boolean = false,
        // «النص المشروح» الاختياري: يُرفع مع المساهمة ويُنشر مع الدرس عند اعتماده.
        transcript: com.ali.menbaradkshk.data.TranscriptExtras =
            com.ali.menbaradkshk.data.TranscriptExtras(),
    ) {
        // لا خروج صامت: كل منع يصل للمستخدم كرسالة تشرح سببه.
        if (_contribution.value.submitting) return
        if (files.isEmpty()) {
            _contribution.value = ContributionState(error = "اختر ملفاً صوتياً أولاً.")
            return
        }
        if (title.isBlank()) {
            _contribution.value = ContributionState(error = "اكتب عنوان الدرس أولاً.")
            return
        }
        _contribution.value = ContributionState(submitting = true)
        viewModelScope.launch {
            val context = getApplication<Application>()
            var mergedTemp: File? = null
            try {
                // ملف واحد يُرفع كما هو؛ أكثر يُدمج محلياً أولاً ثم يُرفع الناتج.
                val (uploadUri, uploadName) = if (files.size == 1) {
                    files.single().uri to files.single().name
                } else {
                    var total = 0L
                    for (file in files) {
                        total += context.contentResolver.openAssetFileDescriptor(file.uri, "r")
                            ?.use { it.length } ?: 0L
                    }
                    if (total > SubmissionRepository.MAX_FILE_BYTES) error("file_too_large")
                    _contribution.value = _contribution.value.copy(merging = true)
                    val timestamp = System.currentTimeMillis()
                    var mergedName = "merged_$timestamp.mp3"
                    mergedTemp = withContext(Dispatchers.IO) {
                        val cache = File(context.cacheDir, "merge_$timestamp").apply { mkdirs() }
                        val locals = files.mapIndexed { index, picked ->
                            // الامتداد الأصلي يبقى (المحوّل يفحص المحتوى لا الاسم).
                            val extension = picked.name.substringAfterLast('.', "bin")
                                .lowercase().take(6)
                            File(cache, "part_$index.$extension").also { target ->
                                context.contentResolver.openInputStream(picked.uri)!!.use { input ->
                                    target.outputStream().use(input::copyTo)
                                }
                            }
                        }
                        // مساران: كل الملفات MP3 → لصق إطارات بلا إعادة ترميز
                        // (سريع وبلا فقد)؛ غير ذلك أو تعذّر اللصق (ترميزات MP3
                        // متنافرة) → فكّ الجميع وإعادة ترميز AAC/M4A — فيصحّ
                        // الدمج **مهما اختلفت الصيغ** والناتج صيغة واحدة دائماً.
                        val allMp3 = files.all { AudioMerger.isMp3(it.name) }
                        val merged = if (allMp3) {
                            try {
                                AudioMerger.mergeMp3(
                                    locals,
                                    File(cache, "merged_$timestamp.mp3").absolutePath,
                                )
                            } catch (_: Mp3FormatException) {
                                mergedName = "merged_$timestamp.m4a"
                                AudioTranscodeMerger.mergeToM4a(
                                    locals,
                                    File(cache, "merged_$timestamp.m4a").absolutePath,
                                )
                            }
                        } else {
                            mergedName = "merged_$timestamp.m4a"
                            AudioTranscodeMerger.mergeToM4a(
                                locals,
                                File(cache, "merged_$timestamp.m4a").absolutePath,
                            )
                        }
                        locals.forEach(File::delete)
                        merged
                    }
                    _contribution.value = _contribution.value.copy(merging = false)
                    Uri.fromFile(mergedTemp) to mergedName
                }
                submissions.submit(
                    SubmissionDraft(
                        audioUri = uploadUri,
                        fileName = uploadName,
                        title = title,
                        category = category,
                        subcategory = subcategory,
                        submitterName = submitterName,
                        note = note,
                        rightsConfirmed = rightsConfirmed,
                        contentPolicyAccepted = contentPolicyAccepted,
                        transcript = transcript,
                    ),
                ) { percent ->
                    _contribution.value = _contribution.value.copy(progress = percent)
                }
                _contribution.value = ContributionState(done = true)
            } catch (failure: Throwable) {
                _contribution.value = ContributionState(
                    error = when {
                        failure is AudioTranscodeMerger.UnsupportedAudioException ->
                            failure.message ?: "تعذّر فكّ أحد الملفات الصوتية."
                        failure is Mp3FormatException ->
                            "تعذّر دمج الملفات — أحدها ليس ملفاً صوتياً سليماً."
                        failure.message?.contains("file_too_large") == true ->
                            "الحجم الكلي أكبر من الحدّ المسموح (100MB)."
                        else -> "تعذّر إرسال المساهمة. تحقق من اتصالك وحاول مجدداً."
                    },
                )
            } finally {
                mergedTemp?.delete()
            }
        }
    }

    fun clearContributionState() {
        _contribution.value = ContributionState()
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
