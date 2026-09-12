package com.ali.menbaradkshk.notification

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        NotificationPoster.show(
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
        NotificationPoster.show(
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
        NotificationPoster.show(
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
        NotificationPoster.show(
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

/// ⛔ قاعدة المعمارية: **صفر تنزيل صوتي تلقائي على الشبكات المحدودة مهما
/// كانت الإعدادات** — عدا سماح المستخدم الصريح في «التنزيل التلقائي»
/// الاختياري، وحتى هو مسقوف بميزانية دورةٍ صغيرة، وData Saver يعطّل الكلّ.
private object AutoDownloadPolicy {
    /// Data Saver مفعَّل؟ لا تنزيل تلقائياً البتة — احترامٌ صريح لاختيار
    /// النظام قبل قيود WorkManager (القيود لا تعرف Data Saver).
    fun dataSaverOn(context: Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return false
        return manager.restrictBackgroundStatus ==
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    fun onMeteredNetwork(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)
            ?.isActiveNetworkMetered == true
}

class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.autoDownloadEnabled()) return Result.success()
        if (AutoDownloadPolicy.dataSaverOn(applicationContext)) return Result.success()
        val metered = AutoDownloadPolicy.onMeteredNetwork(applicationContext)
        // شبكة محدودة والمستخدم لم يسمح بها = لا شيء (القيد يضمنها أصلاً،
        // وهذا حارس داخلي لتبدّل الشبكة بين الجدولة والتشغيل).
        if (metered && store.autoDownloadWifiOnly()) return Result.success()
        val content = ContentRepository.get(applicationContext)
        // فشل المزامنة لا يمنع تحميل ما في الكاش، أمّا إيقاف العمل من
        // WorkManager فيجب أن يُنهيه فوراً لا أن يمضي في جدولة تنزيلات.
        runCatching { content.refresh(false) }.exceptionOrNull()?.let { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        }
        val downloads = DownloadRepository.get(applicationContext)
        // محرك الأولوية بدل الأهداف الثلاثة: طبقة السياق ثم النية ثم الباقي.
        // على شبكة محدودة سمح بها صراحةً: ميزانية 20م.ب كحدّ أقصى للدورة.
        val budget = if (metered) 20L * 1024 * 1024 else Long.MAX_VALUE
        val planned = com.ali.menbaradkshk.data.PriorityEngine
            .plan(applicationContext, budgetBytes = budget, maxItems = MAX_PER_RUN)
        // ⚠️ ما هو في الطابور الآن دخله بنيّةٍ سُجّلت وقتها (يدويّة غالباً) —
        // وسمه «تلقائياً» هنا كان يقلب تنزيلاً يدويّاً منتظراً إلى مرشَّح إخلاء.
        val queuedNow = store.downloadQueue().toSet()
        val ids = planned.map { it.id }.filter { it !in queuedNow }
        if (ids.isEmpty()) return Result.success()
        // ما نزّله المحرك يُعلَّم «تلقائياً» فيُخلى عند ضيق المساحة أولاً.
        store.markAutoQueued(ids)
        // علم إلغاء قديم لدرس ألغاه المستخدم يدويّاً كان يُسقط إدراجه هنا
        // بصمت — والتحميل التلقائي لا واجهة له تُظهر ما سقط.
        ids.forEach { downloads.clearCancel(it) }
        // يمرّ عبر طابور التحميل الخلفي نفسه ليستفيد من الاستئناف عند
        // انقطاع الشبكة وإعادة المحاولة التلقائية وإشعار التقدّم.
        store.addToDownloadQueue(ids, "اختيارات لك", wifiOnly = store.autoDownloadWifiOnly())
        com.ali.menbaradkshk.data.DownloadScheduler.enqueue(applicationContext, userInitiated = false)
        return Result.success()
    }

    companion object {
        private const val MAX_PER_RUN = 30
    }
}

/**
 * 🧠 «التنزيل الذكي» — جديد الأقسام المتابَعة يُنزَّل من تلقائه.
 *
 * مستقلّ عن «التنزيل التلقائي» (ذاك اختياريّ بأهدافه الثلاثة): هذا **مفعَّل
 * افتراضياً** لأنّه لا يكلّف المستخدم شيئاً — قيوده واي فاي + بطارية غير
 * منخفضة، وحدُّه عشرة دروس لكل دورة (كل ١٢ ساعة). يمرّ بطابور التحميل
 * الموجود نفسه فيَرِث الاستئناف وإشعار التقدّم وإعادة المحاولة.
 */
class SmartDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.smartDownloadEnabled()) return Result.success()
        // ⛔ صفر تنزيل تلقائي على الشبكات المحدودة مهما كانت الإعدادات:
        // القيد UNMETERED يضمنها عند الجدولة، وهذا حارس لتبدّل الشبكة
        // بين الجدولة والتشغيل، وData Saver لا تعرفه القيود أصلاً.
        if (
            AutoDownloadPolicy.dataSaverOn(applicationContext) ||
            AutoDownloadPolicy.onMeteredNetwork(applicationContext)
        ) {
            return Result.success()
        }
        val content = ContentRepository.get(applicationContext)
        // فشل المزامنة لا يمنع تحميل ما في الكاش (نمط AutoDownloadWorker نفسه).
        runCatching { content.refresh(false) }.exceptionOrNull()?.let { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        }
        val downloads = DownloadRepository.get(applicationContext)

        // كشف stale أولاً: صوتٌ استُبدل لدى مَن نزّله يُصلَح في أول الطابور.
        val lessons = content.state.value.lessons
        // ⚠️ ما هو في الطابور الآن دخله بنيّةٍ سُجّلت وقتها (يدويّة غالباً) —
        // وسمه «تلقائياً» هنا كان يقلب تنزيلاً يدويّاً منتظراً إلى مرشَّح إخلاء.
        val queuedNow = store.downloadQueue().toSet()
        val stale = downloads.staleDownloadIds(lessons).filter { it !in queuedNow }
        if (stale.isNotEmpty()) {
            val (manual, auto) = stale.partition {
                store.downloadMeta(it)?.optString("src") == "manual"
            }
            if (auto.isNotEmpty()) {
                store.markAutoQueued(auto)
                auto.forEach { downloads.clearCancel(it) }
                store.addToDownloadQueue(auto, "تحديث مكتبتك", wifiOnly = true)
            }
            // اليدويّ يُصلَح بأولوية المستخدم نفسه: الصغير (≤10م.ب) على أي
            // شبكة كي لا يبقى ملفه معطوباً، والكبير ينتظر الواي فاي.
            val byId = content.state.value.lessonById
            val (big, small) = manual.partition {
                (byId[it]?.sizeBytes ?: 0L) > 10L * 1024 * 1024
            }
            small.forEach { downloads.clearCancel(it) }
            big.forEach { downloads.clearCancel(it) }
            if (small.isNotEmpty()) store.addToDownloadQueue(small, "تحديث مكتبتك", wifiOnly = false)
            if (big.isNotEmpty()) store.addToDownloadQueue(big, "تحديث مكتبتك", wifiOnly = true)
        }

        // محرك الأولوية: يختار للجميع (متابعات، مفضّلة، سياق، أرشيف…).
        val planned = com.ali.menbaradkshk.data.PriorityEngine
            .plan(applicationContext, budgetBytes = Long.MAX_VALUE, maxItems = MAX_PER_RUN)
        val staleSet = stale.toSet()
        val ids = planned.map { it.id }.filter { it !in staleSet && it !in queuedNow }
        if (ids.isEmpty() && stale.isEmpty()) return Result.success()
        if (ids.isNotEmpty()) {
            store.markAutoQueued(ids)
            ids.forEach { downloads.clearCancel(it) }
            // القيد لكل عنصر: هذه الدفعة واي فاي فقط مهما كانت دفعات المستخدم.
            store.addToDownloadQueue(ids, "اختيارات لك", wifiOnly = true)
        }
        com.ali.menbaradkshk.data.DownloadScheduler.enqueue(applicationContext, userInitiated = false)
        return Result.success()
    }

    companion object {
        private const val MAX_PER_RUN = 40
    }
}

