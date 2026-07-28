package com.ali.menbaradkshk.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.max
import kotlin.random.Random

data class ContentState(
    val categories: List<Category> = emptyList(),
    val subcategories: List<Subcategory> = emptyList(),
    val lessons: List<Lesson> = emptyList(),
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
) {
    val lessonById: Map<String, Lesson> get() = lessons.associateBy(Lesson::id)
    val categoryById: Map<String, Category> get() = categories.associateBy(Category::id)
    val subcategoryById: Map<String, Subcategory> get() = subcategories.associateBy(Subcategory::id)
}

class ContentRepository private constructor(context: Context) {
    private val store = LocalStore.get(context)
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val _state = MutableStateFlow(
        ContentState(
            categories = store.categories(),
            subcategories = store.subcategories(),
            lessons = mergeDurations(store.lessons()).filter(Lesson::isPublished),
            loading = store.categories().isEmpty() && store.lessons().isEmpty(),
        ),
    )
    val state: StateFlow<ContentState> = _state.asStateFlow()

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - store.lastSyncMs() < SYNC_INTERVAL_MS && _state.value.lessons.isNotEmpty()) {
            return@withContext
        }
        _state.value = _state.value.copy(syncing = true, error = null)
        runCatching {
            coroutineScope {
                val categories = async {
                    db.collection("categories").get().await().documents.map { document ->
                        Category.fromMap(document.id, document.data.orEmpty())
                    }
                }
                val subcategories = async {
                    db.collection("subcategories").get().await().documents.map { document ->
                        Subcategory.fromMap(document.id, document.data.orEmpty())
                    }
                }
                val lessons = async {
                    db.collection("lessons").get().await().documents.map { document ->
                        Lesson.fromMap(document.id, document.data.orEmpty())
                    }.filter(Lesson::isPublished)
                }
                val values = awaitAll(categories, subcategories, lessons)
                @Suppress("UNCHECKED_CAST")
                Triple(
                    values[0] as List<Category>,
                    values[1] as List<Subcategory>,
                    values[2] as List<Lesson>,
                )
            }
        }.onSuccess { (categories, subcategories, lessons) ->
            store.setCategories(categories)
            store.setSubcategories(subcategories)
            store.setLessons(lessons)
            store.setLastSyncMs(now)
            // تنظيف الملفات اليتيمة لدروس أُزيلت من الخادم.
            store.pruneDownloads(lessons.map(Lesson::id).toSet())
            _state.value = ContentState(
                categories = categories,
                subcategories = subcategories,
                lessons = mergeDurations(lessons),
                loading = false,
                syncing = false,
            )
        }.onFailure { failure ->
            val cached = _state.value
            _state.value = cached.copy(
                loading = false,
                syncing = false,
                offline = true,
                error = if (cached.lessons.isEmpty()) {
                    "تعذّر الاتصال ولا توجد بيانات محفوظة بعد."
                } else {
                    "أنت تستعرض نسخة محفوظة على الجهاز."
                },
            )
        }
    }

    fun refreshPersonalization() {
        _state.value = _state.value.copy(lessons = mergeDurations(_state.value.lessons))
    }

    fun lessonsForSubcategory(subcategoryId: String): List<Lesson> =
        _state.value.lessons
            .filter { it.subcategoryId == subcategoryId }
            .sortedBy(Lesson::createdAtMs)

    fun subcategoriesForCategory(categoryId: String): List<Subcategory> =
        _state.value.subcategories
            .filter { it.categoryId == categoryId }
            .sortedByDescending(Subcategory::createdAtMs)

    /// كل دروس قسم رئيسي (عبر كل أقسامه الفرعية) — للتحميل الجماعي.
    fun lessonsForCategory(categoryId: String): List<Lesson> =
        _state.value.lessons
            .filter { it.categoryId == categoryId }
            .sortedBy(Lesson::createdAtMs)

    fun newest(limit: Int = 15): List<Lesson> =
        withAudio().sortedByDescending(Lesson::createdAtMs).take(limit)

    /**
     * «مختارات المنبر» — التمييز صار مؤقّتاً: ما انقضت مدّته يسقط هنا فوراً
     * دون انتظار تنظيف الخادم، فلا يظهر للمستخدم درس انتهى تمييزه.
     */
    fun featured(limit: Int = 12): List<Lesson> {
        val now = System.currentTimeMillis()
        return withAudio()
            .filter { it.featured && (it.featuredUntilMs <= 0L || it.featuredUntilMs > now) }
            .sortedByDescending(Lesson::createdAtMs)
            .take(limit)
    }

    fun mostListened(limit: Int = 15): List<Lesson> =
        withAudio().filter { it.views > 0L }
            .sortedWith(compareByDescending<Lesson> { it.views }.thenByDescending { it.createdAtMs })
            .take(limit)

    fun continueListening(): List<Lesson> {
        val byId = _state.value.lessonById
        val completed = store.completedIds().toSet()
        val positions = store.positions()
        return store.recentPlayedIds().mapNotNull { id ->
            byId[id]?.takeIf { id !in completed && (positions[id] ?: 0L) > 3_000L }
        }
    }

    fun unfinished(): List<Lesson> {
        val completed = store.completedIds().toSet()
        val positions = store.positions()
        return _state.value.lessons.filter {
            it.id !in completed && (positions[it.id] ?: 0L) > 3_000L
        }.sortedByDescending { positions[it.id] ?: 0L }
    }

    fun favorites(): List<Lesson> {
        val byId = _state.value.lessonById
        return store.favoriteIds().mapNotNull(byId::get)
    }

    fun recommended(limit: Int = 50): List<Lesson> {
        val pool = withAudio()
        val subVisits = store.subcategoryVisits()
        val categoryVisits = store.categoryVisits()
        val playCounts = store.playCounts()
        val completed = store.completedIds().toSet()
        if (subVisits.isEmpty() && categoryVisits.isEmpty() && playCounts.isEmpty()) {
            return pool.sortedByDescending(Lesson::createdAtMs).take(limit)
        }
        fun score(lesson: Lesson): Double =
            (subVisits[lesson.subcategoryId] ?: 0L) * 3.0 +
                (categoryVisits[lesson.categoryId] ?: 0L) * 1.5 +
                lesson.views * 0.05 -
                if ((playCounts[lesson.id] ?: 0L) > 0L) 2.0 else 0.0 -
                if (lesson.id in completed) 4.0 else 0.0
        return pool.sortedWith(
            compareByDescending<Lesson>(::score).thenByDescending(Lesson::createdAtMs),
        ).take(limit)
    }

    fun trending(limit: Int = 15): List<Lesson> {
        val now = System.currentTimeMillis()
        fun score(lesson: Lesson): Double {
            val ageDays = max(0L, (now - lesson.createdAtMs) / 86_400_000L).coerceAtMost(3_650)
            return lesson.views * (0.5 + 1.0 / (1 + ageDays / 30.0))
        }
        return withAudio().filter { it.views > 0L }
            .sortedByDescending(::score)
            .take(limit)
    }

    /// دروس في أقسام فرعية زارها المستخدم ولم يُكملها كلها (استكمل قسمك).
    fun continueSection(limit: Int = 15): List<Lesson> {
        val subVisits = store.subcategoryVisits()
        if (subVisits.isEmpty()) return emptyList()
        val completed = store.completedIds().toSet()
        val positions = store.positions()
        val sortedSubs = subVisits.entries.sortedByDescending { it.value }
        val result = mutableListOf<Lesson>()
        for (entry in sortedSubs) {
            for (lesson in lessonsForSubcategory(entry.key)) {
                if (lesson.id in completed) continue
                if ((positions[lesson.id] ?: 0L) > 0L || subVisits.containsKey(lesson.subcategoryId)) {
                    result += lesson
                    if (result.size >= limit) return result
                }
            }
        }
        return result
    }

    fun randomSectionToday(limit: Int = 12): List<Lesson> {
        val subs = _state.value.subcategories
        if (subs.isEmpty()) return emptyList()
        val now = LocalDate.now()
        val sub = subs[(now.dayOfMonth + now.monthValue * 31) % subs.size]
        return lessonsForSubcategory(sub.id).take(limit)
    }

    fun dailyWard(): Lesson? {
        val pool = withAudio().sortedBy(Lesson::id)
        if (pool.isEmpty()) return null
        val now = LocalDate.now()
        return pool[(now.year * 1_000 + now.monthValue * 40 + now.dayOfMonth) % pool.size]
    }

    fun shortStation(): List<Lesson> = withAudio().filter {
        val duration = if (it.durationMs > 0L) it.durationMs else store.duration(it.id)
        duration in 1L until 10 * 60 * 1_000L
    }.sortedByDescending(Lesson::createdAtMs)

    fun randomStation(): List<Lesson> = withAudio().shuffled(Random(System.currentTimeMillis()))

    fun similarTo(lesson: Lesson, limit: Int = 20): List<Lesson> =
        withAudio().filterNot { it.id == lesson.id }.sortedWith(
            compareBy<Lesson> {
                when {
                    lesson.subcategoryId.isNotBlank() && it.subcategoryId == lesson.subcategoryId -> 0
                    lesson.categoryId.isNotBlank() && it.categoryId == lesson.categoryId -> 1
                    else -> 2
                }
            }.thenByDescending { it.views }.thenByDescending { it.createdAtMs },
        ).take(limit)

    fun seriesProgress(subcategoryId: String): Pair<Int, Int> {
        val items = lessonsForSubcategory(subcategoryId).filter { it.audioUrl.isNotBlank() }
        val completed = store.completedIds().toSet()
        return items.count { it.id in completed } to items.size
    }

    suspend fun incrementView(lessonId: String) {
        if (lessonId.isBlank() || store.isViewCountedToday(lessonId)) return
        runCatching {
            ensureSignedIn()
            functions.getHttpsCallable("incrementLessonView")
                .call(mapOf("lessonId" to lessonId)).await()
        }.onSuccess { store.markViewCounted(lessonId) }
    }

    suspend fun sendFeedback(lessonId: String, type: String, note: String) {
        ensureSignedIn()
        functions.getHttpsCallable("sendFeedback").call(
            mapOf("lessonId" to lessonId, "type" to type, "note" to note.trim()),
        ).await()
    }

    private suspend fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    private fun withAudio(): List<Lesson> =
        _state.value.lessons.filter { it.audioUrl.isNotBlank() && it.isPublished }

    private fun mergeDurations(items: List<Lesson>): List<Lesson> = items.map { lesson ->
        val local = store.duration(lesson.id)
        if (lesson.durationMs <= 0L && local > 0L) lesson.copy(durationMs = local) else lesson
    }

    companion object {
        private const val SYNC_INTERVAL_MS = 2 * 60 * 1_000L
        @Volatile private var instance: ContentRepository? = null
        fun get(context: Context): ContentRepository = instance ?: synchronized(this) {
            instance ?: ContentRepository(context.applicationContext).also { instance = it }
        }
    }
}
