package com.ali.menbaradkshk.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.R
import com.ali.menbaradkshk.data.AdhkarReminders
import com.ali.menbaradkshk.data.ContentRepository
import com.ali.menbaradkshk.data.DownloadRepository
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.util.quranPagesLabel
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ContinueReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.notificationsEnabled() || !store.continueReminderEnabled()) return Result.success()
        val completed = store.completedIds().toSet()
        // آخر درس استمع إليه ولم يكمله — لا صاحب أكبر موضع بالمللي ثانية:
        // ذاك كان يقترح أطول درس متوقَّف فيه نفسه كل يوم إلى الأبد.
        val positions = store.positions()
        val candidate = store.recentPlayedIds()
            .firstOrNull { it !in completed && (positions[it] ?: 0L) > 3_000L }
            ?: return Result.success()
        val lesson = ContentRepository.get(applicationContext).state.value.lessonById[candidate]
            ?: return Result.success()
        NotificationPublisher.show(
            applicationContext,
            id = 1,
            title = "تابع الاستماع",
            body = "لديك درس لم تكمله — ${lesson.displayTitle}",
            destination = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}",
            channel = NotificationChannels.CONTENT,
            // هنا الاستئناف من موضع التوقّف نفسه لا من أوّل الدرس — وهو
            // معنى «تابع الاستماع». الموضع بالثواني كما يفهمه الرابط.
            playDestination = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}" +
                "?t=${(positions[candidate] ?: 0L) / 1_000L}",
        )
        return Result.success()
    }
}

/**
 * تذكير الأذكار — محلّي بحت: لا شبكة، ولا قراءة من الخادم، ولا حتى حاجة إلى
 * محتوى التطبيق. لذلك لا يخضع لمفتاح «إشعارات المحتوى» العام، بل لمفتاحه
 * وحده: من أوقف إشعارات الدروس قد يبقى مريداً لتذكير أذكاره.
 * (إذن الإشعارات نفسه يبقى حاكماً — بلا إذن لا يُعرض شيء.)
 */
class AdhkarReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.success()
        val store = LocalStore.get(applicationContext)
        if (!store.adhkarReminder(kind)) return Result.success()

        val (title, body, section) = when (kind) {
            "morning" -> Triple("أذكار الصباح", "﴿فَسُبْحَانَ اللَّهِ حِينَ تُمْسُونَ وَحِينَ تُصْبِحُونَ﴾", "morning")
            "evening" -> Triple("أذكار المساء", "لا تنسَ وردك من أذكار المساء.", "evening")
            "sleep" -> Triple("أذكار النوم", "اختم يومك بأذكار النوم.", "sleep")
            else -> Triple("أذكار الاستيقاظ", "الحمد لله الذي أحيانا بعد ما أماتنا.", "wake")
        }
        NotificationPublisher.show(
            applicationContext,
            id = ADHKAR_NOTIFICATION_IDS[kind] ?: 40,
            title = title,
            body = body,
            destination = "minbar://adhkar/$section",
            channel = NotificationChannels.CONTENT,
        )
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        val ADHKAR_NOTIFICATION_IDS = mapOf(
            "morning" to 41, "evening" to 42, "sleep" to 43, "wake" to 44,
        )
    }
}

class WardWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.notificationsEnabled() || !store.wardEnabled()) return Result.success()
        val lesson = ContentRepository.get(applicationContext).dailyWard() ?: return Result.success()
        NotificationPublisher.show(
            applicationContext,
            id = 700,
            title = "وِرد اليوم 🌿",
            body = lesson.displayTitle,
            destination = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}",
            channel = NotificationChannels.WARD,
            // وِرد اليوم يُسمع من أوّله، فلحظة البداية صفر.
            playDestination = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}?t=0",
        )
        return Result.success()
    }
}

/**
 * 🕌 تذكير وِرد المصحف — نظير [WardWorker] حرفياً: نفس القناة، ونفس الجدولة
 * اليوميّة، ونفس شرط الإشعارات والإذن.
 *
 * والفرق الوحيد أنّه **لا يُزعج من أتمّ ورده**: ما بقي يُقرأ من عدّاد صفحات
 * اليوم نفسه الذي يعرضه فهرس المصحف (انظر [LocalStore.quranWardRemaining])،
 * فمن ختم مقداره اليوم لا يصله شيء.
 */
class QuranWardWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.notificationsEnabled() || !store.quranWardEnabled()) return Result.success()
        val remaining = store.quranWardRemaining()
        if (remaining <= 0) return Result.success()
        NotificationPublisher.show(
            applicationContext,
            id = 701,
            title = "وِرد المصحف 🕌",
            body = "بقي ${quranPagesLabel(remaining)} من وِردك اليوم.",
            destination = "minbar://quran",
            channel = NotificationChannels.WARD,
        )
        return Result.success()
    }
}

class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.autoDownloadEnabled()) return Result.success()
        val content = ContentRepository.get(applicationContext)
        // فشل المزامنة لا يمنع تحميل ما في الكاش، أمّا إيقاف العمل من
        // WorkManager فيجب أن يُنهيه فوراً لا أن يمضي في جدولة تنزيلات.
        runCatching { content.refresh(false) }.exceptionOrNull()?.let { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        }
        val downloads = DownloadRepository.get(applicationContext)
        // الأهداف: 'recent' أحدث الدروس، 'main' الخلاصة المقترحة،
        // و'followed' جديد الأقسام التي يتابعها — بنفس الطابور وشرط الشبكة
        // والحدّ الأقصى، فلا آليّة ثانية لهدفٍ ثالث.
        val target = store.autoDownloadTarget() ?: "recent"
        val lessons = when (target) {
            "main" -> content.recommended(MAX_PER_RUN)
            "followed" -> {
                val followed = store.followedSubcategories().toSet()
                // لا متابعات = لا عمل: ينتهي العامل صامتاً بلا طابور ولا إشعار.
                if (followed.isEmpty()) return Result.success()
                content.state.value.lessons
                    .filter { it.subcategoryId in followed }
                    .sortedByDescending { it.createdAtMs }
                    .take(MAX_PER_RUN)
            }
            else -> content.newest(MAX_PER_RUN)
        }
        val missing = lessons
            .filter { it.audioUrl.isNotBlank() && !downloads.isDownloaded(it.id) }
            .map { it.id }
        if (missing.isEmpty()) return Result.success()
        // علم إلغاء قديم لدرس ألغاه المستخدم يدويّاً كان يُسقط إدراجه هنا
        // بصمت — والتحميل التلقائي لا واجهة له تُظهر ما سقط.
        missing.forEach { downloads.clearCancel(it) }
        // يمرّ عبر طابور التحميل الخلفي نفسه ليستفيد من الاستئناف عند
        // انقطاع الشبكة وإعادة المحاولة التلقائية وإشعار التقدّم.
        store.addToDownloadQueue(
            missing,
            when (target) {
                "main" -> "خلاصتك المقترحة"
                "followed" -> "جديد أقسامك"
                else -> "أحدث الدروس"
            },
            wifiOnly = store.autoDownloadWifiOnly(),
        )
        com.ali.menbaradkshk.data.DownloadScheduler.enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val MAX_PER_RUN = 30
    }
}

/// 🔔 فحص التحديث اليوميّ — الطبقة التي كانت ناقصة.
///
/// شاشة التذكير لا تُرى إلا عند **فتح** التطبيق، ومن يفتحه نادراً يبقى على
/// نسخة قديمة أسابيع بلا أن يعلم. هذا العامل يقرأ وثيقة الإعداد مرّة كل
/// يوم ويُشعر صاحب النسخة الأقدم — الإشعار قابل للصرف كأيّ إشعار، ولا
/// يتكرّر لنسخة صُرفت شاشتُها.
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = com.ali.menbaradkshk.data.AppConfigRepository.get(applicationContext)
        val status = runCatching { config.statusForcingRefresh() }.getOrNull() ?: return Result.success()
        val (latest, message) = when (status) {
            is com.ali.menbaradkshk.data.AppConfigRepository.Status.Required ->
                status.latest to status.message
            is com.ali.menbaradkshk.data.AppConfigRepository.Status.Optional ->
                status.latest to status.message
            else -> return Result.success()
        }
        // خانق يوميّ خاصّ بالإشعار: لا يُزعج أكثر من مرّة في اليوم لنسخة
        // واحدة، ولا يُرسَل إن كان المستخدم قد صرف شاشة هذه النسخة أصلاً.
        if (!config.shouldNotify(latest)) return Result.success()
        config.markNotified(latest)
        NotificationPublisher.show(
            applicationContext,
            id = MinbarMessagingService.UPDATE_NOTIFICATION_ID,
            title = "تتوفّر نسخة أحدث من منبر ادكصهك",
            body = message.ifBlank { "حدِّث التطبيق لتصلك المزايا والإصلاحات الجديدة." },
            destination = com.ali.menbaradkshk.data.AppConfigRepository.PLAY_URL,
            channel = NotificationChannels.CONTENT,
            toStore = true,
        )
        return Result.success()
    }
}