/// 🛡️ حارس الطابور — يسدّ ضياع وظيفة UIDT بعد إعادة تشغيل الجهاز:
/// JobScheduler لا يعيد وظائف نقل البيانات بمبادرة المستخدم بعد reboot،
/// فيبقى الطابور مملوءاً بلا أي عملٍ يعالجه. كل 6 ساعات: طابورٌ غير فارغ
/// بلا عمل نشط ولا وظيفة معلّقة ⇒ إيقاظ المعالجة.
class DownloadQueueGuardianWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (store.downloadQueue().isEmpty()) return Result.success()
        val manager = WorkManager.getInstance(applicationContext)
        val workIdle = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                manager.getWorkInfosForUniqueWork(
                    com.ali.menbaradkshk.data.DownloadScheduler.WORK_NAME,
                ).get() + manager.getWorkInfosForUniqueWork(
                    com.ali.menbaradkshk.data.DownloadScheduler.WIFI_WORK_NAME,
                ).get()
            }.all { it.state.isFinished }
        }.getOrDefault(true)
        val jobIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                applicationContext.getSystemService(android.app.job.JobScheduler::class.java)
                    ?.getPendingJob(com.ali.menbaradkshk.data.DownloadScheduler.JOB_ID) == null
            }.getOrDefault(true)
        } else {
            true
        }
        if (workIdle && jobIdle) {
            com.ali.menbaradkshk.data.DownloadScheduler
                .enqueue(applicationContext, userInitiated = false)
        }
        return Result.success()
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
        NotificationPoster.show(
            applicationContext,
            id = NotificationPoster.UPDATE_NOTIFICATION_ID,
            title = "تتوفّر نسخة أحدث من منبر ادكصهك",
            body = message.ifBlank { "حدِّث التطبيق لتصلك المزايا والإصلاحات الجديدة." },
            destination = com.ali.menbaradkshk.data.AppConfigRepository.PLAY_URL,
            channel = NotificationChannels.CONTENT,
            toStore = true,
        )
        return Result.success()
    }
}

