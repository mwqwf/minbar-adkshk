package com.ali.menbaradkshk.data

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🎁 بذر المكتبة من حزم أصول Play (المعمارية «دون إنترنت» 2026-09-12).
 *
 * الحزمتان `core` و`rest` (fast-follow) تحملان `serving/{sha}.ogg` للمكتبة كلّها،
 * توزّعهما Google مجاناً بعد التثبيت بلا تدخّل. هنا، عند الإقلاع وبعد كل مزامنة،
 * تُسجَّل ملفّاتهما في فهرس التنزيلات بمصدر `bundle` — فيعدّها المشغّل ومحرّك
 * التنزيل «منزَّلة» ولا يطلب من R2 إلا ما ليس في الحزم (الدروس الجديدة).
 *
 * - الهوية بالبصمة: درسٌ بُدّل صوته لاحقاً يعرف المحرّك أنه stale ويجلبه.
 * - ما حذفه المستخدم يدوياً لا يُعاد بذره (إشارة `markUserDeletedDownload`).
 * - غياب الحزم (نسخة بلا أصول، أو لم تصل بعد) لا يغيّر شيئاً — أفضل جهد.
 */
object AssetPackSeeder {
    private const val TAG = "AssetPackSeeder"
    private val PACKS = listOf("core", "rest")

    /** يعيد عدد الدروس التي بُذرت في هذه المرّة. */
    suspend fun seed(context: Context, lessons: List<Lesson>): Int = withContext(Dispatchers.IO) {
        val store = LocalStore.get(context)
        val bySha = HashMap<String, MutableList<Lesson>>()
        lessons.forEach { l -> if (l.sha256.isNotBlank()) bySha.getOrPut(l.sha256) { mutableListOf() }.add(l) }
        if (bySha.isEmpty()) return@withContext 0
        val existing = store.downloads()
        val userDeleted = store.userDeletedDownloadIds()
        val meta = store.downloadsMetaSnapshot()
        val seeded = HashMap<String, Triple<String, String, Long>>() // id → (path, sha, size)
        runCatching {
            val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
            for (pack in PACKS) {
                val location = manager.getPackLocation(pack) ?: continue
                val dir = File(location.assetsPath(), "serving")
                dir.listFiles()?.forEach { f ->
                    val sha = f.name.removeSuffix(".ogg")
                    if (sha.length != 64 || f.length() <= 0L) return@forEach
                    bySha[sha]?.forEach { l ->
                        val current = existing[l.id]
                        if (current != null && File(current).isFile && meta.optJSONObject(l.id)?.optString("sha") == sha) return@forEach
                        if (l.id in userDeleted) return@forEach
                        seeded[l.id] = Triple(f.absolutePath, sha, f.length())
                    }
                }
            }
        }.onFailure { Log.d(TAG, "asset packs unavailable: $it") }
        if (seeded.isEmpty()) return@withContext 0
        store.seedBundledDownloads(seeded)
        Log.i(TAG, "seeded ${seeded.size} lessons from asset packs")
        seeded.size
    }

    /** حجم ما وصل من حزم المتجر على القرص (لعرضه في شاشة التنزيلات). */
    fun bundledBytes(context: Context): Long = runCatching {
        val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
        PACKS.sumOf { pack ->
            val dir = manager.getPackLocation(pack)?.let { File(it.assetsPath(), "serving") }
            dir?.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }.getOrDefault(0L)

    /**
     * ⏳ هل ما زال يُنتظر من متجر Play أن يُحضر بقيّة المكتبة؟
     *
     * **لماذا هذا السؤال أصلاً؟** لأنّ حزمتي `fast-follow` تصلان **بعد** التثبيت
     * بمدّة يقرّرها المتجر، بينما يكتمل الكتالوج في ثوانٍ. فبين اللحظتين يرى
     * محرّك التنزيل مكتبةً «غير منزَّلة» فيسحبها من R2 — ثمّ تصل الحزم بالصوت
     * نفسه. فتُدفع الشبكة مرّتين وتُشغل المساحة مرّتين.
     *
     * والحكم هنا محافظ عمداً، فالخطأ في الاتجاهين مكلف:
     * - `installerIsPlay`: نسخةٌ لم تأتِ من المتجر لا حزمَ لها أصلاً، فلا يُنتظر
     *   لها شيء — وإلّا حُرم مستخدمُها من التنزيل التلقائي إلى الأبد.
     * - `GRACE_MS`: وإن جاءت من المتجر ولم تصل الحزم خلال المهلة، فالأرجح أنّها
     *   لن تصل (مساحة، أو حذفَها المستخدم). فنعود إلى R2 ولا نتركه بلا صوت.
     *
     * فالتأجيل مؤقّت دائماً، ولا يمنع تنزيلاً إلى غير رجعة.
     */
    fun storeDeliveryPending(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val pm = app.packageManager
        val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(app.packageName)
        }
        if (installer != PLAY_STORE_PACKAGE) return@runCatching false
        val installedAt = pm.getPackageInfo(app.packageName, 0).firstInstallTime
        System.currentTimeMillis() - installedAt < GRACE_MS
    }.getOrDefault(false)

    /** حزمة متجر Play — مصدر التثبيت الذي وحدَه يُنتظر منه إحضار الحزم. */
    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /** مهلة انتظار حزم `fast-follow` قبل العودة إلى R2: سبعة أيام. */
    private const val GRACE_MS = 7L * 24 * 60 * 60 * 1_000

    /**
     * إزالة المكتبة المدمجة لتحرير المساحة — الطريق الوحيد الصحيح لحذف ملفّات
     * الحزم (حذفُها ملفّاً ملفّاً يُفسدها ولا يعلم به المتجر). وما بعدها يعود
     * الصوت من R2 بمحرّك التنزيل كما كان.
     */
    fun removeAll(context: Context) {
        runCatching {
            val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
            PACKS.forEach { manager.removePack(it) }
            val store = LocalStore.get(context)
            val bundled = store.downloads().keys.filter { store.isBundledDownload(it) }
            bundled.forEach { store.removeDownload(it) }
            Log.i(TAG, "removed asset packs and ${bundled.size} index entries")
        }.onFailure { Log.d(TAG, "removeAll failed: $it") }
    }

    /** يطلب إحضار الحزم إن لم تصل بعد (fast-follow تصل وحدها؛ هذا احتياط بعد مسح البيانات). */
    fun ensureFetched(context: Context) {
        runCatching {
            val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
            val missing = PACKS.filter { manager.getPackLocation(it) == null }
            if (missing.isNotEmpty()) manager.fetch(missing)
        }.onFailure { Log.d(TAG, "fetch skipped: $it") }
    }
}
