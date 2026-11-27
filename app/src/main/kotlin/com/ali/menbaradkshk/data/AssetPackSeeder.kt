package com.ali.menbaradkshk.data

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🎁 بذر المكتبة من حزم أصول Play (المعمارية «دون إنترنت» 2026-09-12).
 *
 * الحزمتان `core` و`rest` (on-demand) تحملان `serving/{sha}.ogg` للمكتبة كلّها،
 * يطلبهما التطبيق بحسب اختيار المستخدم. عند وصولها وبعد كل مزامنة،
 * تُسجَّل ملفّاتهما في فهرس التنزيلات بمصدر `bundle` — فيعدّها المشغّل ومحرّك
 * التنزيل «منزَّلة» ولا يطلب من R2 إلا ما ليس في الحزم (الدروس الجديدة).
 *
 * - الهوية بالبصمة: درسٌ بُدّل صوته لاحقاً يعرف المحرّك أنه stale ويجلبه.
 * - الملفات اليدوية المستقلة تبقى في مكانها عند وصول الحزم.
 * - ما حذفه المستخدم يدوياً لا يُعاد بذره (إشارة `markUserDeletedDownload`).
 * - غياب الحزم (نسخة بلا أصول، أو لم تصل بعد) لا يغيّر شيئاً — أفضل جهد.
 */
object AssetPackSeeder {
    private const val TAG = "AssetPackSeeder"
    internal val PACKS = listOf("core", "rest", "more")
    // Main-thread only. A timed-out observer never cancels Play's underlying removal.
    private val removalTasks = mutableMapOf<String, Task<Void>>()
    internal fun hasOutstandingPackRemoval() = removalTasks.values.any { !it.isComplete }

    // assetsPath() يكون null إذا كانت الحزمة داخل APK، ولا يصح تفسيره بمسار نسبي.
    internal fun servingDirectory(path: String?): File? =
        path?.let(::File)?.takeIf { it.isAbsolute }?.let { File(it, "serving") }

    internal fun liveServingRoots(context: Context): List<File> = runCatching {
        val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
        PACKS.mapNotNull { pack ->
            runCatching { servingDirectory(manager.getPackLocation(pack)?.assetsPath()) }.getOrNull()
        }
    }.getOrElse {
        Log.d(TAG, "asset packs unavailable", it)
        emptyList()
    }

    /** يعيد عدد الدروس التي بُذرت في هذه المرّة. */
    suspend fun seed(context: Context, lessons: List<Lesson>): Int = withContext(Dispatchers.IO) {
        seedAvailable(LocalStore.get(context), lessons, liveServingRoots(context))
    }