/**
 * 💓 النبض التكيّفي — بديل FCM بعد الاستقلال التام عن Firebase (2.7.0).
 *
 * مصدر النبض ملفٌ صغير على CDN (`pulse.json` ≤ 1 ك.ب، مكاش دقيقة) يكتبه
 * الخادم عند كل تغيير محتوى أو إشعار — لا يمرّ بالـWorker. احتياطه عند
 * الحجب: `GET /v1/pulse` على قاعدتَي `MinbarApi`. وفي كل نبضة لا نداء آخر **إلا إن تغيّر شيء**:
 * - `notifMs` أحدث من آخر ما رأيناه ⇒ خلاصة الإشعارات (العامة، والخاصة لمن
 *   ساهم أو راسل من قبل) وإشعارٌ محلّي لكل جديد بقواعد التصفية أدناه.
 * - `contentMs` أحدث من علامتنا ⇒ مزامنة الكتالوج الخفيفة (غير القسرية).
 *
 * والإيقاع من حداثة آخر تغيير على الخادم (السلّم في [PulseLadder]):
 * كل نبضة تعيد جدولة نفسها بتأخير محسوب، وحارسٌ دوري كل 12 ساعة يعيد
 * إطلاق السلسلة إن قطعها النظام. القيد اتصالٌ فقط — لا شحن ولا واي‑فاي.
 *
 * قواعد الإشعارات المحلّية:
 * - تذكير التحديث **غير مشروط** بمفتاح الإشعارات ويقفز إلى المتجر.
 * - `sec_<id>`: جديد قسمٍ لا يصل إلا لمتابِعه (المتابعة محلّية على الجهاز).
 * - بشرى اعتماد النصّ تُفرغ كاش النصّ لحظة وصولها لا لحظة النقر.
 * - ما فُتح في شاشة الإشعارات لا يُعاد إشعاره، ولا يُغرَق مُثبِّتٌ جديد بشهر.
 */
class PulseWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        val now = System.currentTimeMillis()
        store.setLastPulseMs(now)
        val pulse = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.ali.menbaradkshk.data.MinbarApi.pulse()
        }
        if (pulse != null) {
            if (pulse.notifMs > store.lastSeenNotifMs()) {
                pollNotifications(store, now)
            }
            if (pulse.contentMs > store.lastPulseContentMs()) {
                runCatching { ContentRepository.get(applicationContext).refresh(false) }
                    .exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                store.setLastPulseContentMs(pulse.contentMs)
            }
        }
        // النبضة التالية بحسب حداثة آخر تغيير — وإن تعذّر النبض نفسه فبالحدّ الأقصى.
        val latestChange = pulse?.let { maxOf(it.contentMs, it.notifMs, it.alertsMs) } ?: 0L
        BackgroundScheduler.schedulePulseAfter(applicationContext, PulseLadder.delayMsFor(now - latestChange))
        return Result.success()
    }

    private suspend fun pollNotifications(store: LocalStore, now: Long) {
        val enabled = store.notificationsEnabled()
        val followed = store.followedSubcategories().toSet()
        // خطّ الأساس: آخر ما أُشعر به، وإلّا آخر ما رُئي في الشاشة، وإلّا
        // آخر ٢٤ ساعة — فلا يُغرَق مُثبِّتٌ جديد بإشعارات شهرٍ كامل.
        val baseline = maxOf(store.lastSeenNotifMs(), store.notificationLastSeenMs(), now - DAY_MS)
        var newest = baseline
        var posted = 0

        fun handle(item: org.json.JSONObject, private: Boolean) {
            val createdAt = item.optLong("createdAtMs", 0L)
            if (createdAt <= baseline) return
            val data = buildMap {
                put("type", item.optString("type"))
                put("route", item.optString("route"))
                put("lessonId", item.optString("lessonId"))
                item.optString("refId").takeIf { it.isNotBlank() }?.let { put("refId", it); put("id", it) }
            }
            val update = NotificationPoster.isUpdate(data)
            if (update) {
                // إشعار «إصدار جديد» يحمل رقمه في معرّفه (`update-<code>`):
                // من يحمل نسخةً أحدث أو مساوية لا يُنبَّه.
                val code = item.optString("id").substringAfter("update-", "").toIntOrNull()
                if (code != null && code <= com.ali.menbaradkshk.BuildConfig.VERSION_CODE) return
            } else {
                val topic = item.optString("topic")
                if (topic.startsWith("sec_") && topic.removePrefix("sec_") !in followed) return
                // إبطال الكاش نظافةُ بيانات لا عرضُ إشعار — قبل حارس المفتاح.
                NotificationPoster.invalidateTranscriptCache(applicationContext, data)
                if (!enabled) return
            }
            newest = maxOf(newest, createdAt)
            if (posted >= MAX_POSTED_PER_RUN) return
            posted++
            val title = item.optString("title")
                .ifBlank { applicationContext.getString(com.ali.menbaradkshk.R.string.app_name) }
            val body = item.optString("body")
            if (update) {
                NotificationPoster.show(
                    applicationContext,
                    id = NotificationPoster.UPDATE_NOTIFICATION_ID,
                    title = title,
                    body = body,
                    destination = com.ali.menbaradkshk.data.AppConfigRepository.PLAY_URL,
                    channel = NotificationChannels.CONTENT,
                    toStore = true,
                    highPriority = true,
                )
            } else {
                val destination = NotificationPoster.destinationFor(data)
                    ?: if (private) "minbar://my-submissions" else "minbar://notifications"
                NotificationPoster.show(
                    applicationContext,
                    // معرّف ثابت لكل عنصر خادميّ: التكرار (لو وقع) يحلّ محلّ نفسه.
                    id = ((if (private) "p:" else "g:") + item.optString("id")).hashCode(),
                    title = title,
                    body = body,
                    destination = destination,
                    channel = NotificationChannels.CONTENT,
                    highPriority = true,
                )
            }
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val items = com.ali.menbaradkshk.data.MinbarApi.notifications(baseline + 1, LIMIT)
                // الأقدم أولاً كي يظهر ترتيب الوصول طبيعياً في شريط النظام.
                (items.length() - 1 downTo 0).mapNotNull { items.optJSONObject(it) }
                    .forEach { handle(it, private = false) }
            }
            if (store.knownSubmissionStatuses().isNotEmpty() || store.submitterName().isNotBlank()) {
                runCatching {
                    val items = com.ali.menbaradkshk.data.MinbarApi
                        .getUser(applicationContext, "/v1/me/notifications?since=${baseline + 1}")
                        .optJSONArray("items") ?: org.json.JSONArray()
                    (items.length() - 1 downTo 0).mapNotNull { items.optJSONObject(it) }
                        .forEach { handle(it, private = true) }
                }
            }
        }
        if (newest > store.lastSeenNotifMs()) store.setLastSeenNotifMs(newest)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1_000
        private const val LIMIT = 30
        /// سقف الإشعارات في الدورة الواحدة — شريط النظام ليس صندوق بريد.
        private const val MAX_POSTED_PER_RUN = 5
    }
}