object BackgroundScheduler {
    private const val CONTINUE_WORK = "continue_reminder"
    private const val WARD_WORK = "daily_ward"
    private const val QURAN_WARD_WORK = "quran_ward"
    private const val AUTO_DOWNLOAD_WORK = "auto_download"
    private const val UPDATE_CHECK_WORK = "update_check"

    fun scheduleAll(context: Context) {
        scheduleContinue(context)
        scheduleWard(context)
        scheduleQuranWard(context)
        scheduleAutoDownload(context)
        scheduleUpdateCheck(context)
        scheduleAdhkar(context)
    }

    /// أربعة تذكيرات مستقلّة، لكلٍّ عملٌ دوريّ يوميّ واحد يُلغى فور إيقافه.
    /// المواعيد الافتراضيّة من [AdhkarReminders] وحده — كانت مكرَّرة هنا وفي
    /// شاشة التذكيرات، فأي تعديل في أحدهما يجعل المعروض غير المُجدوَل.
    fun scheduleAdhkar(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        AdhkarReminders.DEFAULTS.forEach { (kind, default) ->
            val work = "adhkar_$kind"
            if (!store.adhkarReminder(kind)) {
                manager.cancelUniqueWork(work)
                return@forEach
            }
            val hour = store.adhkarReminderHour(kind, default.first)
            val minute = store.adhkarReminderMinute(kind, default.second)
            val delay = delayUntil(hour, minute)
            val request = PeriodicWorkRequestBuilder<AdhkarReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .pinNextRun(delay)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(AdhkarReminderWorker.KEY_KIND, kind)
                        .build(),
                )
                .build()
            manager.enqueueUniquePeriodicWork(work, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    /// غير مشروط بإعدادات الإشعارات الاختيارية: تذكير التحديث ليس محتوى
    /// ترويجياً بل شرط بقاء التطبيق سليماً. (وإذن الإشعارات نفسه يبقى
    /// حاكماً: بلا إذن لا يُعرض شيء.)
    fun scheduleUpdateCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UPDATE_CHECK_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleContinue(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        if (!store.notificationsEnabled() || !store.continueReminderEnabled()) {
            manager.cancelUniqueWork(CONTINUE_WORK)
            return
        }
        val delay = delayUntil(19, 0)
        val request = PeriodicWorkRequestBuilder<ContinueReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .pinNextRun(delay)
            .build()
        manager.enqueueUniquePeriodicWork(
            CONTINUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleWard(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        if (!store.notificationsEnabled() || !store.wardEnabled()) {
            manager.cancelUniqueWork(WARD_WORK)
            return
        }
        val delay = delayUntil(store.wardHour(), store.wardMinute())
        val request = PeriodicWorkRequestBuilder<WardWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .pinNextRun(delay)
            .build()
        manager.enqueueUniquePeriodicWork(WARD_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /// نظيرة [scheduleWard] بلا اختلاف في النمط — ومنه تثبيتُ الموعد التالي
    /// عبر [pinNextRun] (انظر تعليقها: بدونه لا يُطبَّق تغيير الوقت أبداً).
    /// وساعةٌ سالبة تعني «مقدارٌ بلا تذكير»: الوِرد يُعرض في الفهرس ولا يُجدوَل.
    fun scheduleQuranWard(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        val hour = store.quranWardHour()
        if (!store.notificationsEnabled() || !store.quranWardEnabled() || hour < 0) {
            manager.cancelUniqueWork(QURAN_WARD_WORK)
            return
        }
        val delay = delayUntil(hour, store.quranWardMinute())
        val request = PeriodicWorkRequestBuilder<QuranWardWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .pinNextRun(delay)
            .build()
        manager.enqueueUniquePeriodicWork(
            QURAN_WARD_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleAutoDownload(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        if (!store.autoDownloadEnabled()) {
            manager.cancelUniqueWork(AUTO_DOWNLOAD_WORK)
            return
        }
        val network = if (store.autoDownloadWifiOnly()) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        manager.enqueueUniquePeriodicWork(
            AUTO_DOWNLOAD_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /// ⚠️ الموعد التالي يُثبَّت صراحةً في **كل** جدولة ولا يُترك لحساب
    /// WorkManager: سياسة `UPDATE` تُبقي `lastEnqueueTime` و`periodCount`
    /// القديمين، و`initialDelay` لا يُحتسب إلا في الدورة الأولى — فكان تغيير
    /// وقت التذكير لا يُطبَّق أبداً بعد أوّل تشغيل، وكان كل تأخير من وضع
    /// الغفوة يتراكم في الموعد بلا رجعة. ولا نلجأ إلى
    /// `CANCEL_AND_REENQUEUE` لأنّها تُلغي تذكيراً معلّقاً لم يُطلق بعد.
    private fun PeriodicWorkRequest.Builder.pinNextRun(delayMs: Long): PeriodicWorkRequest.Builder =
        setNextScheduleTimeOverride(System.currentTimeMillis() + delayMs)

    private fun delayUntil(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var due = now.withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        if (!due.isAfter(now)) due = due.plusDays(1)
        return Duration.between(now, due).toMillis().coerceAtLeast(0L)
    }
}

private object NotificationPublisher {
    fun show(
        context: Context,
        id: Int,
        title: String,
        body: String,
        destination: String,
        channel: String,
        /// حين تكون الوجهة المتجر: نقفز إليه مباشرة عبر الوسيط الصامت بدل
        /// فتح التطبيق. الرابط لا يظهر للمستخدم في الحالتين.
        toStore: Boolean = false,
        /// ⏵ وجهة زرّ «استمع الآن»: رابط درسٍ يبدأ تشغيله فور فتحه.
        /// فارغة = بلا زرّ.
        playDestination: String = "",
    ) {
        val intent = if (toStore) {
            com.ali.menbaradkshk.util.StoreRedirectActivity.intent(context, destination)
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(destination)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_minbar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        // ⏵ «استمع الآن» — ضغطةٌ واحدة لا اثنتان: النقر على الإشعار كان
        // يفتح التطبيق على صفحة الدرس ثم يبحث المستخدم عن زرّ التشغيل.
        //
        // ولماذا يفتح التطبيق ولا يشغّل من الخلفية مباشرة؟ لأن بدء خدمة
        // الوسائط والتطبيق في الخلفية ممنوع منذ أندرويد 8 (ويرمي استثناءً
        // في 12+) — وهي العلّة نفسها الموثَّقة في ودجت «الآن يُشغَّل».
        // فنسلك المسار القائم: رابط الدرس مع لحظة بدايةٍ صريحة، وشاشة
        // المشغّل تبدأ التشغيل من تلقائها حين تصلها اللحظة. النتيجة
        // للمستخدم واحدة: ضغطة واحدة ثم صوت.
        if (playDestination.isNotBlank()) {
            val playIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(playDestination)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            builder.addAction(
                0,
                "▶ استمع الآن",
                PendingIntent.getActivity(
                    context,
                    // رمز طلبٍ مستقلّ عن نقرة الإشعار نفسها، وإلّا داس
                    // أحدهما الآخر (نفس السياق ونفس الرمز = نفس المُعلَّق).
                    id + PLAY_REQUEST_OFFSET,
                    playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val notification = builder.build()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /// إزاحة رموز الطلب لأزرار الإشعارات — بعيدة عن معرّفات الإشعارات كلّها.
    private const val PLAY_REQUEST_OFFSET = 90_000
}
