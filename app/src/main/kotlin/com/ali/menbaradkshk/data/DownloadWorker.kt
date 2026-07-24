package com.ali.menbaradkshk.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/// مسار التحميل على أندرويد 13 وما دون: عمل خلفية عادي عبر WorkManager.
/// لا يستخدم أي خدمة أمامية، ويعرض إشعار تقدّم عادياً، ويستأنف الملف الجزئي.
class LessonDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (DownloadQueueProcessor(applicationContext).run()) {
            DownloadRunResult.FINISHED -> Result.success()
            DownloadRunResult.NEEDS_RETRY -> Result.retry()
        }
}

object DownloadScheduler {
    private const val WORK_NAME = "lesson_downloads"
    private const val JOB_ID = 4210

    /// يشغّل معالجة الطابور بالمسار المناسب لنسخة النظام:
    /// أندرويد 14+ → وظيفة نقل بيانات بمبادرة المستخدم (بلا خدمة أمامية)،
    /// وما دونه → عمل خلفية عادي. يسقط تلقائياً إلى WorkManager إن تعذّرت
    /// جدولة وظيفة UIDT (مثلاً حين يُستدعى والتطبيق في الخلفية).
    fun enqueue(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (scheduleUserInitiated(context)) return
        }
        enqueueBackgroundWork(context)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiated(context: Context): Boolean = runCatching {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, LessonDownloadJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            // تقدير تقريبي (≈8 ميغابايت لكل درس) يساعد النظام على الجدولة
            // وعرض حجم النقل للمستخدم؛ لا يؤثر على صحة التحميل.
            .setEstimatedNetworkBytes(
                LocalStore.get(context).downloadQueue().size.coerceAtLeast(1) * 8L * 1_024 * 1_024,
                0L,
            )
            .build()
        scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    private fun enqueueBackgroundWork(context: Context) {
        val request = OneTimeWorkRequestBuilder<LessonDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
