package com.ali.menbaradkshk.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ali.menbaradkshk.data.DownloadRepository
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LocalStore
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUiState(
    val connected: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val speaker: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val sleepEndsAtMs: Long? = null,
    /// 🌙 مؤقّت نوم بالمحتوى: عدد الأطراف الباقية (١ أو ٢)، أو
    /// [com.ali.menbaradkshk.data.LocalStore.SLEEP_UNTIL_QUEUE_END] للتلاوة،
    /// و0 = لا مؤقّت من هذا النوع. مستقلّ عن [sleepEndsAtMs] لأنّه لا موعد له.
    val sleepAfterItems: Int = 0,
    val autoplay: Boolean = true,
    /// «تخطّي الصمت» — مطفأ افتراضياً عمداً: تقصير السكتات يغيّر المسموع في
    /// التلاوة وفي الدروس ذات الوقفات المقصودة، فمن يريده يفتحه بنفسه.
    val skipSilence: Boolean = false,
    val error: String? = null,
    /// موضع العنصر الحالي في قائمة التشغيل — عليه يقوم **تمييز الآية الجارية**
    /// في المصحف: كل آية عنصرٌ مستقلّ، فالفهرس نفسه هو رقم الآية بلا حاجة
    /// إلى أي ملفّ توقيتات.
    val itemIndex: Int = 0,
)

/**
 * «تشغيل تلقائي للتالي» — قيمة جلسة (تعود true عند إعادة تشغيل التطبيق) كما في الأصل.
 *
 * 📚 **ولماذا عدٌّ تنازليّ معلن؟** لأنّ السلاسل هنا كتبٌ لا ملفّات متفرّقة:
 * «شرح الأخضري» ستّون درساً مرقّمة، ومن أنهى السابع عشر يريد الثامن عشر لا
 * قائمةً يبحث فيها. والانتقال كان يقع صامتاً، فلا يفهم المستمع ما جرى ولا
 * يجد سبيلاً إلى منعه. فصار له إعلانٌ خمس ثوانٍ: يراه، ويوقفه إن شاء.
 *
 * [pending] يملؤه [PlaybackService] (هو من يرى الانتقال فعلاً) وتقرؤه شاشة
 * المشغّل. والخدمة والواجهة في العمليّة نفسها — نمط [SkipSilenceState] نفسه.
 */
object AutoplayState {
    @Volatile var enabled: Boolean = true

    /// 👁️ هل واجهة التطبيق ظاهرة الآن؟ تضبطها MainActivity في onStart/onStop.
    /// العدّ التنازلي المعلَن (٥ ثوانٍ صمت) لا معنى له وأحدٌ لا يراه: في
    /// الخلفية يُنتقل إلى الدرس التالي مباشرةً بلا وقفة.
    @Volatile var uiVisible: Boolean = false

    /// [title] عنوان الدرس التالي، [endsAtMs] موعد انطلاقه، و[token] يميّز
    /// هذا العدّ بعينه كي لا يُشغّل مؤقّتٌ متأخّرٌ درساً أُلغي عدّه.
    data class Pending(val title: String, val endsAtMs: Long, val token: Long)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun beginCountdown(title: String, endsAtMs: Long, token: Long) {
        _pending.value = Pending(title, endsAtMs, token)
    }

    /// يُنهي العدّ ويُرجع هل كان [token] هو العدّ الجاري فعلاً — به وحده تعرف
    /// الخدمة أنّ لها أن تُشغّل التالي.
    fun consume(token: Long): Boolean {
        val current = _pending.value ?: return false
        if (current.token != token) return false
        _pending.value = null
        return true
    }

    fun clearCountdown() {
        _pending.value = null
    }
}

/// 💬 قناة أخبار المشغّل الخفيفة (Snackbar) — كائن مشترك بين الخدمة والواجهة
/// (العمليّة واحدة، نمط [AutoplayState] نفسه): الخدمة تكتب «أُكمل التشغيل من
/// التنزيلات» ولو كانت الواجهة ميتة، وتعرضه الواجهة متى حييت.
object PlaybackNoticeState {
    val notice = MutableStateFlow<String?>(null)
}