/**
 * 📏 سلّم النبض — الثوابت كلّها هنا ولا مكان آخر.
 *
 * التأخير حتى النبضة التالية من **حداثة آخر تغيير على الخادم** (محتوى أو
 * إشعار أو تنبيه): خادمٌ نشط يُسأل كثيراً، وساكنٌ يُترك — فلا تُهدر شبكة
 * المستخدم ولا بطاريته على خادمٍ لم يتغيّر منذ أسبوع، ولا يتأخّر إشعارٌ
 * في أسبوعٍ حافل.
 *
 * | آخر تغيير        | النبضة التالية |
 * |------------------|----------------|
 * | خلال 24 ساعة     | 30 دقيقة       |
 * | خلال 1–3 أيام    | 3 ساعات        |
 * | أقدم (أو مجهول)  | 12 ساعة        |
 *
 * والحدّان [30 دقيقة، 12 ساعة] لا يُتجاوزان.
 */
object PulseLadder {
    const val MIN_DELAY_MS = 30L * 60 * 1_000
    const val MAX_DELAY_MS = 12L * 60 * 60 * 1_000
    private const val MID_DELAY_MS = 3L * 60 * 60 * 1_000
    private const val DAY_MS = 24L * 60 * 60 * 1_000

    /// نبضة فوريّة عند فتح التطبيق إن مضى هذا القدر على آخر نبضة.
    const val FOREGROUND_STALE_MS = 15L * 60 * 1_000

    /// حارس السلسلة الدوري: يعيد إطلاقها إن قطعها النظام (وتأخيره الأوّل إلزامي).
    const val GUARDIAN_HOURS = 12L

    fun delayMsFor(ageMs: Long): Long = when {
        ageMs < 0L -> MIN_DELAY_MS
        ageMs < DAY_MS -> MIN_DELAY_MS
        ageMs < 3 * DAY_MS -> MID_DELAY_MS
        else -> MAX_DELAY_MS
    }.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
}

