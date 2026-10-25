package com.ali.menbaradkshk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.notification.NotificationChannels
import com.ali.menbaradkshk.notification.BackgroundScheduler
import androidx.work.Configuration
import java.io.File

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MinbarApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Keep WorkManager's IDs separate from DownloadScheduler.JOB_ID (4210).
            .setJobSchedulerJobIdRange(1_000, 3_000)
            .build()

    /// Coil 3 لا يُنشئ كاشاً قرصياً افتراضياً إطلاقاً، فكانت كل صورة تُنزَّل من
    /// جديد بعد كل إقلاع. المفاتيح روابط Storage الحاملة لـ`token` فالإبطال ذاتي.
    @Volatile private var imageLoaderCreated = false

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    // الاسم من [LocalStore] كي لا يفترق ما يُكتب عمّا يُقاس ويُمسح.
                    .directory(File(context.cacheDir, LocalStore.IMAGE_CACHE_DIR))
                    .maxSizeBytes(IMAGE_CACHE_BYTES)
                    .build()
            }
            .memoryCache {
                // 🪶 ١٥٪ من ذاكرة العمليّة لا ٢٠٪: أجهزة جمهورنا متواضعة،
                // وكاش صورٍ سمينٌ يعني ضغطاً على جامع القمامة وتقطّعاً في
                // التمرير — والصور هنا أغلفةٌ وصفحات مصحف تُقرأ واحدةً واحدة،
                // فلا يحتاج بقاؤها في الذاكرة إلى خُمس الجهاز.
                MemoryCache.Builder().maxSizePercent(context, 0.15).build()
            }
            .build()
            .also { imageLoaderCreated = true }

    override fun onCreate() {
        super.onCreate()
        instanceOrNull = this
        createNotificationChannels()
        // 🔥 التسخين في الخلفية لا على الخيط الرئيسي (Play Vitals 2026-10-25):
        // - جدولة العمّال تمرّ بتهيئة WorkManager وقراءة التفضيلات، ورُصد لها ANR
        //   داخل `onCreate` (`BackgroundScheduler.scheduleContinue`).
        // - كاش الوسائط (`SimpleCache`) يقرأ فهرسه وقاعدته من القرص، وكان يُنشأ
        //   أوّل مرّة داخل `PlaybackService.onCreate` على الخيط الرئيسي، فرُصد
        //   ANR هناك وانهيار `ForegroundServiceDidNotStartInTime` حين تُطلَق
        //   الخدمة من زرّ السمّاعة/الودجت بعد موت العمليّة وتتأخّر عن مهلة
        //   النظام. التسخين هنا يجعل إنشاءها لاحقاً مجرّد قراءة مرجعٍ جاهز.
        Thread({
            runCatching { LocalStore.get(this) }
            runCatching { mediaCache(this) }
            runCatching { BackgroundScheduler.scheduleAll(this) }
            // استئناف طابور التحميل إن بقيت فيه دروس من جلسة سابقة.
            runCatching {
                if (LocalStore.get(this).downloadQueue().isNotEmpty()) {
                    com.ali.menbaradkshk.data.DownloadScheduler.enqueue(this)
                }
            }
        }, "minbar-warmup").start()
    }

    /**
     * 🍃 امتثال متطلب Play (ذاكرة الصور النقطية بالخلفية): عند اختفاء الواجهة
     * أو أشدّ نُفرغ كاش ذاكرة Coil (~50MB سقفاً، وفيه صفحات مصحف كبيرة) ونصوص
     * وتخطيطات المصحف المفكوكة — كلّها تُعاد من كاش القرص/الأصول عند العودة
     * فلا أثر وظيفي. وعند ضغط أخفّ والواجهة ظاهرة يُقلَّص كاش الصور للنصف
     * فقط كي لا يتقطّع ما يُعرض الآن.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // ⚠️ لا يُنشأ محمّل الصور هنا إن لم يكن قد أُنشئ: `SingletonImageLoader.get`
        // يبنيه (مسح ServiceLoader + كاش القرص) على الخيط الرئيسي، ورُصد له ANR
        // من `onTrimMemory` نفسه. ما لم يُبنَ فلا ذاكرة له تُفرَّغ أصلاً.
        if (!imageLoaderCreated) return
        val imageCache = runCatching { SingletonImageLoader.get(this).memoryCache }.getOrNull()
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { imageCache?.clear() }
            com.ali.menbaradkshk.data.QuranRepository.trimMemory()
            com.ali.menbaradkshk.data.MushafRepository.trimMemory()
        } else if (level >= TRIM_MEMORY_RUNNING_LOW) {
            runCatching { imageCache?.let { it.trimToSize(it.size / 2) } }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    NotificationChannels.CONTENT,
                    getString(R.string.content_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    NotificationChannels.WARD,
                    getString(R.string.ward_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    NotificationChannels.DOWNLOADS,
                    "تحميل الدروس",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            ),
        )
    }

    companion object {
        @Volatile var instanceOrNull: MinbarApplication? = null
            private set
        private const val MEDIA_CACHE_BYTES = 256L * 1_024 * 1_024
        /// 🖼️ سقفٌ صريح ٥٠ م.ب لكاش الصور — أقلّ من السابق (٦٤) عمداً:
        /// المساحة على أجهزة جمهورنا شحيحة، والصورة المطرودة تُعاد بضغطة
        /// شبكةٍ واحدة صغيرة، أمّا مئة ميغا محجوزة فتُشعِر المستخدم بأنّ
        /// التطبيق «يأكل جهازه». والمجلّد نفسه يقرؤه
        /// `LocalStore.imageCacheBytes()` ليعرضه في الإعدادات.
        private const val IMAGE_CACHE_BYTES = 50L * 1_024 * 1_024

        @Volatile
        private var cacheInstance: SimpleCache? = null

        /// كاش الوسائط: درس غير منزَّل كان يُنزَّل كاملاً من جديد في كل استماع.
        ///
        /// **مفرد حقيقي على مستوى العملية إلزاماً** — نسختا `SimpleCache` على
        /// المجلد نفسه ترميان استثناءً. وهو فهرس منفصل تماماً عن «تنزيلاتي»:
        /// لا يمسّ `pruneDownloads` ولا حذف التنزيل، والملف المنزَّل صراحةً
        /// يبقى مقدَّماً دائماً لأنّه يُقرأ بمسار محلّي لا يمرّ بالكاش أصلاً.
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        fun mediaCache(context: Context): SimpleCache = cacheInstance ?: synchronized(this) {
            cacheInstance ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media_cache").apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cacheInstance = it }
        }
    }
}