/// «تخطّي الصمت» — خلافاً لـ[AutoplayState] يبقى بين الجلسات، فله ملفّ تفضيلات
/// صغير مستقلّ (`LocalStore` ليس من ملفّات هذه الميزة).
///
/// **لماذا كائنٌ مشترك لا أمر جلسة عبر `MediaController`؟** لأن `Player` — وهو
/// كلّ ما يملكه المتحكّم — لا يعرّف `setSkipSilenceEnabled` أصلاً؛ هي على
/// `ExoPlayer` وحده، أي داخل [PlaybackService]. والخدمة والواجهة في العملية
/// نفسها (لا `android:process` في البيان)، فبَوْحُ قيمةٍ واحدة أبسط بكثير من
/// `SessionCommand` مخصّص بترميز وفكّ ترميز — وهو نمط [AutoplayState] نفسه.
object SkipSilenceState {
    private const val PREFS = "minbar_playback"
    private const val KEY = "skip_silence"

    @Volatile
    var enabled: Boolean = false
        private set

    /// تسجّلها [PlaybackService] كي يسري التبديل على المشغّل الحيّ فوراً لا عند
    /// الدرس التالي؛ وتُمسح عند إتلاف الخدمة فلا تُلمس مشغّلاً محرَّراً.
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /// القراءة الوحيدة من القرص؛ بعدها كلّ شيء من الذاكرة.
    fun load(context: Context): Boolean {
        enabled = prefs(context).getBoolean(KEY, false)
        return enabled
    }

    fun set(context: Context, value: Boolean) {
        if (enabled == value) return
        enabled = value
        // apply() لا commit(): المفتاح لا يجوز أن ينتظر القرص تحت الإصبع.
        prefs(context).edit().putBoolean(KEY, value).apply()
        onChanged?.invoke()
    }
}

