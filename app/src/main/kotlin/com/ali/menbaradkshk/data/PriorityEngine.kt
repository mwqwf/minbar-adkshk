package com.ali.menbaradkshk.data

import android.content.Context
import java.io.File

/// 🎯 محرك أولوية التنزيل التلقائي — معمارية «المكتبة الكاملة».
///
/// يبني قائمة مرشّحين بطبقات صارمة (T0 سياق فوري ← T1 نية معلنة ← T2 منبر ←
/// T3 اهتمام مشتق ← T4 إكمال الأرشيف)، وداخل كل شريحة **الأقل بايتات متبقية
/// أولاً**: الملف الجزئي الموجود على القرص يُحتسب، فإكمال نصف ملفٍ أرخص من
/// بدء ملف جديد. كل الحسابات على خرائط مسبقة — لا مسح O(n²).
object PriorityEngine {

    /// حجم افتراضي حين يجهل الخادم `sizeBytes` — نفس تقدير جدولة UIDT.
    private const val DEFAULT_SIZE_BYTES = 8L * 1_024 * 1_024

    /// «استماع حديث» لسلسلةٍ = ظهور في السجل خلال هذه المدة.
    private const val RECENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1_000

    /// «الأحدث نشراً» في طبقة المنبر = آخر ثلاثين يوماً.
    private const val FRESH_WINDOW_MS = 30L * 24 * 60 * 60 * 1_000

