package com.ali.menbaradkshk.data

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// وظيفة «نقل بيانات بمبادرة المستخدم» (User-Initiated Data Transfer) —
/// المسار المعتمد من أندرويد 14+ لتنزيل يبدأه المستخدم. يستمر مع إغلاق
/// الشاشة والخروج من التطبيق، ويعرض النظام إشعاره الخاص، **دون** الحاجة
/// إلى إذن FOREGROUND_SERVICE_DATA_SYNC إطلاقاً.
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class LessonDownloadJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val processor = DownloadQueueProcessor(applicationContext)
        // النظام يشترط إشعاراً ظاهراً طوال عمل وظيفة النقل.
        runCatching {
            setNotification(
                params,
                DownloadQueueProcessor.NOTIFICATION_ID,
                processor.build("جارٍ تجهيز التحميل…", ongoing = true),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        work = scope.launch {
            // الرفع من **داخل** الكوروتين: لو أُلغي قبل أن يبدأ (إغلاق الخدمة)
            // لبقيت الراية مرفوعة أبداً فلا تُجدوَل أي تحميل بعدها.
            running.incrementAndGet()
            val result = try {
                processor.run { notification ->
                    runCatching {
                        setNotification(
                            params,
                            DownloadQueueProcessor.NOTIFICATION_ID,
                            notification,
                            JOB_END_NOTIFICATION_POLICY_REMOVE,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // كان runCatching يبتلع أي انهيار داخلي ويعيد الجدولة إلى الأبد
                // بلا أي سجل — الآن يُسجَّل السبب على الأقل قبل إعادة المحاولة.
                android.util.Log.e("LessonDownloadJob", "download run crashed", e)
                DownloadRunResult.NEEDS_RETRY
            } finally {
                // يُخفَّض **قبل** `jobFinished` وقبل انتهاء الكوروتين: القفل
                // داخل المعالج يُحرَّر أوّلاً (finally أعمق)، فلا تبقى الراية
                // مرفوعة على وظيفة لم تعد تعمل ولا تُخفض قبل تحرّر القفل.
                running.decrementAndGet()
            }
            // الوظيفة أُلغيت من onStopJob: النظام تولّى إعادة الجدولة بنفسه،
            // وإعلان الانتهاء بعدها يخصّ وظيفة لم تعد قائمة.
            if (!isActive) return@launch
            jobFinished(params, result == DownloadRunResult.NEEDS_RETRY)
            if (result == DownloadRunResult.FINISHED) rescheduleLeftover()
        }
        return true
    }

    /// ⛔ سباقٌ ضيّق لا يجوز إهماله: عنصر يُضاف بين آخر قراءةٍ للطابور وخفض
    /// راية «تعمل الآن» كان يُسقَط بصمت — لأنّ [DownloadScheduler] ينسحب ما
    /// دامت الوظيفة تعمل كي لا يقتلها. ما بقي في الطابور غير موقوفٍ يستحقّ
    /// جولةً جديدة، وقيد الشبكة يُحسب من جديد فلا دوران على شبكة محدودة.
    private fun rescheduleLeftover() {
        if (DownloadRepository.get(applicationContext).paused.value) return
        if (LocalStore.get(applicationContext).downloadQueue().isEmpty()) return
        DownloadScheduler.enqueue(applicationContext)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // أعِد الجدولة: الطابور محفوظ والملف الجزئي يُستأنف من موضعه.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val running = java.util.concurrent.atomic.AtomicInteger(0)

        /// هل وظيفة النقل تعمل الآن؟ جدولة المعرّف نفسه توقف الجارية
        /// وتستبدلها، فيُسأل عنها قبل أيّ إعادة جدولة (انظر DownloadScheduler).
        val isRunning: Boolean get() = running.get() > 0
    }
}