object BackgroundScheduler {
    private const val CONTINUE_WORK = "continue_reminder"
    private const val WARD_WORK = "daily_ward"
    private const val QURAN_WARD_WORK = "quran_ward"
    private const val AUTO_DOWNLOAD_WORK = "auto_download"
    private const val SMART_DOWNLOAD_WORK = "smart_download"
    private const val UPDATE_CHECK_WORK = "update_check"
    private const val QUEUE_GUARDIAN_WORK = "download_queue_guardian"
    private const val PULSE_WORK = "pulse"
    private const val PULSE_GUARDIAN_WORK = "pulse_guardian"

    fun scheduleAll(context: Context) {
        scheduleContinue(context)
        scheduleWard(context)
        scheduleQuranWard(context)
        scheduleAutoDownload(context)
        scheduleSmartDownload(context)
        scheduleQueueGuardian(context)
        scheduleUpdateCheck(context)
        scheduleAdhkar(context)
        schedulePulseGuardian(context)
    }

    /// 🛡️ حارس الطابور: كل 6 ساعات بقيد اتصال (انظر [DownloadQueueGuardianWorker]).
    fun scheduleQueueGuardian(context: Context) {
        val request = PeriodicWorkRequestBuilder<DownloadQueueGuardianWorker>(6, TimeUnit.HOURS)
            // ⛔ قاعدة ثابتة: أي عمل دوري جديد يلزمه setInitialDelay — أول
            // تشغيل يقع فوراً فيتزاحم مع الإقلاع البارد على الأجهزة الضعيفة.
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            QUEUE_GUARDIAN_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /// 🧠 «التنزيل الذكي»: كل ١٢ ساعة، واي فاي فقط + بطارية غير منخفضة —
    /// فلا يمسّ بيانات المستخدم ولا بطاريته (انظر [SmartDownloadWorker]).
    fun scheduleSmartDownload(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!LocalStore.get(context).smartDownloadEnabled()) {
            manager.cancelUniqueWork(SMART_DOWNLOAD_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS)
            // ⚠️ تأخير أوّلي إلزامي: أول تشغيل للعمل الدوري يقع **فوراً**، فكان
            // التنزيل يتزاحم مع الإقلاع البارد على المعالج والذاكرة ويخنق
            // الأجهزة الضعيفة حتى ANR (شوهد فعلياً على محاكي 2GB).
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        manager.enqueueUniquePeriodicWork(
            SMART_DOWNLOAD_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
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


    /// 💓 حارس النبض: كل 12 ساعة بقيد اتصال — نبضةٌ تعيد إطلاق السلسلة
    /// التكيّفية إن قطعها النظام (انظر [PulseWorker] و[PulseLadder]).
    /// لا يُلغى بإطفاء الإشعارات: تذكير التحديث ومزامنة المحتوى يمرّان منه.
    fun schedulePulseGuardian(context: Context) {
        val request = PeriodicWorkRequestBuilder<PulseWorker>(PulseLadder.GUARDIAN_HOURS, TimeUnit.HOURS)
            // ⛔ قاعدة ثابتة: أي عمل دوري جديد يلزمه setInitialDelay — أول
            // تشغيل يقع فوراً فيتزاحم مع الإقلاع البارد على الأجهزة الضعيفة.
            .setInitialDelay(PulseLadder.MIN_DELAY_MS, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PULSE_GUARDIAN_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /// النبضة التالية بعد تأخير محسوب — `REPLACE` باسم فريد: سلسلةٌ واحدة لا تتفرّع.
    fun schedulePulseAfter(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<PulseWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(PULSE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /// نبضة فوريّة عند فتح التطبيق إن مضى ربع ساعة على آخر نبضة — فمن يفتح
    /// التطبيق لا ينتظر السلّم. تحلّ محلّ النبضة المؤجَّلة (التي ستُعاد جدولتها).
    fun pulseIfStale(context: Context) {
        val store = LocalStore.get(context)
        if (System.currentTimeMillis() - store.lastPulseMs() < PulseLadder.FOREGROUND_STALE_MS) return
        schedulePulseAfter(context, 0L)
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