    fun plan(context: Context, budgetBytes: Long, maxItems: Int): List<Lesson> {
        if (maxItems <= 0 || budgetBytes <= 0L) return emptyList()
        val store = LocalStore.get(context)
        val content = ContentRepository.get(context)
        val downloads = DownloadRepository.get(context)
        val state = content.state.value
        val lessons = state.lessons.filter { it.audioUrl.isNotBlank() }
        if (lessons.isEmpty()) return emptyList()

        // ---- خرائط مسبقة ----
        val downloadedIds = store.downloads().keys
        val userDeleted = store.userDeletedDownloadIds()
        val staleIds = downloads.staleDownloadIds(lessons).toSet()
        val positions = store.positions()
        val completed = store.completedIds().toSet()
        val recentPlayed = store.recentPlayedIds()
        val playCounts = store.playCounts()
        val followed = store.followedSubcategories().toSet()
        val favorites = store.favoriteIds().toSet()
        val playlistIds = store.playlists().flatMap { it.lessonIds }.toSet()
        val historyStamps = content.historyStamps()
        val now = System.currentTimeMillis()
        val lessonById = state.lessonById
        val lessonsBySub = lessons.groupBy(Lesson::subcategoryId)
            .mapValues { (_, list) -> list.sortedBy(Lesson::createdAtMs) }
        val partsDir = File(context.filesDir, "lessons")

        // البايتات المتبقية: الحجم المعلوم (أو الافتراضي) ناقص الجزئي على
        // القرص — بنفس قاعدة تسمية DownloadRepository: `<safeId>.<ext>.part`.
        val remainingCache = HashMap<String, Long>(maxItems * 2)
        fun bytesRemaining(lesson: Lesson): Long = remainingCache.getOrPut(lesson.id) {
            val total = lesson.sizeBytes.takeIf { it > 0L } ?: DEFAULT_SIZE_BYTES
            val safeId = lesson.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val raw = android.net.Uri.parse(lesson.audioUrl).lastPathSegment
                ?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            val extension = when (raw) {
                "mp3", "m4a", "aac", "wav", "flac", "amr", "opus", "ogg" -> raw
                "ogx", "oga", "ogv" -> "ogg"
                "m4b", "mp4" -> "m4a"
                "3gp", "3gpp" -> "3gp"
                else -> "mp3"
            }
            val partial = File(partsDir, "$safeId.$extension.part").length()
            (total - partial).coerceAtLeast(0L)
        }

        // ---- التجميع بالطبقات ----
        val picked = LinkedHashSet<String>()
        var spent = 0L
        val result = ArrayList<Lesson>(maxItems)

        /// يضيف شريحةً مرتَّبةً بالأقل بايتات متبقية، محترماً الميزانية والسقف
        /// والاستثناءات (المنزَّل — إلا stale، والمحذوف يدوياً، والمكرَّر).
        fun take(candidates: List<Lesson>, allowDownloaded: Boolean = false) {
            if (result.size >= maxItems) return
            candidates.asSequence()
                .filter { it.id !in picked && it.id !in userDeleted }
                .filter { allowDownloaded || it.id !in downloadedIds }
                .sortedBy(::bytesRemaining)
                .forEach { lesson ->
                    if (result.size >= maxItems) return
                    val cost = bytesRemaining(lesson)
                    if (spent + cost > budgetBytes) return
                    picked += lesson.id
                    spent += cost
                    result += lesson
                }
        }

        // T0-أ: stale لسلسلة فيها استماع حديث — إصلاح ما يسمعه الآن أولاً.
        val recentSubs = lessons.asSequence()
            .filter { (historyStamps[it.id] ?: 0L) >= now - RECENT_WINDOW_MS }
            .map(Lesson::subcategoryId).toSet()
        val staleLessons = staleIds.mapNotNull(lessonById::get)
        take(staleLessons.filter { it.subcategoryId in recentSubs }, allowDownloaded = true)

        // T0-ب: التالي غير المنزَّل في السلسلة الجارية (أحدث درس في السجل).
        recentPlayed.firstOrNull()?.let(lessonById::get)?.let { current ->
            val series = lessonsBySub[current.subcategoryId].orEmpty()
            val index = series.indexOfFirst { it.id == current.id }
            if (index >= 0) {
                val next = series.drop(index + 1).firstOrNull { it.id !in downloadedIds }
                if (next != null) take(listOf(next))
            }
        }

        // T0-ج: بدأه ولم يتمه وغير منزَّل — سيعود إليه غالباً.
        take(
            lessons.filter {
                (positions[it.id] ?: 0L) > 0L && it.id !in completed
            },
        )

        // T1-أ: بقية stale — إصلاح مكتبته كلّها قبل أي جديد.
        take(staleLessons, allowDownloaded = true)

        // T1-ب: جديد الأقسام المتابَعة — الأحدث أولاً كترتيبٍ ثانوي: فرز
        // take بالبايتات **مستقرّ**، فالترتيب المُمرَّر يحسم التعادل (وكل
        // مجهول الحجم متعادل على الافتراضي).
        take(lessons.filter { it.subcategoryId in followed }.sortedByDescending(Lesson::createdAtMs))

        // T1-ج: المفضّلة وقوائم التشغيل.
        take(lessons.filter { it.id in favorites || it.id in playlistIds })

        // T1-د: إكمال أقسام نزّل ≥50٪ من دروسها — نية تنزيل شبه معلنة.
        val downloadedBySub = HashMap<String, Int>()
        downloadedIds.forEach { id ->
            lessonById[id]?.let { downloadedBySub.merge(it.subcategoryId, 1, Int::plus) }
        }
        val halfDownloadedSubs = downloadedBySub.filter { (subId, count) ->
            val total = lessonsBySub[subId]?.size ?: 0
            total > 0 && count * 2 >= total
        }.keys
        take(lessons.filter { it.subcategoryId in halfDownloadedSubs })

        // T2: اختيار المنبر — المميَّز ثم الأحدث نشراً (آخر 30 يوماً).
        take(content.featured(maxItems))
        take(
            lessons.filter { it.createdAtMs >= now - FRESH_WINDOW_MS }
                .sortedByDescending(Lesson::createdAtMs),
        )

        // T3: الأقسام الأعلى استماعاً عنده (مجموع playCounts لكل قسم فرعي).
        val listenBySub = HashMap<String, Long>()
        playCounts.forEach { (id, count) ->
            lessonById[id]?.let { listenBySub.merge(it.subcategoryId, count, Long::plus) }
        }
        val topSubs = listenBySub.entries.sortedByDescending { it.value }.map { it.key }
        topSubs.forEach { subId -> take(lessonsBySub[subId].orEmpty()) }

        // T4: إكمال الأرشيف — قسماً قسماً ثم الأشهر (views) تنازلياً.
        take(lessons.sortedWith(compareBy(Lesson::subcategoryId).thenByDescending(Lesson::views)))

        return result
    }
}
