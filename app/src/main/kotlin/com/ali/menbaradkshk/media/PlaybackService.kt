package com.ali.menbaradkshk.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.MinbarApplication
import com.ali.menbaradkshk.data.ContentRepository
import com.ali.menbaradkshk.data.LocalStore
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var store: LocalStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * ⚠️ خيط واحد للكتابة في المخزن — لا الخيط الرئيسي.
     *
     * كل كتابة في [LocalStore] تفكّ JSON كاملاً وتُعيد تسلسله (خرائط المواضع
     * والثواني اليوميّة تبلغ مئات المداخل)، وحلقة النبض تفعل ذلك **كل خمس
     * ثوانٍ طوال التشغيل** على `Dispatchers.Main.immediate` — أي على مُرسِل
     * الواجهة نفسه، فتتقطّع الحركة ويهتزّ التمرير بلا سبب ظاهر.
     *
     * والخيط **واحد** لا مجمّع `Dispatchers.IO`: الكتابات متتابعة بطبيعتها
     * (موضع ثم موضع)، ولو تسابقت على مجمّع لكتب نبضٌ متأخّر موضعاً أقدم فوق
     * أحدث منه — أي لعاد الدرس إلى الوراء. التسليم على خيط واحد يحفظ الترتيب
     * كما كان على الخيط الرئيسي تماماً.
     */
    private val storeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val storeScope = CoroutineScope(
        SupervisorJob() + storeExecutor.asCoroutineDispatcher(),
    )
    private var trackingJob: Job? = null
    private var sleepJob: Job? = null
    /// مؤقّت الخمس ثوانٍ المعلَن بين درسٍ والذي يليه — انظر [beginAutoplayCountdown].
    private var autoplayJob: Job? = null
    private var trackedLessonId = ""
    private var lastTrackedPositionMs = 0L
    /// استماع فعليّ متراكم للدرس الحالي — أساس احتساب «استُمع إليه».
    private var listenedMs = 0L
    private var playCounted = false
    /// مرجع ثابت لا لامدا جديدة عند كلّ تسجيل: به يتحقّق `onDestroy` أن التسجيل
    /// القائم تسجيلُه هو، فلا تمحو خدمةٌ محتضِرة تسجيلَ خدمةٍ جديدة حلّت محلّها.
    private val skipSilenceApplier: () -> Unit = { applySkipSilence() }

    override fun onCreate() {
        super.onCreate()
        store = LocalStore.get(this)
        SkipSilenceState.load(this)
        // الملفات المحلّية (`file://`) تُقرأ مباشرةً بـ`FileDataSource` عبر
        // `DefaultDataSource`، فلا تدخل الكاش أبداً: «تنزيلاتي» فهرس منفصل.
        val networkSources = CacheDataSource.Factory()
            .setCache(MinbarApplication.mediaCache(this))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent("MinbarAdkassahk/${com.ali.menbaradkshk.BuildConfig.VERSION_NAME}")
                    .setAllowCrossProtocolRedirects(true),
            )
            // فشل الكاش لا يجوز أن يُفشِل التشغيل — نتابع من الشبكة.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this)
            // ⚠️ إلزاميّ: بلا سمات صوت صريحة وطلب البؤرة الصوتيّة يستمرّ الدرس
            // فوق مكالمة أو تطبيق آخر، وتخفض بعض واجهات المصنّعين صوت التطبيق.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // إيقاف تلقائي عند نزع السمّاعة بدل بثّ الدرس على مكبّر الجهاز.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, networkSources)),
            )
            .build()
        player.apply {
            setPlaybackSpeed(store.playbackSpeed().toFloat())
            addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        trackedLessonId = mediaItem?.mediaId.orEmpty()
                        lastTrackedPositionMs = currentPosition.coerceAtLeast(0L)
                        // درس جديد ⇒ عدّاد الاستماع يبدأ من الصفر، والاحتساب
                        // يُلغى إن غادر المستخدم قبل اكتمال المدّة المطلوبة.
                        listenedMs = 0L
                        playCounted = false
                        // العنصر تبدّل ⇒ نوعه قد يتبدّل معه (درس ⇄ آية)، وعليه
                        // وحده يتوقّف سريان «تخطّي الصمت».
                        this@PlaybackService.applySkipSilence()
                        // ⚠️ المصحف مستثنى من هذين السلوكين: قائمته آياتٌ لا
                        // دروس، فالانتقال التلقائي بين الآيات هو التلاوة نفسها.
                        // لولا الاستثناء لتوقّفت التلاوة عند كل آية لمن عطّل
                        // «التشغيل التلقائي للتالي»، ولقفزت الآية إلى موضع
                        // محفوظ لدرسٍ يحمل معرّفاً مشابهاً.
                        // انتقالٌ بيد المستخدم (زرّ التالي/اختيار درس) يُلغي أيّ
                        // عدٍّ تنازليّ جارٍ: هو اختار بنفسه، فلا معنى لعدٍّ بعده.
                        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            autoplayJob?.cancel()
                            AutoplayState.clearCountdown()
                        }
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                            isLesson(trackedLessonId)
                        ) {
                            // 🌙 طرفُ درسٍ وقع فعلاً ⇒ يُنقَص عدّاد «إلى نهاية
                            // الدرس»؛ وإن بلغ الصفر فالنوم أولى من التالي.
                            val sleepNow = consumeSleepItem()
                            // «تشغيل تلقائي للتالي» معطّل → نقف عند نهاية الدرس كما في الأصل.
                            if (!AutoplayState.enabled || sleepNow) {
                                pause()
                            } else {
                                // 📚 وإلّا: توقّفٌ قصير معلَن ثمّ انتقال — كي
                                // يرى المستمع إلى أين يذهب وله أن يمنعه.
                                beginAutoplayCountdown()
                            }
                            // استئناف الدرس التالي من موضعه المحفوظ (نمط playLesson الأصلي).
                            val saved = store.position(trackedLessonId)
                            if (saved > 3_000L) seekTo(saved)
                        }
                        if (trackedLessonId.isNotBlank() && isLesson(trackedLessonId)) {
                            // السجل يقصد الفتح فعلاً فيبقى فوريّاً؛ أمّا عدّاد
                            // الاستماع والمشاهدة فيؤجَّلان إلى استماع فعليّ
                            // (انظر [countPlayIfListenedEnough]) — كانا يُحتسبان
                            // بمجرّد صيرورة الدرس حالياً، وعليهما تُبنى ريلات
                            // «الأكثر استماعاً» و«الرائج» وتحليلات اللوحة كلّها.
                            // ⚠️ الكتابة على خيط المخزن الواحد لا الرئيسي: كل
                            // كتابة تفكّ JSON كاملاً وتعيد تسلسله (جانك)، وكانت
                            // تتسابق مع كتابات نبضة storeScope على المفاتيح
                            // نفسها (اقرأ-عدّل-اكتب من خيطين فيضيع تحديث).
                            val id = trackedLessonId
                            storeScope.launch { store.addRecentPlayed(id) }
                            com.ali.menbaradkshk.widget.NowPlayingWidget
                                .refresh(this@PlaybackService)
                        }
                    }

                    /// موضع الدرس المغادَر يُحفظ من oldPosition لأن المشغّل صار على الجديد.
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        val oldId = oldPosition.mediaItem?.mediaId.orEmpty()
                        if (oldId.isBlank() || oldId == newPosition.mediaItem?.mediaId) return
                        if (!isLesson(oldId)) return
                        // القيم مُلتقطة هنا (خيط رئيسي) والكتابة على خيط المخزن
                        // الواحد — كانت مباشرةً على الرئيسي فتتسابق مع نبضة
                        // الخمس ثوانٍ على KEY_POSITIONS نفسه ويضيع موضع
                        // الدرس المغادَر أو يرتدّ، فوق كلفة JSON على خيط الواجهة.
                        val positionMs = oldPosition.positionMs
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                            storeScope.launch { store.markCompleted(oldId) }
                        } else {
                            storeScope.launch { store.setPosition(oldId, positionMs) }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // القراءة من المشغّل على الرئيسي (شرط ExoPlayer)
                        // والكتابة على خيط المخزن الواحد — انظر تعليق
                        // onPositionDiscontinuity أعلاه.
                        if (playbackState == Player.STATE_READY && duration > 0L) {
                            val lessonDuration = duration
                            currentMediaItem?.mediaId?.takeIf(::isLesson)?.let { id ->
                                storeScope.launch { store.setDuration(id, lessonDuration) }
                            }
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            // 🌙 طرفُ القائمة كلّها: هنا ينتهي «إلى نهاية
                            // التلاوة» في المصحف، وهنا أيضاً يُطوى أيّ عدّاد
                            // نومٍ باقٍ فلا يبقى معلّقاً إلى جلسةٍ قادمة.
                            if (store.sleepAfterItems() != 0) {
                                store.clearSleepAfterItems()
                                pause()
                            }
                            currentMediaItem?.mediaId
                                ?.takeIf { it.isNotBlank() && isLesson(it) }
                                ?.let { id -> storeScope.launch { store.markCompleted(id) } }
                        }
                    }
                },
            )
        }
        // بها يصل تبديل المفتاح إلى مشغّلٍ يعمل فوراً لا عند الدرس التالي. ولا
        // نُسري التفضيل هنا: المشغّل خالٍ للتوّ، وأوّلُ `setMediaItems` يُطلق
        // `onMediaItemTransition` فيُسريه عارفاً نوعَ ما يُشغَّل (درس أم آية).
        SkipSilenceState.onChanged = skipSilenceApplier
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .setCallback(resumptionCallback)
            .build()
        trackingJob = scope.launch {
            var lastTickElapsedMs = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TRACK_INTERVAL_MS)
                // ⏱️ سقفُ الزمن الحقيقي: «تخطّي الصمت» يُقدّم موضع الملف أسرع
                // من الساعة، فلا يُحتسب استماعٌ أكثر من الثواني التي مضت فعلاً.
                val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                val wallMs = (nowElapsedMs - lastTickElapsedMs).coerceAtLeast(0L)
                lastTickElapsedMs = nowElapsedMs
                if (player.isPlaying) {
                    // 📖 قراءة المشغّل تبقى على الخيط الرئيسي (شرط ExoPlayer)،
                    // والكتابات وحدها تنتقل إلى [storeScope] — انظر تعليقه.
                    val current = player.currentPosition.coerceAtLeast(0L)
                    val delta = (current - lastTrackedPositionMs)
                        .coerceIn(0L, TRACK_INTERVAL_MS * 2)
                        .coerceAtMost(wallMs)
                    val counted = delta > 0L &&
                        isLesson(player.currentMediaItem?.mediaId.orEmpty())
                    if (counted) {
                        listenedMs += delta
                        countPlayIfListenedEnough()
                    }
                    lastTrackedPositionMs = current
                    val snapshot = positionSnapshot()
                    storeScope.launch {
                        if (counted) {
                            store.addListenSeconds(delta / 1_000L)
                            // سلسلة الاستماع تتقدّم بالاستماع الفعلي لا بإتمام الدرس فقط.
                            store.recordDailyListen()
                        }
                        snapshot?.let(::applySnapshot)
                    }
                }
            }
        }
        sleepJob = scope.launch {
            // مؤقّت النوم مسؤوليّة الخدمة لا نطاق الواجهة: سحب التطبيق من
            // التطبيقات الحديثة كان يقتل المؤقّت ويترك الصوت يعمل طوال الليل.
            while (isActive) {
                val endsAt = store.sleepEndsAtMs()
                if (endsAt <= 0L) {
                    delay(SLEEP_POLL_MS)
                    continue
                }
                val remaining = endsAt - System.currentTimeMillis()
                if (remaining > 0L) {
                    delay(remaining.coerceAtMost(SLEEP_POLL_MS))
                    continue
                }
                store.clearSleepTimer()
                player.pause()
                persistPosition()
            }
        }
    }

    /**
     * «تخطّي الصمت» — نُسنده إلى `SilenceSkippingAudioProcessor` الذي في سلسلة
     * معالجات `DefaultAudioSink` أصلاً، عبر `ExoPlayer.skipSilenceEnabled`؛
     * فلا معالج صمت مكتوب بيدنا ولا مصنع عارضات مخصّص ولا بايت واحد يُضاف
     * إلى الحزمة. (ولهذا بقي `ExoPlayer.Builder` أعلاه بلا مساس: تفعيل
     * `setEnableFloatOutput` أو `setEnableAudioTrackPlaybackParams` يُخرج
     * الصوت من هذه السلسلة فيموت التخطّي صامتاً.)
     *
     * ⛔ **المصحف مستثنى مهما كان التفضيل**: سكتات التلاوة وقفٌ مقصود ومعنى
     * مسموع، فتقصيرها تغييرٌ لها. القيمة الفعليّة = رغبة المستخدم **و** كون
     * الجاري درساً لا آية — والمعرّف الفارغ (لا شيء في القائمة) يُحسب «ليس
     * درساً» عمداً: الحارس يُغلق عند الشكّ، ثم يفتحه أوّلُ انتقالٍ إلى درس.
     */
    private fun applySkipSilence() {
        val id = player.currentMediaItem?.mediaId.orEmpty()
        val wanted = SkipSilenceState.enabled && id.isNotBlank() && isLesson(id)
        if (player.skipSilenceEnabled != wanted) player.skipSilenceEnabled = wanted
    }

    /**
     * 📚 توقّفٌ معلَن خمس ثوانٍ قبل الدرس التالي.
     *
     * **لماذا الوقفة أصلاً؟** الانتقال كان يقع صامتاً: يسمع المستمع صوتاً
     * جديداً ولا يدري ما هو ولا كيف يمنعه. والوقفة القصيرة تكفي لأن يقرأ
     * «التالي: …» ويضغط «إيقاف» إن أراد — ولا تكفي لأن تُشعره بالانقطاع.
     *
     * ولا مسار تشغيل ثانٍ هنا: القائمة والمشغّل كما هما، وكلّ ما نفعله
     * `pause()` ثم `play()` على العنصر الذي انتقل إليه المشغّل بنفسه.
     */
    private fun beginAutoplayCountdown() {
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        player.pause()
        val token = System.currentTimeMillis()
        AutoplayState.beginCountdown(title, token + AUTOPLAY_DELAY_MS, token)
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            delay(AUTOPLAY_DELAY_MS)
            // `consume` يفشل إن ألغى المستخدم العدّ أو سبقه بـ«شغّل الآن» —
            // فلا يُستأنف تشغيلٌ أوقفه صاحبه.
            if (AutoplayState.consume(token)) player.play()
        }
    }

    /**
     * 🌙 يُنقص عدّاد «إلى نهاية الدرس» عند طرفٍ فعليّ، ويُرجع هل حان الإيقاف.
     *
     * «إلى نهاية التلاوة» ([LocalStore.SLEEP_UNTIL_QUEUE_END]) لا يُنقَص هنا:
     * آية المصحف عنصرٌ مستقلّ، وطرفُها ليس طرف التلاوة — موعده `STATE_ENDED`.
     */
    private fun consumeSleepItem(): Boolean {
        val remaining = store.sleepAfterItems()
        if (remaining <= 0) return false
        if (remaining == 1) {
            store.clearSleepAfterItems()
            return true
        }
        store.setSleepAfterItems(remaining - 1)
        return false
    }

    /// يحتسب الاستماع بعد [COUNT_AFTER_MS] من السماع الفعليّ لا بمجرّد الفتح.
    private fun countPlayIfListenedEnough() {
        val id = trackedLessonId
        if (playCounted || id.isBlank() || !isLesson(id) || listenedMs < COUNT_AFTER_MS) return
        playCounted = true
        // كتابة مخزن ⇒ خارج الخيط الرئيسي كبقيّة كتابات النبضة.
        storeScope.launch { store.incrementPlayCount(id) }
        scope.launch { ContentRepository.get(this@PlaybackService).incrementView(id) }
    }

    /// استئناف التشغيل بعد موت العملية (زر التشغيل في الودجت/السماعة).
    private val resumptionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // ⚠️ `store.lessons()` يفكّ JSON لمئات الدروس، وهذه الدالة تُنادى
            // على الخيط الرئيسي وهي مطالَبةٌ بردٍّ سريع (زرّ التشغيل في الودجت
            // أو السمّاعة بعد موت العمليّة). الحجب هنا كان يعني تجمّداً مرئيّاً،
            // وربّما ANR على جهازٍ بطيء. الردّ **وعدٌ** يُوفى من خيط خلفيّ —
            // وهو ما تسمح به `ListenableFuture` أصلاً؛ النمط نفسه المستعمل مع
            // `goAsync` في `NowPlayingWidget`.
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            storeScope.launch {
                runCatching {
                    val lessonId = store.recentPlayedIds().firstOrNull().orEmpty()
                    val lesson = store.lessons().firstOrNull { it.id == lessonId }
                    val local = lessonId.takeIf(String::isNotBlank)?.let(store::localAudioPath)
                    if (lesson == null || (local == null && lesson.audioUrl.isBlank())) {
                        future.setException(
                            UnsupportedOperationException("لا يوجد درس سابق للاستئناف"),
                        )
                    } else {
                        future.set(
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(PlaybackController.mediaItemFor(lesson, local)),
                                0,
                                store.position(lessonId),
                            ),
                        )
                    }
                }.onFailure { failure -> future.setException(failure) }
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    /// لقطة موضعٍ تُلتقط من المشغّل (خيط رئيسي) لتُكتب لاحقاً في خيط آخر.
    private data class PositionSnapshot(val id: String, val position: Long, val duration: Long)

    /// القراءة وحدها — تُنادى على الخيط الرئيسي حصراً (ExoPlayer يشترطه).
    private fun positionSnapshot(): PositionSnapshot? {
        val id = trackedLessonId.takeIf { it.isNotBlank() && isLesson(it) } ?: return null
        return PositionSnapshot(
            id,
            player.currentPosition.coerceAtLeast(0L),
            player.duration.takeIf { it > 0L } ?: 0L,
        )
    }

    /// الكتابة وحدها — لا تلمس المشغّل، فتصلح لأي خيط.
    private fun applySnapshot(snapshot: PositionSnapshot) {
        if (snapshot.duration > 0L && snapshot.position >= snapshot.duration - 3_000L) {
            store.markCompleted(snapshot.id)
        } else {
            store.setPosition(snapshot.id, snapshot.position)
        }
    }

    /// حفظ فوريّ متزامن — للحظات الموت (`onTaskRemoved`/`onDestroy`) حيث لا
    /// يبقى وقتٌ لخيطٍ آخر أن يُنهي عمله قبل أن تُقتل العمليّة.
    private fun persistPosition() {
        positionSnapshot()?.let(::applySnapshot)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // إلغاء التسجيل قبل كل شيء: تبديلٌ يصل بعد release() يلمس مشغّلاً محرَّراً.
        if (SkipSilenceState.onChanged === skipSilenceApplier) SkipSilenceState.onChanged = null
        persistPosition()
        trackingJob?.cancel()
        sleepJob?.cancel()
        // عدٌّ معلّق بلا خدمة = شريطٌ في الواجهة لن ينطلق أبداً.
        autoplayJob?.cancel()
        AutoplayState.clearCountdown()
        mediaSession.release()
        player.release()
        scope.cancel()
        // ⚠️ الإلغاء يقطع الكتابات المؤجَّلة، ولذلك حُفظ الموضع أعلاه متزامناً
        // قبله. وإغلاق الخيط واجبٌ وإلا بقي حيّاً بعد موت الخدمة.
        storeScope.cancel()
        storeExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        /**
         * 🕌 بادئة معرّفات آيات المصحف في قائمة التشغيل.
         *
         * المصحف يتشارك المشغّل نفسه مع الدروس (فيربح التشغيل في الخلفية
         * وإشعار التحكّم والسمّاعات بلا شيفرة جديدة)، لكنّه **يجب ألّا يدخل
         * إحصاءات الدروس بحال**: لو دخل لامتلأت «تابع الاستماع» و«الأكثر
         * استماعاً» و«حصادك» بآيات، ولأُرسِلت مشاهدات وهميّة لدروس غير
         * موجودة. فكل كتابة إلى المخزن مشروطة بـ[isLesson].
         */
        const val QURAN_ID_PREFIX = "q:"

        /** هل هذا المعرّف درساً حقيقياً (لا آية مصحف)؟ */
        fun isLesson(mediaId: String): Boolean = !mediaId.startsWith(QURAN_ID_PREFIX)

        private const val TRACK_INTERVAL_MS = 5_000L
        /// دورة فحص مؤقّت النوم حين لا يكون قريباً؛ عند اقترابه ننام مدّته بالضبط.
        private const val SLEEP_POLL_MS = 15_000L
        /// 30 ثانية استماع فعليّ قبل احتساب الدرس «مسموعاً».
        private const val COUNT_AFTER_MS = 30_000L
        /// خمس ثوانٍ معلَنة قبل الدرس التالي — انظر [beginAutoplayCountdown].
        private const val AUTOPLAY_DELAY_MS = 5_000L
    }
}