    internal fun seedAvailable(store: LocalStore, lessons: List<Lesson>, roots: List<File>): Int {
        val bySha = lessons.filter { isServingAudioSha(it.sha256) }.groupBy { it.sha256 }
        if (bySha.isEmpty() || roots.isEmpty()) return 0
        val seeded = HashMap<String, Triple<String, String, Long>>()
        for (dir in roots) for (file in dir.listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".ogg")) continue
            val aliases = bySha[file.name.removeSuffix(".ogg")] ?: continue
            val length = file.length()
            if (length <= 0L) continue
            for (lesson in aliases) {
                if (lesson.sizeBytes > 0L && lesson.sizeBytes != length) continue
                seeded.putIfAbsent(lesson.id, Triple(file.absolutePath, lesson.sha256, length))
            }
        }
        val count = store.seedBundledDownloads(seeded)
        if (count != 0) Log.i(TAG, "seeded $count lessons from asset packs")
        return count
    }

    /** One completed pack, one validated build member. Store retains deletion/removal guards. */
    internal suspend fun seedExpected(context: Context, pack: String, expected: Map<String, String>) =
        withContext(Dispatchers.IO) {
            if (pack !in PACKS || expected.isEmpty()) return@withContext
            val store = LocalStore.get(context)
            val root = runCatching {
                val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
                servingDirectory(manager.getPackLocation(pack)?.assetsPath())
            }.getOrNull() ?: return@withContext
            if (store.libraryPackMode() != LibraryPackMode.AUTOMATIC || store.downloadQueuePaused()) {
                return@withContext
            }
            val current = ContentRepository.get(context).state.value.lessonById
            val queued = store.downloadQueue().toHashSet()
            val autonomous = store.autoDownloadQueuedIds()
            val candidates = HashMap<String, Triple<String, String, Long>>()
            for ((id, sha) in expected) {
                if (id !in queued || id !in autonomous || current[id]?.sha256 != sha ||
                    sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' }) continue
                val file = File(root, "$sha.ogg")
                val length = file.length()
                val expectedSize = current[id]?.sizeBytes ?: 0L
                if (file.isFile && length > 0L && (expectedSize <= 0L || expectedSize == length)) {
                    candidates[id] = Triple(file.absolutePath, sha, length)
                }
            }
            store.seedBundledDownloads(candidates)
        }

    /** حجم ما وصل من حزم المتجر على القرص (لعرضه في شاشة التنزيلات). */
    fun bundledBytes(context: Context): Long = runCatching {
        val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
        PACKS.sumOf { pack ->
            val dir = servingDirectory(manager.getPackLocation(pack)?.assetsPath())
            dir?.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }.getOrDefault(0L)

    /**
     * إزالة المكتبة المدمجة لتحرير المساحة — الطريق الوحيد الصحيح لحذف ملفّات
     * الحزم (حذفُها ملفّاً ملفّاً يُفسدها). لا نعيد طلبها تلقائياً بعد هذا المسح؛
     * يظل التنزيل اليدوي والبث ممكنَين.
     */
    suspend fun removeAll(context: Context): Set<String> = withContext(Dispatchers.Main.immediate) {
        val app = context.applicationContext
        // Revokes old probes and installs the persistent reseed block before asking Play.
        if (!LibraryPackDelivery.stopForPackRemoval(app)) return@withContext PACKS.toSet()
        try {
            val manager = runCatching { AssetPackManagerFactory.getInstance(app) }.getOrNull()
            if (!LibraryPackDelivery.isPlayInstalled(app) && !hasOutstandingPackRemoval() && manager != null && PACKS.all { pack ->
                    runCatching { manager.getPackLocation(pack) == null }.getOrDefault(false)
                }) {
                LocalStore.get(app).confirmLibraryPackRemoval()
                return@withContext emptySet()
            }
            if (manager == null) return@withContext PACKS.toSet()
            val unconfirmed = PlayPackRemoval.unconfirmed(
                packs = PACKS,
                observe = { pack ->
                    runCatching { manager.getPackStates(listOf(pack)) }.getOrNull()
                        ?.removalResult()?.getOrNull()?.packStates()?.get(pack)?.status()
                },
                installed = { pack -> runCatching { manager.getPackLocation(pack) != null }.getOrNull() },
                remove = { pack ->
                    val task = removalTasks[pack]?.takeUnless { it.isComplete }
                        ?: runCatching { manager.removePack(pack) }.getOrNull()?.also { removalTasks[pack] = it }
                    task?.removalResult()?.isSuccess == true
                },
                settle = { delay(250L) },
            ) + removalTasks.filterValues { !it.isComplete }.keys
            if (unconfirmed.isEmpty()) LocalStore.get(app).confirmLibraryPackRemoval()
            unconfirmed
        } finally {
            LibraryPackDelivery.finishPackRemoval()
        }
    }

    private suspend fun <T> Task<T>.removalResult(): Result<T>? = withTimeoutOrNull(1_000L) {
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (continuation.isActive) {
                    continuation.resume(
                        if (task.isSuccessful) Result.success(task.result)
                        else Result.failure(task.exception ?: IllegalStateException("Play task canceled")),
                    )
                }
            }
        }
    }
}
