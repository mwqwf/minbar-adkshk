package com.ali.menbaradkshk.data

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.notification.NotificationChannels

/// نتيجة معالجة طابور التحميل.
enum class DownloadRunResult { FINISHED, NEEDS_RETRY }

/// منطق تحميل طابور الدروس، مشترك بين مسارَي التشغيل:
/// وظيفة «نقل بيانات بمبادرة المستخدم» (أندرويد 14+) وعمل الخلفية العادي.
/// يعالج الطابور تسلسلياً، ويحدّث إشعار التقدّم، ويطلب إعادة المحاولة عند
/// انقطاع الشبكة مع إبقاء الملف الجزئي ليُستأنف من موضعه.
class DownloadQueueProcessor(private val context: Context) {

    private val store = LocalStore.get(context)
    private val downloads = DownloadRepository.get(context)
    private val content = ContentRepository.get(context)

    /// [onProgressNotification] يسمح لمُشغّل UIDT بتمرير الإشعار إلى النظام.
    suspend fun run(onProgressNotification: (Notification) -> Unit = {}): DownloadRunResult {
        var queue = store.downloadQueue()
        if (queue.isEmpty()) {
            downloads.queueState.value = null
            return DownloadRunResult.FINISHED
        }
        notify("جارٍ تجهيز التحميل…", onProgressNotification)

        var failures = 0
        while (queue.isNotEmpty()) {
            val id = queue.first()
            val lesson = content.state.value.lessonById[id]
            if (lesson == null || lesson.audioUrl.isBlank() || downloads.isDownloaded(id)) {
                store.removeFromDownloadQueue(id)
                queue = store.downloadQueue()
                continue
            }
            val label = store.downloadQueueLabel()
            val total = store.downloadQueueTotal().coerceAtLeast(1)
            val done = (total - queue.size).coerceAtLeast(0)
            downloads.queueState.value = DownloadQueueState(label, done, total, lesson.displayTitle)
            notify("($done/$total) ${lesson.displayTitle}", onProgressNotification)

            try {
                downloads.download(lesson)
                store.removeFromDownloadQueue(id)
            } catch (retryable: RetryableDownloadException) {
                // انقطاع اتصال: يبقى الدرس في الطابور ويُستأنف الملف الجزئي لاحقاً.
                downloads.queueState.value = DownloadQueueState(
                    label,
                    done,
                    total,
                    lesson.displayTitle,
                    waitingForNetwork = true,
                )
                notify("بانتظار عودة الاتصال للاستئناف…", onProgressNotification)
                return DownloadRunResult.NEEDS_RETRY
            } catch (permanent: Throwable) {
                failures++
                store.removeFromDownloadQueue(id)
            }
            queue = store.downloadQueue()
        }

        downloads.queueState.value = null
        store.clearDownloadQueueIfEmpty()
        showDone(
            if (failures == 0) "اكتمل تحميل الدروس للاستماع دون إنترنت."
            else "اكتمل التحميل مع تعذّر $failures درساً.",
        )
        return DownloadRunResult.FINISHED
    }

    private fun notify(text: String, onProgressNotification: (Notification) -> Unit) {
        val notification = build(text, ongoing = true)
        onProgressNotification(notification)
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun showDone(text: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NOTIFICATION_ID)
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { manager.notify(NOTIFICATION_ID + 1, build(text, ongoing = false)) }
        }
    }

    fun build(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(context, NotificationChannels.DOWNLOADS)
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done,
            )
            .setContentTitle("تحميل الدروس")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    91,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        const val NOTIFICATION_ID = 90
    }
}
