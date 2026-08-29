package com.ali.menbaradkshk.data

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.menbaradkshk.notification.NotificationChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * ⬇️ عامل تنزيل المصحف (صور الصفحات أو التلاوة الكاملة) على WorkManager.
 *
 * كان التنفيذ في `viewModelScope` فيموت بإغلاق التطبيق — وتنزيلُ مئات
 * الميغابايتات على إنترنت ضعيف لا يكتمل في جلسة واحدة أبداً. العامل:
 *  - يصمد لإغلاق التطبيق ويعمل بقيد شبكة متصلة (أو واي فاي فقط عند الطلب)؛
 *  - يستدعي منطق [QuranDownloadRepository] **نفسه**، فتحديث حالة الواجهة
 *    يمرّ عبر تدفّقات المستودع كما كان (العمليّة واحدة)؛
 *  - ويستأنف الأجزاء: المنزَّل كاملاً يُتخطّى، والجزئي `.part` يُكمل من موضعه.
 */
class QuranDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val repo = QuranDownloadRepository.get(applicationContext)

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val riwayaId = inputData.getString(KEY_RIWAYA).orEmpty()
        return try {
            coroutineScope {
                // إشعار تقدّم بسيط يقرأ آخر قيمة كل ثانية — لا مع كل صفحة/آية.
                val notifier = launch {
                    while (true) {
                        val text = when (type) {
                            TYPE_PAGES -> repo.pageProgress.value
                                ?.let { "صفحة ${it.done} من ${it.total}" }
                            else -> repo.progress.value
                                ?.let { "سورة ${it.surah} • ${it.done} من ${it.total}" }
                        }
                        if (text != null) notify(build(text, ongoing = true))
                        delay(1_000L)
                    }
                }
                try {
                    when (type) {
                        TYPE_PAGES -> repo.downloadMushafPages(riwayaId)
                        else -> downloadWholeAudio(riwayaId, inputData.getString(KEY_RECITER).orEmpty())
                    }
                } finally {
                    notifier.cancel()
                }
            }
            showDone(
                if (type == TYPE_PAGES) "اكتمل تنزيل صور المصحف — يعمل بلا إنترنت."
                else "اكتمل تنزيل تلاوة المصحف — تعمل بلا إنترنت.",
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            // إيقاف من المستخدم أو النظام: ما نُزّل محفوظ ويُستأنف لاحقاً.
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
            throw cancelled
        } catch (failure: Exception) {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                showDone("انقطع تنزيل المصحف — افتح شاشة المصحف واضغط التنزيل ليُكمل من حيث توقّف.")
                Result.failure()
            }
        }
    }

    /// تلاوة المصحف كاملاً سورةً بعد سورة — المنطق المنقول من AppViewModel،
    /// بتخطّي المنزَّل فيصلح للاستئناف بعد أي انقطاع.
    private suspend fun downloadWholeAudio(riwayaId: String, reciterId: String) {
        val index = QuranRepository.get(applicationContext).index()
        val riwaya = index.riwaya(riwayaId)
        val reciter = riwaya.reciters.firstOrNull { it.id == reciterId } ?: return
        for (surah in index.surahs) {
            if (repo.isSurahDownloaded(reciter, surah.number, surah.ayahs)) continue
            repo.downloadSurah(reciter, surah.number, surah.ayahs)
        }
    }

    /// للعمل المعجَّل على أندرويد ١١ وما دون (يُشغَّل كخدمة أمامية قصيرة).
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, build("جارٍ تنزيل المصحف…", ongoing = true))

    private fun build(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(applicationContext, NotificationChannels.DOWNLOADS)
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done,
            )
            .setContentTitle("تنزيل المصحف")
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun notify(notification: Notification) {
        if (!canNotify()) return
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun showDone(text: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.cancel(NOTIFICATION_ID)
        if (!canNotify()) return
        runCatching { manager.notify(NOTIFICATION_ID + 1, build(text, ongoing = false)) }
    }

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_RIWAYA = "riwaya"
        const val KEY_RECITER = "reciter"
        const val TYPE_PAGES = "pages"
        const val TYPE_AUDIO = "audio"
        const val NOTIFICATION_ID = 94
        private const val MAX_ATTEMPTS = 5
    }
}

/// جدولة تنزيلات المصحف — عمل واحد في كل مرّة (اسم فريد واحد)، وهو ما تفرضه
/// الواجهة أصلاً برفض تنزيل ثانٍ ما دام واحد جارياً.
object QuranDownloadScheduler {
    private const val WORK_NAME = "quran_download"

    fun enqueuePages(context: Context, riwayaId: String, wifiOnly: Boolean) =
        enqueue(context, QuranDownloadWorker.TYPE_PAGES, riwayaId, "", wifiOnly)

    fun enqueueAudio(context: Context, riwayaId: String, reciterId: String, wifiOnly: Boolean) =
        enqueue(context, QuranDownloadWorker.TYPE_AUDIO, riwayaId, reciterId, wifiOnly)

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(
        context: Context,
        type: String,
        riwayaId: String,
        reciterId: String,
        wifiOnly: Boolean,
    ) {
        val request = OneTimeWorkRequestBuilder<QuranDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(QuranDownloadWorker.KEY_TYPE, type)
                    .putString(QuranDownloadWorker.KEY_RIWAYA, riwayaId)
                    .putString(QuranDownloadWorker.KEY_RECITER, reciterId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .build(),
            )
            // معجَّل إن سمحت الحصّة (المستخدم ضغط الزر للتوّ وينتظر البدء)،
            // وإلا فعمل عاديّ — لا فشل.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        // REPLACE لا KEEP: ضغطة جديدة بعد انقطاعٍ مؤجَّلٍ بمهلة تراجعيّة
        // تعني «استأنف الآن» — وKEEP كانت ستُسقطها بصمت.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