class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val downloads = DownloadRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        PlaybackUiState(
            speed = store.playbackSpeed().toFloat(),
            // مؤقّت نوم قائم من جلسة سابقة يظهر فور إعادة فتح التطبيق.
            sleepEndsAtMs = store.sleepEndsAtMs().takeIf { it > System.currentTimeMillis() },
            // ومؤقّت «إلى نهاية الدرس» كذلك: محفوظ فيظهر بعد إعادة الفتح.
            sleepAfterItems = store.sleepAfterItems(),
            skipSilence = SkipSilenceState.load(appContext),
        ),
    )
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var released = false
    private var pendingPlay: (() -> Unit)? = null
    private var sleepJob: Job? = null
    // آخر طلب تشغيل — ملاذ إعادة البناء حين تُفرَّغ قائمة المشغّل (خدمة قُتلت)
    // فلا ينفع prepare() وحده.
    private var lastLesson: Lesson? = null
    private var lastQueue: List<Lesson> = emptyList()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onPlayerError(error: PlaybackException) {
            // 📴 «الإكمال بلا إنترنت» صار في [PlaybackService.tryOfflineFallback]
            // (الخدمة تعيش بالخلفية وتملك القائمة)؛ إن نجح هناك عاد المشغّل
            // إلى READY فيُمسح هذا الخطأ تلقائياً (انظر onPlaybackStateChanged).
            // بعد أيّ خطأ يعود المشغّل إلى STATE_IDLE؛ نُصفّر مؤشّرات الحالة كي لا
            // تبقى عالقة على «جارٍ التحميل» فتُعطَّل أزرار التشغيل.
            _state.value = _state.value.copy(
                error = messageFor(error),
                playing = false,
                loading = false,
            )
        }

        // أوّل تشغيل ناجح يمسح خطأ المحاولة السابقة كي لا يبقى معلّقاً أبداً.
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) clearError()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) clearError()
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    // قد يكتمل الربط بعد release() — نحرّر فوراً كي لا يتسرّب متحكم حيّ.
                    if (released) {
                        mediaController.release()
                        return@onSuccess
                    }
                    controller = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                    pendingPlay?.invoke()
                    pendingPlay = null
                }.onFailure {
                    // لا مشغّل ⇒ النداء المؤجّل لن يُنفَّذ أبداً؛ نحرّره كي لا
                    // يحتجز الدرس وقائمة التشغيل طوال عمر المتحكّم.
                    pendingPlay = null
                    _state.value = _state.value.copy(error = "تعذّر الاتصال بمشغل الصوت.")
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        scope.launch {
            while (isActive) {
                delay(500L)
                // نبض الموضع لازم أثناء التشغيل/التخزين المؤقّت فقط؛ بقيّة
                // التغيّرات تصل عبر onEvents — فلا داعي لإيقاظ الواجهة كل نصف
                // ثانية والمشغّل متوقّف.
                controller
                    ?.takeIf { it.isPlaying || it.playbackState == Player.STATE_BUFFERING }
                    ?.let(::publish)
            }
        }
        restoreSleepTimer()
    }

    /// يستعيد مؤقّت النوم المحفوظ بعد إتلاف النشاط. الخدمة هي من تضمن الإيقاف
    /// في الموعد فعلاً؛ هذا لتحديث الواجهة وإيقاف فوريّ ما دامت حيّة.
    private fun restoreSleepTimer() {
        val end = store.sleepEndsAtMs()
        if (end <= 0L) return
        if (end <= System.currentTimeMillis()) {
            store.clearSleepTimer()
            return
        }
        _state.value = _state.value.copy(sleepEndsAtMs = end)
        armSleepJob(end)
    }

    private fun armSleepJob(endsAtMs: Long) {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay((endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            controller?.pause()
            store.clearSleepTimer()
            _state.value = _state.value.copy(sleepEndsAtMs = null)
        }
    }

    fun play(lesson: Lesson, queue: List<Lesson>, startAtMs: Long? = null, restart: Boolean = false) {
        val player = controller
        if (player == null) {
            // نداء وصل قبل اكتمال ربط MediaController (إقلاع بارد/رابط عميق) — يُنفَّذ عند الجاهزية.
            if (!released) pendingPlay = { play(lesson, queue, startAtMs, restart) }
            return
        }
        if (!restart && player.currentMediaItem?.mediaId == lesson.id && startAtMs == null) {
            // نفس الدرس ⇒ تبديل تشغيل/إيقاف عبر toggle() لا نداء player.play() مباشرةً،
            // كي تُعاد التهيئة إن كان المشغّل في STATE_IDLE بعد خطأ سابق.
            toggle()
            return
        }
        // الفحص قبل بناء القائمة: ifEmpty تُعيد الدرس نفسه فتُخفي غياب الصوت.
        if (lesson.audioUrl.isBlank() && !downloads.isDownloaded(lesson.id)) {
            _state.value = _state.value.copy(error = "هذا الدرس لا يحتوي ملفاً صوتياً.")
            return
        }
        // الدرس المطلوب يتصدّر القائمة دائماً إن رشّحه المرشّح خارجها، كي لا
        // يسقط الفهرس إلى 0 فيُشغَّل درس آخر بموضع الدرس المطلوب.
        val filtered = queue.filter { it.audioUrl.isNotBlank() || downloads.isDownloaded(it.id) }
        val playable = if (filtered.any { it.id == lesson.id }) filtered else listOf(lesson) + filtered
        val index = playable.indexOfFirst { it.id == lesson.id }.coerceAtLeast(0)
        // نستأنف من الموضع المحفوظ فقط إن تجاوز 3 ثوانٍ (نمط الأصل).
        val position = startAtMs
            ?: store.position(lesson.id).takeIf { it > 3_000L }
            ?: 0L
        lastLesson = lesson
        lastQueue = queue
        // محاولة جديدة ⇒ خطأ المحاولة السابقة لم يعد يمثّل الحالة.
        _state.value = _state.value.copy(error = null)
        player.setMediaItems(playable.map(::toMediaItem), index, position)
        player.prepare()
        player.play()
    }

    /**
     * 🕌 تشغيل تلاوة المصحف — قائمة آيات، كل آية ملفّ مستقلّ.
     *
     * **لماذا هذا التصميم بالذات؟** لأنّه يمنحنا المزامنة الدقيقة مجّاناً:
     * حين تكون كل آية عنصراً في قائمة التشغيل، يصير `currentMediaItemIndex`
     * هو رقم الآية الجاري تلاوتها بالضبط — فلا نحتاج ملفّات توقيتات ولا
     * حسابات تقريبيّة، والانتقال إلى آية بعينها هو `seekTo(index)` لا أكثر.
     * ونرث مع ذلك كلّ ما في الخدمة أصلاً: تشغيل في الخلفية، إشعار تحكّم،
     * أزرار السمّاعة، ومؤقّت النوم.
     *
     * المعرّفات كلّها ببادئة [PlaybackService.QURAN_ID_PREFIX] فلا تختلط
     * تلاوة المصحف بإحصاءات الدروس بحال.
     */
    fun playQuran(items: List<MediaItem>, startIndex: Int = 0) {
        val player = controller
        if (player == null) {
            if (!released) pendingPlay = { playQuran(items, startIndex) }
            return
        }
        if (items.isEmpty()) return
        // المصحف ليس درساً: نُبطل ملاذ إعادة البناء كي لا يُعيد `replayLast`
        // درساً قديماً مكان التلاوة بعد موت الخدمة.
        lastLesson = null
        lastQueue = emptyList()
        _state.value = _state.value.copy(error = null)
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    /** الانتقال إلى آية بعينها داخل التلاوة الجارية. */
    fun seekToItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        prepareIfIdle(player)
        player.seekTo(index, 0L)
        player.play()
    }

    fun toggle() {
        val player = controller
        if (player == null) {
            // لا متحكّم بعد ⇒ نُعيد بناء آخر طلب (يُؤجَّل حتى اكتمال الربط).
            replayLast()
            return
        }
        if (player.isPlaying) {
            player.pause()
            return
        }
        // قائمة فارغة في STATE_IDLE (خدمة قُتلت) ⇒ prepare() لا يجد ما يُهيّئه.
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
            replayLast()
            return
        }
        prepareIfIdle(player)
        player.play()
    }

    /// إعادة التهيئة قبل أيّ استئناف: بعد أيّ خطأ يبقى المشغّل في STATE_IDLE،
    /// و`play()` عليه لا يفعل شيئاً — فتموت أزرار التشغيل إلى الأبد بلا هذا.
    private fun prepareIfIdle(player: Player) {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            _state.value = _state.value.copy(error = null)
            player.prepare()
        }
    }

    /// إعادة بناء قائمة التشغيل من آخر طلب — restart كي لا يعود إلى فرع التبديل.
    private fun replayLast() {
        val lesson = lastLesson ?: return
        play(lesson, lastQueue, restart = true)
    }

    /// إعادة المحاولة بعد فشل التشغيل — يستدعيها شريط الخطأ في الواجهة.
    fun retry() {
        val player = controller
        if (player == null || player.mediaItemCount == 0) {
            replayLast()
            return
        }
        _state.value = _state.value.copy(error = null)
        player.prepare()
        player.play()
    }

    /// 💬 إشعار خفيف من المشغّل للواجهة (Snackbar) — القناة مشتركة في
    /// [PlaybackNoticeState] لأنّ **الخدمة** هي من يُكمل بلا إنترنت الآن
    /// (تعيش في الخلفية بعد موت الواجهة)، والواجهة — إن كانت حيّة — تعرضه.
    val notice: StateFlow<String?> = PlaybackNoticeState.notice.asStateFlow()

    fun consumeNotice() {
        PlaybackNoticeState.notice.value = null
    }

    /// 👁️ تضبطها MainActivity في onStart/onStop — انظر [AutoplayState.uiVisible].
    fun setUiVisible(visible: Boolean) {
        AutoplayState.uiVisible = visible
    }

    /// رسالة عربية مناسبة لسبب الفشل — الشبكة أشيع الأسباب.
    private fun messageFor(error: PlaybackException): String {
        val network = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val missing = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        return when {
            network -> "تعذّر تشغيل الصوت — تحقّق من الاتصال ثم أعد المحاولة."
            missing -> "الملف الصوتي غير متاح الآن. أعد المحاولة لاحقاً."
            else -> "تعذّر تشغيل الصوت. أعد المحاولة."
        }
    }

    fun seekTo(milliseconds: Long) {
        controller?.seekTo(milliseconds.coerceAtLeast(0L))
    }

    fun skipForward() {
        val seconds = store.skipSeconds()
        controller?.let {
            val target = it.currentPosition + seconds * 1_000L
            // المدة غير معروفة أثناء التحميل (سالبة) فلا تصلح سقفاً.
            val cap = it.duration.takeIf { duration -> duration > 0L }
            it.seekTo(if (cap != null) target.coerceAtMost(cap) else target)
        }
    }

    fun skipBackward() {
        val seconds = store.skipSeconds()
        controller?.let { it.seekTo((it.currentPosition - seconds * 1_000L).coerceAtLeast(0L)) }
    }

    fun next() {
        val player = controller ?: return
        prepareIfIdle(player)
        player.seekToNextMediaItem()
    }

    fun previous() {
        val player = controller ?: return
        prepareIfIdle(player)
        player.seekToPreviousMediaItem()
    }

    fun setSpeed(speed: Float) {
        val safe = speed.coerceIn(0.75f, 2f)
        store.setPlaybackSpeed(safe.toDouble())
        controller?.setPlaybackSpeed(safe)
        _state.value = _state.value.copy(speed = safe)
    }

    fun setSleepTimer(minutes: Int) {
        val end = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        // مؤقّتان لا يجتمعان: اختيار الدقائق يلغي «إلى نهاية الدرس» وبالعكس،
        // وإلّا توقّف التشغيل عند أوّلهما فيبدو أنّ الاختيار لم يُحترم.
        store.clearSleepAfterItems()
        // الموعد يُحفظ ليقرأه `PlaybackService`: هذا النطاق يُحرَّر مع
        // `AppViewModel.onCleared`، فكان سحب التطبيق يقتل المؤقّت بينما يستمرّ
        // التشغيل عبر الخدمة الأمامية فيعمل الصوت طوال الليل.
        store.setSleepEndsAtMs(end)
        _state.value = _state.value.copy(sleepEndsAtMs = end, sleepAfterItems = 0)
        armSleepJob(end)
    }

    /**
     * 🌙 «إلى نهاية الدرس» / «إلى نهاية درسين» — [count] عدد الأطراف الباقية.
     *
     * **لا مدّة تُحسب هنا**: لو حسبنا `durationMs - positionMs` مرّةً واحدة
     * لأخطأ المؤقّت هدفه عند أوّل تغيّر — تشغيلٌ تلقائيّ ينقل إلى درسٍ أطول،
     * أو تبديلُ سرعة، أو ترجيعٌ نصف دقيقة. فالمعنى المحفوظ هو الشرط نفسه:
     * «قِف عند طرف الدرس الجاري»، ومن يُنفّذه [PlaybackService] عند كل طرف.
     */
    fun setSleepAfterItems(count: Int) {
        // مدّة قائمة من قبل ⇒ تُلغى: انظر تعليق [setSleepTimer].
        sleepJob?.cancel()
        sleepJob = null
        store.clearSleepTimer()
        store.setSleepAfterItems(count)
        _state.value = _state.value.copy(sleepEndsAtMs = null, sleepAfterItems = count)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        store.clearSleepTimer()
        store.clearSleepAfterItems()
        _state.value = _state.value.copy(sleepEndsAtMs = null, sleepAfterItems = 0)
    }

    fun setAutoplay(enabled: Boolean) {
        AutoplayState.enabled = enabled
        // إطفاؤه والعدّ جارٍ يعني «لا تنتقل» — فيُلغى العدّ معه لا أن يمضي.
        if (!enabled) AutoplayState.clearCountdown()
        _state.value = _state.value.copy(autoplay = enabled)
    }

    /// «إيقاف» في شريط العدّ التنازليّ: لا ينتقل الآن، ويُحترم اختياره بقيّة
    /// الجلسة — فلا يُسأل عند كل درس بعد أن أجاب مرّة.
    fun stopAutoplayCountdown() {
        setAutoplay(false)
    }

    /// «شغّل الآن» — **لا مسار تشغيل ثانٍ**: الدرس التالي محمَّل في المشغّل
    /// أصلاً وينتظر انقضاء العدّ، فكلّ ما يلزم رفعُ اليد عنه.
    fun startNextNow() {
        AutoplayState.clearCountdown()
        val player = controller ?: return replayLast()
        prepareIfIdle(player)
        player.play()
    }

    /// الخدمة هي من تُطبّقه على المشغّل (وتحرس المصحف منه)؛ هنا الحفظ والإبلاغ.
    fun setSkipSilence(enabled: Boolean) {
        SkipSilenceState.set(appContext, enabled)
        _state.value = _state.value.copy(skipSilence = enabled)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun toMediaItem(lesson: Lesson): MediaItem =
        mediaItemFor(lesson, downloads.localPath(lesson.id))

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        _state.value = _state.value.copy(
            connected = true,
            mediaId = player.currentMediaItem?.mediaId.orEmpty(),
            title = metadata.title?.toString().orEmpty(),
            speaker = metadata.artist?.toString().orEmpty(),
            playing = player.isPlaying,
            loading = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            speed = player.playbackParameters.speed,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            autoplay = AutoplayState.enabled,
            // قراءة خريطة تفضيلات في الذاكرة — رخيصة، والخدمة هي من تُنقص
            // العدّاد فلا سبيل آخر لتعرف الواجهة أنّه بلغ الصفر.
            sleepAfterItems = store.sleepAfterItems(),
            skipSilence = SkipSilenceState.enabled,
            itemIndex = player.currentMediaItemIndex.coerceAtLeast(0),
        )
    }

    fun release() {
        released = true
        pendingPlay = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }

    companion object {
        /**
         * 🕌 عنصر تلاوة — معرّفه ببادئة `q:` كي **لا** يدخل إحصاءات الدروس
         * (انظر [PlaybackService.isLesson]).
         */
        fun quranItem(id: String, url: String, title: String, artist: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(PlaybackService.QURAN_ID_PREFIX + id)
                .setUri(Uri.parse(url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()

        /// بناء عنصر التشغيل من الدرس — يستعمله أيضاً استئناف الجلسة في `PlaybackService`.
        fun mediaItemFor(lesson: Lesson, localPath: String?): MediaItem {
            val uri = if (localPath != null) Uri.fromFile(File(localPath)) else Uri.parse(lesson.audioUrl)
            return MediaItem.Builder()
                .setMediaId(lesson.id)
                .setUri(uri)
                // 🔑 مفتاح كاش البثّ = بصمة المحتوى (حين تصل من الفهرس): البايتات
                // المتطابقة تتشارك الكاش مهما تبدّل المضيف أو تكرر الدرس، وتغيّر
                // الصوت (بصمة جديدة) يعزل كاشه تلقائياً. بلا بصمة يبقى الافتراضي
                // (الرابط نصاً) — سلوك النسخ السابقة حرفياً.
                .setCustomCacheKey(lesson.sha256.takeIf { it.isNotBlank() && localPath == null })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(lesson.displayTitle)
                        .setArtist(lesson.speaker.ifBlank { "منبر ادكصهك" })
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }
    }
}
