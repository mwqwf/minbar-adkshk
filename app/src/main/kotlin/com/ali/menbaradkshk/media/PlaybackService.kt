package com.ali.menbaradkshk.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.data.ContentRepository
import com.ali.menbaradkshk.data.LocalStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var store: LocalStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null
    private var trackedLessonId = ""
    private var lastTrackedPositionMs = 0L

    override fun onCreate() {
        super.onCreate()
        store = LocalStore.get(this)
        player = ExoPlayer.Builder(this).build().apply {
            setPlaybackSpeed(store.playbackSpeed().toFloat())
            addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        trackedLessonId = mediaItem?.mediaId.orEmpty()
                        lastTrackedPositionMs = currentPosition.coerceAtLeast(0L)
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            // «تشغيل تلقائي للتالي» معطّل → نقف عند نهاية الدرس كما في الأصل.
                            if (!AutoplayState.enabled) pause()
                            // استئناف الدرس التالي من موضعه المحفوظ (نمط playLesson الأصلي).
                            val saved = store.position(trackedLessonId)
                            if (saved > 3_000L) seekTo(saved)
                        }
                        if (trackedLessonId.isNotBlank()) {
                            store.incrementPlayCount(trackedLessonId)
                            store.addRecentPlayed(trackedLessonId)
                            com.ali.menbaradkshk.widget.NowPlayingWidget
                                .refresh(this@PlaybackService)
                            scope.launch {
                                ContentRepository.get(this@PlaybackService)
                                    .incrementView(trackedLessonId)
                            }
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
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                            store.markCompleted(oldId)
                        } else {
                            store.setPosition(oldId, oldPosition.positionMs)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && duration > 0L) {
                            store.setDuration(currentMediaItem?.mediaId.orEmpty(), duration)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            currentMediaItem?.mediaId?.takeIf(String::isNotBlank)
                                ?.let(store::markCompleted)
                        }
                    }
                },
            )
        }
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
            while (isActive) {
                delay(TRACK_INTERVAL_MS)
                if (player.isPlaying) {
                    val current = player.currentPosition.coerceAtLeast(0L)
                    val delta = (current - lastTrackedPositionMs).coerceIn(0L, TRACK_INTERVAL_MS * 2)
                    if (delta > 0L) {
                        store.addListenSeconds(delta / 1_000L)
                        // سلسلة الاستماع تتقدّم بالاستماع الفعلي لا بإتمام الدرس فقط.
                        store.recordDailyListen()
                    }
                    lastTrackedPositionMs = current
                    persistPosition()
                }
            }
        }
    }

    /// استئناف التشغيل بعد موت العملية (زر التشغيل في الودجت/السماعة).
    private val resumptionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val lessonId = store.recentPlayedIds().firstOrNull().orEmpty()
            val lesson = store.lessons().firstOrNull { it.id == lessonId }
            val local = lessonId.takeIf(String::isNotBlank)?.let(store::localAudioPath)
            if (lesson == null || (local == null && lesson.audioUrl.isBlank())) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("لا يوجد درس سابق للاستئناف"),
                )
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(PlaybackController.mediaItemFor(lesson, local)),
                    0,
                    store.position(lessonId),
                ),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    private fun persistPosition() {
        val id = trackedLessonId.takeIf(String::isNotBlank) ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        if (duration > 0L && position >= duration - 3_000L) {
            store.markCompleted(id)
        } else {
            store.setPosition(id, position)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistPosition()
        trackingJob?.cancel()
        mediaSession.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TRACK_INTERVAL_MS = 5_000L
    }
}
