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

    /** يطلب إحضار الحزم إن لم تصل بعد (fast-follow تصل وحدها؛ هذا احتياط بعد مسح البيانات). */
    fun ensureFetched(context: Context) {
        runCatching {
            val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
            val missing = PACKS.filter { manager.getPackLocation(it) == null }
            if (missing.isNotEmpty()) manager.fetch(missing)
        }.onFailure { Log.d(TAG, "fetch skipped: $it") }
    }
}
