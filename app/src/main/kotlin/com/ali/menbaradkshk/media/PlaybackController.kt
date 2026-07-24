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
    val autoplay: Boolean = true,
    val error: String? = null,
)

/// «تشغيل تلقائي للتالي» — قيمة جلسة (تعود true عند إعادة تشغيل التطبيق) كما في الأصل.
object AutoplayState {
    @Volatile var enabled: Boolean = true
}

class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val downloads = DownloadRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackUiState(speed = store.playbackSpeed().toFloat()))
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var sleepJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = "تعذّر تشغيل الصوت. تحقق من الاتصال ثم أعد المحاولة.")
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                }.onFailure {
                    _state.value = _state.value.copy(error = "تعذّر الاتصال بمشغل الصوت.")
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        scope.launch {
            while (isActive) {
                delay(500L)
                controller?.let(::publish)
            }
        }
    }

    fun play(lesson: Lesson, queue: List<Lesson>, startAtMs: Long? = null, restart: Boolean = false) {
        val player = controller ?: return
        if (!restart && player.currentMediaItem?.mediaId == lesson.id && startAtMs == null) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        val playable = queue.filter { it.audioUrl.isNotBlank() || downloads.isDownloaded(it.id) }
            .ifEmpty { listOf(lesson) }
        val index = playable.indexOfFirst { it.id == lesson.id }.coerceAtLeast(0)
        // نستأنف من الموضع المحفوظ فقط إن تجاوز 3 ثوانٍ (نمط الأصل).
        val position = startAtMs
            ?: store.position(lesson.id).takeIf { it > 3_000L }
            ?: 0L
        player.setMediaItems(playable.map(::toMediaItem), index, position)
        player.prepare()
        player.play()
    }

    fun toggle() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(milliseconds: Long) {
        controller?.seekTo(milliseconds.coerceAtLeast(0L))
    }

    fun skipForward() {
        val seconds = store.skipSeconds()
        controller?.let { it.seekTo((it.currentPosition + seconds * 1_000L).coerceAtMost(it.duration)) }
    }

    fun skipBackward() {
        val seconds = store.skipSeconds()
        controller?.let { it.seekTo((it.currentPosition - seconds * 1_000L).coerceAtLeast(0L)) }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()

    fun setSpeed(speed: Float) {
        val safe = speed.coerceIn(0.75f, 2f)
        store.setPlaybackSpeed(safe.toDouble())
        controller?.setPlaybackSpeed(safe)
        _state.value = _state.value.copy(speed = safe)
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val end = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        _state.value = _state.value.copy(sleepEndsAtMs = end)
        sleepJob = scope.launch {
            delay((end - System.currentTimeMillis()).coerceAtLeast(0L))
            controller?.pause()
            _state.value = _state.value.copy(sleepEndsAtMs = null)
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _state.value = _state.value.copy(sleepEndsAtMs = null)
    }

    fun setAutoplay(enabled: Boolean) {
        AutoplayState.enabled = enabled
        _state.value = _state.value.copy(autoplay = enabled)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun toMediaItem(lesson: Lesson): MediaItem {
        val local = downloads.localPath(lesson.id)
        val uri = if (local != null) Uri.fromFile(File(local)) else Uri.parse(lesson.audioUrl)
        return MediaItem.Builder()
            .setMediaId(lesson.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(lesson.displayTitle)
                    .setArtist(lesson.speaker.ifBlank { "منبر ادكصهك" })
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

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
        )
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }
}
