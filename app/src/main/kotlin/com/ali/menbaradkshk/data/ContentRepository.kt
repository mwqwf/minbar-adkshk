package com.ali.menbaradkshk.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    // فهارس مبنيّة مرّة واحدة لكل حالة لا عند كل قراءة: كانت `get()` تُعيد
    // بناء الخريطة كاملة في كل استدعاء، وهي تُقرأ داخل قوائم الواجهة لكل عنصر
    // على حدة (اسم القسم لكل بطاقة) — أي مرور كامل على مئات الدروس لكل صفّ في
    // كل إعادة تركيب. الحالة غير قابلة للتغيير فالبناء الكسول آمن.
    val lessonById: Map<String, Lesson> by lazy { lessons.associateBy(Lesson::id) }
    val categoryById: Map<String, Category> by lazy { categories.associateBy(Category::id) }
    val subcategoryById: Map<String, Subcategory> by lazy {
        subcategories.associateBy(Subcategory::id)
    }
}

class ContentRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    /// تفضيلات خاصّة بالمستودع وحده (علامات المسبار، إخفاء عناصر السجل،
    /// ترتيب قوائم التشغيل) — لا تُخلط بمخزن التطبيق العام.
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance() }
    // قراءة واحدة لكل مخزن: كل استدعاء يعيد تحليل JSON كامل من التفضيلات
    // على خيط الإقلاع، وكان يتكرّر مرّتين للأقسام والدروس بلا داعٍ.
    private val _state = MutableStateFlow(
        run {
            val cachedCategories = store.categories()
            val cachedLessons = store.lessons()
            if (cachedCategories.isEmpty() && cachedLessons.isEmpty()) {
                // 🎁 أول تشغيل بلا أي كاش: لقطة الكتالوج المضمّنة وقت البناء
                // تجعل المكتبة كلّها قابلة للتصفّح فوراً وبلا إنترنت إطلاقاً
                // (عناوين/أقسام/شيوخ/مدد) — والدروس تنتظر أول اتصال. تُقرأ
                // مرة واحدة في العمر هنا؛ وأي فشل يُعيد الشاشة الفارغة القديمة.
                val seeded = readBundledSnapshot(appContext)
                ContentState(
                    categories = seeded?.categories.orEmpty(),
                    subcategories = seeded?.subcategories.orEmpty(),
                    lessons = mergeDurations(seeded?.lessons.orEmpty()),
                    loading = seeded == null,
                    offline = seeded != null,
                )
            } else {
                ContentState(
                    categories = cachedCategories,
                    subcategories = store.subcategories(),
                    lessons = mergeDurations(cachedLessons),
                    loading = false,
                )
            }
        },
    )
    val state: StateFlow<ContentState> = _state.asStateFlow()

    /// نطاق خاص بالمستودع: التحديث الصريح لا يُلغى بمغادرة الشاشة، فلا تبقى
    /// حالة «جارٍ التحديث» عالقة إن انصرف المستخدم أثناء السحب-للتحديث.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deepJob: Job? = null

    /// تحديث كامل صريح يتخطّى المسبار (سحب-للتحديث و«إعادة المحاولة»).
    fun requestDeepRefresh() {
        if (deepJob?.isActive == true) return
        deepJob = scope.launch { refresh(force = true, deep = true) }
    }

    /**
     * مزامنة المحتوى.
     *
     * «الطزاجة الفوريّة عند الفتح» قرار منتج مقصود ويبقى: كل عودة للتطبيق
     * تستدعي هذه الدالة بـ[force]=true. الجديد أنّها لم تعد تُنزّل مجموعة
     * الدروس كاملة في كل عودة (حوار صلاحية، تدوير، رجوع من الكاميرا…): يسبقها
     * **مسبار رخيص** — ثلاثة عدّادات تجميعيّة `count()` واستعلام وثيقة واحدة
     * لأحدث `updatedAt` — فإن طابق المخزَّن لم يُجلب شيء إطلاقاً.
     *
     * [deep] يتخطّى المسبار ويجلب كل شيء (سحب-للتحديث اليدوي).
     */
    /// 🔒 قفل المزامنة: عودةٌ للتطبيق وسحبٌ للتحديث قد يتزامنان فيجريان
    /// جلبين كاملين متوازيين — يكتب المتأخّرُ الأقدمَ فوق الأحدث ويُضاعف
    /// استهلاك البيانات. المتأخّر ينتظر انتهاء الأوّل ثم يقرّر بنفسه (وغالباً
    /// يخرج فوراً: المسبار/الخانق يريانه غير لازم).
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    /// أقدم لحظة يضمن الخادم أن سجلّ الحذف يغطّيها (تصل مع وثيقة البصمة).
    @Volatile private var serverDeltaFloorMs: Long = 0L

    suspend fun refresh(
        force: Boolean = false,
        deep: Boolean = false,
    ) = refreshMutex.withLock { refreshLocked(force, deep) }

    private suspend fun refreshLocked(force: Boolean, deep: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hasCache = _state.value.lessons.isNotEmpty()
        if (!force && now - store.lastSyncMs() < SYNC_INTERVAL_MS && hasCache) {
            return@withContext
        }
        // علامات الخادم كما رآها المسبار: تُحفظ حرفياً بعد أي جلب كامل بدل
        // علاماتٍ محسوبة من استعلاماتنا — كانت المحسوبة تخالف بصمة الخادم
        // حتماً فتفتح حلقة جلب كامل دائمة (لغم 2026-08-29).
        var probedServer: ProbeMarks? = null
        // المسبار لا يُستعمل إلا مع كاش موجود وعلامات محفوظة من مزامنة سابقة؛
        // وأوّل تشغيل يجلب كل شيء كالمعتاد.
        if (!deep && hasCache) {
            val stored = storedMarks()
            if (stored != null) {
                // حدّ أدنى بين مسبارين: عودات متلاحقة لا تُكلّف شيئاً.
                if (now - lastProbeMs() < PROBE_INTERVAL_MS) return@withContext
                val server = probe()
                probedServer = server
                setLastProbeMs(now)
                // فشل المسبار (انقطاع/رفض) يسقط إلى الجلب الكامل كما كان.
                if (server != null && server == stored) {
                    // ✅ المسبار أكّد للتوّ أنّ المحفوظ مطابق للخادم: راية
                    // offline ورسالة «نسخة محفوظة» من فشلٍ سابق صارتا كاذبتين
                    // — كانتا تبقيان ظاهرتين حتى يتغيّر المحتوى فعلاً أو يسحب
                    // المستخدم للتحديث يدوياً رغم أن الاتصال عاد.
                    val current = _state.value
                    if (current.offline || current.error != null) {
                        _state.value = current.copy(offline = false, error = null)
                    }
                    return@withContext
                }
                // 🪶 تصحيح حرفٍ في اسم قسم كان يُنزّل المكتبة كلّها من جديد:
                // أي اختلاف في البصمة يُسقط الكاش ويُعاد جلب مئات الوثائق —
                // دقائق انتظار وميغابايتات من رصيدٍ مدفوع. الآن نجلب ما
                // **تغيّر وحده** وندمجه في المحفوظ، ولا نعود إلى الجلب
                // الكامل إلا حين يكون هو الأصحّ أو الأرخص.
                if (server != null) {
                    _state.value = _state.value.copy(syncing = true, error = null)
                    if (applyDelta(stored, server, now)) return@withContext
                }
            }
        }
        _state.value = _state.value.copy(syncing = true, error = null)
        runCatching {
            // الجلب الكامل عبر واجهة الكتالوج أولاً: **طلب HTTP واحد** مضغوط
            // (~70 ك.ب) من كاش CDN بدل مئات قراءات الوثائق — وFirestore يبقى
            // احتياطاً حرفياً كاملاً عند أي فشل أو نقص.
            // ⚠️ حارس النسخة الباردة: كاش CDN قد يقدّم كتالوجاً أقدم من حالة
            // الخادم التي رآها المسبار للتو (stale-while-revalidate) — قبوله
            // كان **يرتدّ** بالأجهزة إلى بيانات بائدة تدوس الأحدث (وقع فعلياً
            // في اختبار القبول ليلة الهجرة). اللقطة تُقبل فقط إن كانت بعمر
            // علامات الخادم أو أحدث وبعدد دروسه نفسه، وإلا فFirestore.
            fetchCatalogSnapshot()?.takeIf { snapshot ->
                val server = probedServer
                server == null || (
                    snapshot.maxUpdatedMs >= server.maxUpdatedMs &&
                        snapshot.lessons.size == server.lessons
                    )
            } ?: fetchFirestoreSnapshot(hasCache)
        }.onSuccess { snapshot ->
            val categories = snapshot.categories
            val subcategories = snapshot.subcategories
            // ⛔ لا ترشيح: النشر المجدول أُزيل من المنظومة كلّها (اللوحة
            // والدوال السحابيّة)، فكلّ ما يصل من الخادم منشور بحكم وجوده.
            val lessons = snapshot.lessons
            store.setCategories(categories)
            store.setSubcategories(subcategories)
            store.setLessons(lessons)
            store.setLastSyncMs(now)
            // ⚠️ العلامات تُشتق من **اللقطة المخزَّنة نفسها** لا من وثيقة
            // البصمة: حفظ علامات الخادم مع بياناتٍ قد تكون دونها (كتالوج من
            // كاش CDN) سمّم الحالة مرة — علامات حديثة فوق بيانات بائدة فلا
            // مسبار يختلف ولا دلتا تشفي. المشتقة تصف ما خُزّن فعلاً: إن كانت
            // اللقطة خلف الخادم اختلف المسبار التالي وجلبت الدلتا الفارق.
            // (بعد إصلاح بصمة الخادم 2026-08-29 تساويان عند التطابق أصلاً.)
            saveMarks(
                ProbeMarks(
                    categories = categories.size,
                    subcategories = subcategories.size,
                    lessons = snapshot.lessons.size,
                    maxUpdatedMs = snapshot.maxUpdatedMs,
                    categoriesUpdatedMs = snapshot.categoriesUpdatedMs,
                    subcategoriesUpdatedMs = snapshot.subcategoriesUpdatedMs,
                ),
            )
            setLastProbeMs(now)
            // نقطة انطلاق سجلّ الحذف: الجلب الكامل حصر الموجود فعلاً، فما
            // قبل هذه اللحظة لا يعنينا. والتراجع دقيقتين يحتاط لفارق ساعة
            // الخادم عن ساعة الجهاز — وإعادة حذف ما ليس موجوداً لا تضرّ.
            setDeleteMark(now - DELETE_MARK_BACKOFF_MS)
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
            // ❗ runCatching يلتقط الإلغاء أيضاً: مغادرة التطبيق أثناء المزامنة
            // كانت تُسجَّل «فشلاً» في المستودع المفرد الباقي بعد الشاشة، فتبقى
            // راية offline ورسالة «نسخة محفوظة» ظاهرتين عند إعادة الفتح بلا سبب.
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val cached = _state.value
            _state.value = cached.copy(
                loading = false,
                syncing = false,
                offline = true,
                error = when {
                    cached.lessons.isEmpty() -> "تعذّر الاتصال ولا توجد بيانات محفوظة بعد."
                    // ⚠️ لم تكتمل مزامنة كاملة قط (أول تثبيت وصلت منه صفحة
                    // دروس ثم انقطع): لا شيء حُفظ على القرص (الحفظ في
                    // onSuccess وحده)، فادّعاء «نسخة محفوظة» كاذب ويطمئن
                    // المستخدم إلى مكتبة ناقصة ستختفي عند إعادة الفتح بلا نت.
                    store.lastSyncMs() == 0L ->
                        "انقطع الاتصال قبل اكتمال التحميل — اسحب للتحديث."
                    else -> "أنت تستعرض نسخة محفوظة على الجهاز."
                },
            )
        }
    }

    /// الجلب الكامل من Firestore — المسار الاحتياطي الحرفي القديم (صفحات
    /// تُرسم تباعاً في أول تثبيت) عندما تتعذر واجهة الكتالوج لأي سبب.
    private suspend fun fetchFirestoreSnapshot(hasCache: Boolean): Snapshot = coroutineScope {
        val categoriesJob = async {
            db.collection("categories").get().await().documents.map { document ->
                Category.fromMap(document.id, document.data.orEmpty())
            }
        }
        val subcategoriesJob = async {
            db.collection("subcategories").get().await().documents.map { document ->
                Subcategory.fromMap(document.id, document.data.orEmpty())
            }
        }
        val newest = async { newestUpdatedMs() }
        val newestCategories = async { newestUpdatedMs("categories") }
        val newestSubcategories = async { newestUpdatedMs("subcategories") }

        val categories = categoriesJob.await()
        val subcategories = subcategoriesJob.await()
        // 🚀 أوّل تثبيت: تُرسم المكتبة فور وصول الأقسام وتُملأ الدروس تباعاً.
        if (!hasCache) {
            _state.value = _state.value.copy(
                categories = categories,
                subcategories = subcategories,
                loading = false,
                syncing = true,
            )
        }
        val lessons = fetchLessonsPaged { page ->
            if (!hasCache) {
                _state.value = _state.value.copy(
                    lessons = mergeDurations(page),
                    loading = false,
                    syncing = true,
                )
            }
        }
        Snapshot(
            categories = categories,
            subcategories = subcategories,
            lessons = lessons,
            maxUpdatedMs = newest.await(),
            categoriesUpdatedMs = newestCategories.await(),
            subcategoriesUpdatedMs = newestSubcategories.await(),
        )
    }

    /**
     * الجلب الكامل عبر `/api/catalog`: طلب gzip واحد من كاش CDN يعيد المكتبة
     * كلّها، فيوفّر مئات قراءات الوثائق. `null` عند أي فشل أو شكّ في السلامة
     * (عدّ لا يطابق، JSON ناقص) — فيتولّى Firestore الأمر حرفياً كما كان.
     */
    private suspend fun fetchCatalogSnapshot(): Snapshot? = runCatching {
        val connection = java.net.URL(CATALOG_URL).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept-Encoding", "gzip")
        val body = try {
            if (connection.responseCode != 200) return@runCatching null
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding == "gzip") {
                java.util.zip.GZIPInputStream(raw)
            } else {
                raw
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        parseCatalog(JSONObject(body))
    }.getOrNull()

    /// يحوّل JSON الكتالوج (واجهة الموقع أو اللقطة المضمّنة) إلى Snapshot،
    /// مع تحقّق صارم: الأعداد المعلنة تطابق المصفوفات فعلاً وإلا رُفض كله.
    private fun parseCatalog(json: JSONObject): Snapshot? {
            val counts = json.optJSONObject("counts") ?: return null
            val categoriesJson = json.optJSONArray("categories") ?: return null
            val subcategoriesJson = json.optJSONArray("subcategories") ?: return null
            val lessonsJson = json.optJSONArray("lessons") ?: return null
            if (counts.optInt("categories", -1) != categoriesJson.length() ||
                counts.optInt("subcategories", -1) != subcategoriesJson.length() ||
                counts.optInt("lessons", -1) != lessonsJson.length() ||
                lessonsJson.length() == 0
            ) {
                return null
            }
            fun JSONObject.toDataMap(): Map<String, Any?> {
                val map = mutableMapOf<String, Any?>()
                keys().forEach { key -> map[key] = opt(key) }
                return map
            }
            val categories = (0 until categoriesJson.length()).mapNotNull { index ->
                val item = categoriesJson.optJSONObject(index) ?: return@mapNotNull null
                Category.fromMap(item.optString("id"), item.toDataMap())
            }
            val subcategories = (0 until subcategoriesJson.length()).mapNotNull { index ->
                val item = subcategoriesJson.optJSONObject(index) ?: return@mapNotNull null
                Subcategory.fromMap(item.optString("id"), item.toDataMap())
            }
            var maxLessons = 0L
            var maxCategories = 0L
            var maxSubcategories = 0L
            (0 until categoriesJson.length()).forEach { index ->
                maxCategories = max(
                    maxCategories,
                    categoriesJson.optJSONObject(index)?.optLong("updatedAtMs") ?: 0L,
                )
            }
            (0 until subcategoriesJson.length()).forEach { index ->
                maxSubcategories = max(
                    maxSubcategories,
                    subcategoriesJson.optJSONObject(index)?.optLong("updatedAtMs") ?: 0L,
                )
            }
            val lessons = (0 until lessonsJson.length()).mapNotNull { index ->
                val item = lessonsJson.optJSONObject(index) ?: return@mapNotNull null
                maxLessons = max(maxLessons, item.optLong("updatedAtMs"))
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                Lesson.fromMap(id, item.toDataMap()).let { lesson ->
                    // واجهة الكتالوج تعيد `createdAtMs` رقمياً بهذا الاسم.
                    if (lesson.createdAtMs == 0L) {
                        lesson.copy(createdAtMs = item.optLong("createdAtMs"))
                    } else {
                        lesson
                    }
                }
            }
            if (lessons.size != lessonsJson.length()) return null
            return Snapshot(
                categories = categories,
                subcategories = subcategories,
                lessons = lessons,
                maxUpdatedMs = maxLessons,
                categoriesUpdatedMs = maxCategories,
                subcategoriesUpdatedMs = maxSubcategories,
            )
        }

    /// لقطة الكتالوج المضمّنة وقت البناء (assets/catalog/snapshot.jz —
    /// ‏JSON مضغوط gzip بامتداد محايد كي لا يفكّه AGP). فشلها ليس خطأً:
    /// نسخ قديمة من الحزمة قد لا تحملها.
    private fun readBundledSnapshot(context: Context): Snapshot? = runCatching {
        context.assets.open("catalog/snapshot.jz").use { stream ->
            val text = java.util.zip.GZIPInputStream(stream)
                .bufferedReader().use { it.readText() }
            parseCatalog(JSONObject(text))
        }
    }.getOrNull()

    fun refreshPersonalization() {
        _state.value = _state.value.copy(lessons = mergeDurations(_state.value.lessons))
    }

    // ------------------------------------------------------------------
    // المسبار الرخيص (لا يجلب وثائق: ثلاثة عدّادات + وثيقة واحدة)
    // ------------------------------------------------------------------

    /// نتيجة الجلب الكامل — تُشتقّ منها علامات المسبار.
    private data class Snapshot(
        val categories: List<Category>,
        val subcategories: List<Subcategory>,
        val lessons: List<Lesson>,
        val maxUpdatedMs: Long,
        val categoriesUpdatedMs: Long = 0L,
        val subcategoriesUpdatedMs: Long = 0L,
    )

    /// بصمة حالة الخادم: أعداد المجموعات الثلاث + أحدث طابع تعديل في كلٍّ منها.
    ///
    /// ⚠️ طابعا الأقسام والفروع ليسا ترفاً: إعادة تسمية قسم تكتب `updatedAt`
    /// على وثيقته وحدها — لا عدّاد يتغيّر ولا طابع درس — فكانت البصمة تتطابق
    /// والاسم القديم يبقى معروضاً حتى يسحب المستخدم للتحديث يدويّاً.
    private data class ProbeMarks(
        val categories: Int,
        val subcategories: Int,
        val lessons: Int,
        val maxUpdatedMs: Long,
        val categoriesUpdatedMs: Long = 0L,
        val subcategoriesUpdatedMs: Long = 0L,
    )

    /**
     * يجلب الدروس على صفحات مرتَّبة بمعرّف الوثيقة، ويُبلّغ [onPage] بكل ما
     * تجمّع بعد كل صفحة.
     *
     * الترتيب بـ`__name__` مقصود: لا يحتاج فهرساً ولا يتأثّر بغياب حقل
     * `createdAt` عن الوثائق القديمة المغلَّفة، والترقيم به مستقرّ فلا تتكرّر
     * وثيقة ولا تسقط أخرى بين صفحتين.
     */
    private suspend fun fetchLessonsPaged(onPage: (List<Lesson>) -> Unit): List<Lesson> {
        val all = mutableListOf<Lesson>()
        var last: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            var query = db.collection("lessons")
                .orderBy(com.google.firebase.firestore.FieldPath.documentId())
                .limit(LESSONS_PAGE_SIZE)
            last?.let { query = query.startAfter(it) }
            val snapshot = query.get().await()
            if (snapshot.isEmpty) break
            all += snapshot.documents.map { document ->
                Lesson.fromMap(document.id, document.data.orEmpty())
            }
            onPage(all.toList())
            if (snapshot.size() < LESSONS_PAGE_SIZE) break
            last = snapshot.documents.last()
        }
        return all
    }

    /**
     * المسبار: **قراءة واحدة** لوثيقة البصمة `content_meta/state` التي تحدّثها
     * الدوال الخادمية (الأعداد الثلاثة + أحدث طابع تعديل في كل مجموعة).
     * إن وُجدت مكتملة الحقول اشتُقّت منها العلامات نفسها التي كان يشتقّها
     * المسار القديم؛ وإن غابت أو نقصت حقولها سقطنا إلى المسار القديم
     * (تسعة استعلامات) كما هو حرفياً — فمتى تُطلق المزامنة لا يتغيّر.
     */
    private suspend fun probe(): ProbeMarks? = metaProbe() ?: legacyProbe()

    /// يقرأ وثيقة البصمة ويشتقّ منها العلامات؛ `null` عند غيابها أو نقص أي
    /// حقل من الستة (فالسقوط للمسار القديم هو الأمان).
    private suspend fun metaProbe(): ProbeMarks? = runCatching {
        val document = db.collection("content_meta").document("state").get().await()
        if (!document.exists()) return@runCatching null
        // أرضية الدلتا تُقرأ هنا مجاناً (نفس الوثيقة) وتُحفظ جانباً — ليست
        // جزءاً من مساواة العلامات (تتقدم يومياً مع كنس سجل الحذف).
        (document.get("deltaFloorMs") as? Number)?.toLong()?.let { serverDeltaFloorMs = it }
        val lessons = (document.get("lessonsCount") as? Number)?.toInt()
        val categories = (document.get("categoriesCount") as? Number)?.toInt()
        val subcategories = (document.get("subcategoriesCount") as? Number)?.toInt()
        val lessonsUpdated = document.get("lessonsUpdatedAtMs")
        val categoriesUpdated = document.get("categoriesUpdatedAtMs")
        val subcategoriesUpdated = document.get("subcategoriesUpdatedAtMs")
        if (lessons == null || categories == null || subcategories == null ||
            lessonsUpdated == null || categoriesUpdated == null || subcategoriesUpdated == null
        ) {
            return@runCatching null
        }
        ProbeMarks(
            categories = categories,
            subcategories = subcategories,
            lessons = lessons,
            maxUpdatedMs = lessonsUpdated.timeMillis(),
            categoriesUpdatedMs = categoriesUpdated.timeMillis(),
            subcategoriesUpdatedMs = subcategoriesUpdated.timeMillis(),
        )
    }.getOrNull()

    private suspend fun legacyProbe(): ProbeMarks? = runCatching {
        coroutineScope {
            val categories = async { countOf("categories") }
            val subcategories = async { countOf("subcategories") }
            val lessons = async { countOf("lessons") }
            val newest = async { newestUpdatedMs() }
            val newestCategories = async { newestUpdatedMs("categories") }
            val newestSubcategories = async { newestUpdatedMs("subcategories") }
            ProbeMarks(
                categories = categories.await(),
                subcategories = subcategories.await(),
                lessons = lessons.await(),
                maxUpdatedMs = newest.await(),
                categoriesUpdatedMs = newestCategories.await(),
                subcategoriesUpdatedMs = newestSubcategories.await(),
            )
        }
    }.getOrNull()

    private suspend fun countOf(collection: String): Int =
        db.collection(collection).count().get(AggregateSource.SERVER).await().count.toInt()

    /// أحدث `updatedAt` في مجموعة (وثيقة واحدة لكل صيغة). الوثائق التي لا
    /// تحمل الحقل لا تدخل الاستعلام أصلاً، فغيابه كلّياً يعني صفراً ثابتاً.
    ///
    /// ⚠️ صيغتان لا واحدة: الوثائق القديمة المغلَّفة `{data:{…}}` يكتب فيها
    /// الطابعَ في `data.updatedAt`، فالاكتفاء بالحقل الأعلى يُبقيها خارج
    /// البصمة فلا يُلتقط تعديلها أبداً.
    private suspend fun newestUpdatedMs(collection: String = "lessons"): Long =
        coroutineScope {
            val plain = async { newestUpdatedBy(collection, "updatedAt") }
            val wrapped = async { newestUpdatedBy(collection, "data.updatedAt") }
            max(plain.await(), wrapped.await())
        }

    private suspend fun newestUpdatedBy(collection: String, field: String): Long = runCatching {
        db.collection(collection)
            .orderBy(field, Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.get(field)
            .timeMillis()
    }.getOrDefault(0L)

    // ------------------------------------------------------------------
    // المزامنة التفاضليّة: ما تغيّر وحده لا المكتبة كلّها
    // ------------------------------------------------------------------

    /**
     * يجلب ما تغيّر منذ آخر مزامنة ويدمجه في الكاش المحفوظ.
     *
     * يعيد `true` إن نجح واكتفى بذلك، و`false` إن تعذّر — وحينها يمضي
     * المستدعي إلى الجلب الكامل كما كان تماماً.
     *
     * ⚠️ **الصحّة قبل التوفير**: بعد الدمج نقارن الأعداد الثلاثة بأعداد
     * الخادم التي جاء بها المسبار. أي فرق — درسٌ جديد بلا طابع تعديل، حذفٌ
     * لم يُسجَّل، سجلّ حذفٍ انقضت مدّته — يُلغي التوفير ويُعيدنا إلى الجلب
     * الكامل. فلا يمكن أن يخسر المستخدم محتوى بهذا المسار.
     */
    private suspend fun applyDelta(
        stored: ProbeMarks,
        server: ProbeMarks,
        now: Long,
    ): Boolean = runCatching {
        // تراجعُ أي طابع يعني حالة خادم لا نفهمها (استعادة نسخة، تغيّر ساعة)
        // — لا نجازف بالدمج عليها.
        if (server.maxUpdatedMs < stored.maxUpdatedMs ||
            server.categoriesUpdatedMs < stored.categoriesUpdatedMs ||
            server.subcategoriesUpdatedMs < stored.subcategoriesUpdatedMs
        ) {
            return@runCatching false
        }
        // مجموعة بلا أي طابع تعديل (صفر عند الطرفين) لا يمكن تتبّعها
        // تفاضليّاً أصلاً؛ ومع ذلك يبقى فحص الأعداد أدناه حكماً نهائياً.
        val baseCategories = store.categories()
        val baseSubcategories = store.subcategories()
        val baseLessons = store.lessons()
        if (baseLessons.isEmpty()) return@runCatching false
        // بلا نقطة انطلاق لسجلّ الحذف لا نعرف ما فات المستخدمَ من حذف
        // (تحديث تطبيقٍ كان يزامن بالطريقة القديمة) — جلبةٌ كاملة واحدة
        // تُرسي النقطة، وما بعدها تفاضليّ.
        val sinceDeleted = deleteMark()
        if (sinceDeleted <= 0L) return@runCatching false
        // 🧭 أرضية الدلتا الحتمية (لا احتمال حكم الأعداد وحده): جهاز غاب أطول
        // من عمر سجلّ الحذف قد فاتته شواهد ممسوحة — الخادم يعلن أقدم لحظة
        // مضمونة في `deltaFloorMs`، وما دونها جلبٌ كامل واجب.
        val floor = serverDeltaFloorMs
        if (floor > 0L && sinceDeleted <= floor) return@runCatching false

        coroutineScope {
            val deletedJob = async { deletedSince(sinceDeleted) }
            val categoriesJob = async {
                if (server.categoriesUpdatedMs > stored.categoriesUpdatedMs) {
                    changedDocs("categories", stored.categoriesUpdatedMs)
                } else {
                    emptyList()
                }
            }
            val subcategoriesJob = async {
                if (server.subcategoriesUpdatedMs > stored.subcategoriesUpdatedMs) {
                    changedDocs("subcategories", stored.subcategoriesUpdatedMs)
                } else {
                    emptyList()
                }
            }
            val lessonsJob = async {
                if (server.maxUpdatedMs > stored.maxUpdatedMs) {
                    changedDocs("lessons", stored.maxUpdatedMs)
                } else {
                    emptyList()
                }
            }
            val deleted = deletedJob.await() ?: return@coroutineScope false
            val changedCategories = categoriesJob.await() ?: return@coroutineScope false
            val changedSubcategories = subcategoriesJob.await() ?: return@coroutineScope false
            val changedLessons = lessonsJob.await() ?: return@coroutineScope false

            // تغييرٌ ضخم: التفاضليّ حينها أغلى من صفحات الجلب الكامل.
            val touched = changedCategories.size + changedSubcategories.size +
                changedLessons.size + deleted.values.sumOf { it.size }
            if (touched > MAX_DELTA_DOCS) return@coroutineScope false
            // «تغيّرت البصمة ولم نجد وثيقة» — كان هذا يُشعل جلباً كاملاً مع أن
            // معناه الوحيد المشروع (متى تطابقت الأعداد) انحرافُ طابعٍ لا
            // محتوى: تُحفظ علامات الخادم ويُكتفى. اختلاف الأعداد يبقي الجلب
            // الكامل حَكَماً كما كان.
            if (touched == 0) {
                if (baseCategories.size != server.categories ||
                    baseSubcategories.size != server.subcategories ||
                    baseLessons.size != server.lessons
                ) {
                    return@coroutineScope false
                }
                store.setLastSyncMs(now)
                saveMarks(server)
                _state.value = _state.value.copy(syncing = false, offline = false, error = null)
                return@coroutineScope true
            }

            val categories = mergeById(
                baseCategories,
                changedCategories.map { Category.fromMap(it.id, it.data.orEmpty()) },
                deleted["categories"].orEmpty(),
                Category::id,
            )
            val subcategories = mergeById(
                baseSubcategories,
                changedSubcategories.map { Subcategory.fromMap(it.id, it.data.orEmpty()) },
                deleted["subcategories"].orEmpty(),
                Subcategory::id,
            )
            val lessons = mergeById(
                baseLessons,
                changedLessons.map { Lesson.fromMap(it.id, it.data.orEmpty()) },
                deleted["lessons"].orEmpty(),
                Lesson::id,
            )
            // 🛡️ حكم الصحّة: العدد بعد الدمج = عدد الخادم، وإلّا فالجلب الكامل.
            if (categories.size != server.categories ||
                subcategories.size != server.subcategories ||
                lessons.size != server.lessons
            ) {
                return@coroutineScope false
            }

            store.setCategories(categories)
            store.setSubcategories(subcategories)
            store.setLessons(lessons)
            store.setLastSyncMs(now)
            saveMarks(server)
            setDeleteMark(now - DELETE_MARK_BACKOFF_MS)
            store.pruneDownloads(lessons.map(Lesson::id).toSet())
            _state.value = ContentState(
                categories = categories,
                subcategories = subcategories,
                lessons = mergeDurations(lessons),
                loading = false,
                syncing = false,
            )
            true
        }
    }.getOrDefault(false)

    /**
     * وثائق مجموعةٍ تغيّرت بعد [sinceMs].
     *
     * ⚠️ ستّة استعلامات لا واحد، ولكلٍّ سببه:
     * - **حقلان**: الوثائق القديمة المغلَّفة `{data:{…}}` تكتب الطابع في
     *   `data.updatedAt` لا في الجذر (نفس علّة [newestUpdatedBy]).
     * - **ثلاثة أنواع**: Firestore يرتّب القيم بأنواعها أولاً، فحدٌّ من نوع
     *   Timestamp لا يرى وثيقةً طابعها رقمٌ خام ولا نصٌّ والعكس — والقاعدة
     *   فيها الأشكال الثلاثة: `updateCompat` في اللوحة كان يكتب `updatedAt`
     *   **نصَّ ISO**، فتعديل عنوانٍ من اللوحة ما كان الدلتا يلتقطه أبداً
     *   (والعدد لم يتغيّر فلا ينقذه حكم الأعداد) ولا يصل للمستخدمين إلا
     *   بجلبة كاملة عرضيّة. والوثائق النصيّة القديمة باقية في القاعدة ولو
     *   حُوِّلت اللوحة إلى Timestamp، فالحدّ النصي لازم دائماً.
     *
     * النتيجة تُدمج بمعرّف الوثيقة فلا تكرار. و`null` تعني فشلاً أو تجاوز
     * الحدّ — أي «ارجع إلى الجلب الكامل».
     */
    private suspend fun changedDocs(
        collection: String,
        sinceMs: Long,
    ): List<com.google.firebase.firestore.DocumentSnapshot>? = runCatching {
        coroutineScope {
            val bounds = listOf<Any>(
                com.google.firebase.Timestamp(java.util.Date(sinceMs)),
                sinceMs,
                // حدّ النصّ: ISO بتوقيت UTC وميلي ثانية ثابتة العرض يقارَن
                // معجمياً فيوافق الترتيب الزمني (انظر توثيق الدالة أعلاه).
                isoUpdatedBound(sinceMs),
            )
            val jobs = listOf("updatedAt", "data.updatedAt").flatMap { field ->
                bounds.map { bound -> async { changedBy(collection, field, bound) } }
            }
            val merged = LinkedHashMap<String, com.google.firebase.firestore.DocumentSnapshot>()
            jobs.forEach { job -> job.await().forEach { merged[it.id] = it } }
            if (merged.size > MAX_DELTA_DOCS) null else merged.values.toList()
        }
    }.getOrNull()

    private suspend fun changedBy(
        collection: String,
        field: String,
        bound: Any,
    ): List<com.google.firebase.firestore.DocumentSnapshot> =
        db.collection(collection)
            .whereGreaterThan(field, bound)
            .orderBy(field, Query.Direction.ASCENDING)
            .limit(MAX_DELTA_DOCS + 1L)
            .get()
            .await()
            .documents

    /**
     * ما حُذف من الخادم بعد [sinceMs]، مصنَّفاً بالمجموعة.
     *
     * الاستعلام التفاضليّ لا يكشف المحذوف أبداً (الوثيقة لم تعد موجودة
     * لتُقرأ)، فالخادم يسجّل كل اختفاء في `deleted_ids` — وهذه قراءتها.
     */
    private suspend fun deletedSince(sinceMs: Long): Map<String, Set<String>>? = runCatching {
        val documents = db.collection("deleted_ids")
            .whereGreaterThan("deletedAtMs", sinceMs)
            .orderBy("deletedAtMs", Query.Direction.ASCENDING)
            .limit(MAX_DELTA_DOCS + 1L)
            .get()
            .await()
            .documents
        if (documents.size > MAX_DELTA_DOCS) return@runCatching null
        val grouped = mutableMapOf<String, MutableSet<String>>()
        documents.forEach { document ->
            val collection = document.get("collection").text()
            val docId = document.get("docId").text()
            if (collection.isNotBlank() && docId.isNotBlank()) {
                grouped.getOrPut(collection) { mutableSetOf() } += docId
            }
        }
        grouped
    }.getOrNull()

    private fun deleteMark(): Long = prefs.getLong(KEY_DELETE_MARK, 0L)

    private fun setDeleteMark(value: Long) =
        prefs.edit().putLong(KEY_DELETE_MARK, value).apply()

    private fun storedMarks(): ProbeMarks? {
        if (!prefs.contains(KEY_MARK_LESSONS)) return null
        return ProbeMarks(
            categories = prefs.getInt(KEY_MARK_CATEGORIES, -1),
            subcategories = prefs.getInt(KEY_MARK_SUBCATEGORIES, -1),
            lessons = prefs.getInt(KEY_MARK_LESSONS, -1),
            maxUpdatedMs = prefs.getLong(KEY_MARK_UPDATED, 0L),
            categoriesUpdatedMs = prefs.getLong(KEY_MARK_CATEGORIES_UPDATED, 0L),
            subcategoriesUpdatedMs = prefs.getLong(KEY_MARK_SUBCATEGORIES_UPDATED, 0L),
        )
    }

    private fun saveMarks(marks: ProbeMarks) = prefs.edit()
        .putInt(KEY_MARK_CATEGORIES, marks.categories)
        .putInt(KEY_MARK_SUBCATEGORIES, marks.subcategories)
        .putInt(KEY_MARK_LESSONS, marks.lessons)
        .putLong(KEY_MARK_UPDATED, marks.maxUpdatedMs)
        .putLong(KEY_MARK_CATEGORIES_UPDATED, marks.categoriesUpdatedMs)
        .putLong(KEY_MARK_SUBCATEGORIES_UPDATED, marks.subcategoriesUpdatedMs)
        .apply()

    private fun lastProbeMs(): Long = prefs.getLong(KEY_LAST_PROBE, 0L)

    private fun setLastProbeMs(value: Long) =
        prefs.edit().putLong(KEY_LAST_PROBE, value).apply()

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
        // الرئيسية تُفتح يومياً، فختم السجل هنا يجعل رؤوس «اليوم/أمس» دقيقة.
        touchHistoryStamps()
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

    /// هل لدى المستخدم أي سجلّ يُبنى عليه التخصيص؟ عند غيابه تكون «مقترح لك»
    /// نسخة طبق الأصل من «الأحدث»، فتُستبدل في الرئيسية بـ«ابدأ من هنا».
    fun hasHistory(): Boolean =
        store.subcategoryVisits().isNotEmpty() ||
            store.categoryVisits().isNotEmpty() ||
            store.playCounts().isNotEmpty()

    // ---- سجلّ الاستماع: عناصر مخفيّة بالسحب ----

    /// السجلّ المعروض: ترتيب الاستماع نفسه، منقوصاً منه ما أخفاه المستخدم.
    /// أي إعادة تشغيل للدرس تُعيده إلى السجلّ تلقائياً (عدّاد التشغيل تجاوز
    /// قيمته لحظة الإخفاء)، فلا يختفي درس يستمع إليه المستخدم من جديد.
    fun historyLessons(): List<Lesson> {
        touchHistoryStamps()
        val hidden = hiddenHistory()
        val byId = _state.value.lessonById
        val counts = store.playCounts()
        val revived = mutableListOf<String>()
        val items = store.recentPlayedIds().mapNotNull { id ->
            if (hidden.has(id)) {
                if ((counts[id] ?: 0L) > hidden.optLong(id)) {
                    revived += id
                } else {
                    return@mapNotNull null
                }
            }
            byId[id]
        }
        if (revived.isNotEmpty()) {
            revived.forEach { hidden.remove(it) }
            prefs.edit().putString(KEY_HIDDEN_HISTORY, hidden.toString()).apply()
        }
        return items
    }

    /// إخفاء عنصر من السجلّ (سحب للحذف) — محليّ بحت وقابل للنقض بإعادة التشغيل.
    fun hideFromHistory(lessonId: String) {
        if (lessonId.isBlank()) return
        val hidden = hiddenHistory().put(lessonId, store.playCounts()[lessonId] ?: 0L)
        prefs.edit().putString(KEY_HIDDEN_HISTORY, hidden.toString()).apply()
    }

    private fun hiddenHistory(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_HIDDEN_HISTORY, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    /// طوابع زمنية تقريبيّة لآخر استماع: السجل نفسه لا يحمل تواريخ، فكل معرّف
    /// يظهر فيه لأوّل مرّة — أو ازداد عدّاد تشغيله — يُختم بوقت رؤيته. التطبيق
    /// يُفتح يومياً فالدقّة كافية تماماً لرؤوس «اليوم/أمس/هذا الأسبوع».
    private fun touchHistoryStamps() {
        val recent = store.recentPlayedIds()
        if (recent.isEmpty()) return
        val counts = store.playCounts()
        val root = historyStampsJson()
        val now = System.currentTimeMillis()
        var changed = false
        recent.forEach { id ->
            val entry = root.optJSONObject(id)
            val plays = counts[id] ?: 0L
            if (entry == null || entry.optLong("plays", -1L) != plays) {
                root.put(id, JSONObject().put("at", now).put("plays", plays))
                changed = true
            }
        }
        // ما خرج من السجل لا يُبقى له طابع.
        val valid = recent.toSet()
        val stale = buildList {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in valid) add(key)
            }
        }
        if (stale.isNotEmpty()) {
            stale.forEach { root.remove(it) }
            changed = true
        }
        if (changed) prefs.edit().putString(KEY_HISTORY_STAMPS, root.toString()).apply()
    }

    /// طابع آخر استماع لكل درس في السجل (بالمللي ثانية) — لرؤوس السجل الزمنيّة.
    fun historyStamps(): Map<String, Long> {
        val root = historyStampsJson()
        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val at = root.optJSONObject(key)?.optLong("at") ?: 0L
                if (at > 0L) put(key, at)
            }
        }
    }

    private fun historyStampsJson(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_HISTORY_STAMPS, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    // ---- ترتيب يدوي لقوائم التشغيل ----

    /// يعيد دروس القائمة بترتيب المستخدم إن وُجد؛ وما استُجدّ من دروس يبقى في
    /// آخر القائمة بترتيبه الأصلي.
    fun orderedPlaylist(playlistId: String, lessons: List<Lesson>): List<Lesson> {
        val order = playlistOrder(playlistId) ?: return lessons
        val rank = order.withIndex().associate { (index, id) -> id to index }
        val known = lessons.filter { rank.containsKey(it.id) }.sortedBy { rank.getValue(it.id) }
        val fresh = lessons.filterNot { rank.containsKey(it.id) }
        return known + fresh
    }

    /// ينقل عنصراً في القائمة المعروضة ويحفظ الترتيب الجديد.
    fun movePlaylistItem(playlistId: String, orderedIds: List<String>, from: Int, to: Int) {
        if (from == to || from !in orderedIds.indices || to !in orderedIds.indices) return
        val updated = orderedIds.toMutableList()
        updated.add(to, updated.removeAt(from))
        val root = playlistOrders().put(playlistId, JSONArray(updated))
        prefs.edit().putString(KEY_PLAYLIST_ORDER, root.toString()).apply()
    }

    private fun playlistOrder(playlistId: String): List<String>? {
        val array = playlistOrders().optJSONArray(playlistId) ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotEmpty)?.let(::add)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun playlistOrders(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_PLAYLIST_ORDER, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    /// 🧹 يمحو الآثار الشخصيّة المخزّنة في تفضيلات هذا المستودع (السجلّ
    /// المخفي وطوابع السجل وترتيب القوائم). «حذف بياناتي» كان يمحو مخزن
    /// التطبيق العام ويُبقي هذه — فدرسٌ أخفاه المستخدم من سجلّه قبل المحو
    /// يظلّ محجوباً بعده مهما أعاد تشغيله، خلافاً لوعد «محو كل شيء».
    /// (علامات المسبار وسجلّ الحذف تبقى: بيانات مزامنة لا أثر استخدام.)
    fun clearPersonalData() {
        prefs.edit()
            .remove(KEY_HIDDEN_HISTORY)
            .remove(KEY_HISTORY_STAMPS)
            .remove(KEY_PLAYLIST_ORDER)
            .apply()
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
        // المفتاح يُحسب مرّة لكل درس لا مرّتين لكل مقارنة: الترتيب المباشر
        // بمحدِّد يستدعيه في كل موازنة، وهذه الدالّة تُحسب على خيط الواجهة.
        return pool.map { it to score(it) }
            .sortedWith(
                compareByDescending<Pair<Lesson, Double>> { it.second }
                    .thenByDescending { it.first.createdAtMs },
            )
            .map { it.first }
            .take(limit)
    }

    fun trending(limit: Int = 15): List<Lesson> {
        val now = System.currentTimeMillis()
        fun score(lesson: Lesson): Double {
            val ageDays = max(0L, (now - lesson.createdAtMs) / 86_400_000L).coerceAtMost(3_650)
            return lesson.views * (0.5 + 1.0 / (1 + ageDays / 30.0))
        }
        // كسابقتها: المفتاح مرّة لكل درس لا مع كل موازنة.
        return withAudio().filter { it.views > 0L }
            .map { it to score(it) }
            .sortedByDescending { it.second }
            .map { it.first }
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

    fun shortStation(): List<Lesson> {
        // خريطة المدد تُقرأ مرّة لا مرّة لكل درس (نفس علّة `mergeDurations`)،
        // والدالّة تُحسب على خيط الواجهة في الرئيسية مع كل تبدّل للمراجعة.
        val local = store.durations()
        return withAudio().filter {
            val duration = if (it.durationMs > 0L) it.durationMs else (local[it.id] ?: 0L)
            duration in 1L until 10 * 60 * 1_000L
        }.sortedByDescending(Lesson::createdAtMs)
    }

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
        _state.value.lessons.filter { it.audioUrl.isNotBlank() }

    /// ⚠️ قراءة **واحدة** لخريطة المدد لا قراءة لكل درس: `store.duration` تنسخ
    /// ملفّ التفضيلات كاملاً وتحلّل JSON من جديد في كل استدعاء، وهذه الدالّة
    /// تُنادى على القائمة كاملة — منها مرّةً في مُنشئ المستودع أي على خيط
    /// الإقلاع.
    private fun mergeDurations(items: List<Lesson>): List<Lesson> {
        val local = store.durations()
        if (local.isEmpty()) return items
        return items.map { lesson ->
            val duration = local[lesson.id] ?: 0L
            if (lesson.durationMs <= 0L && duration > 0L) {
                lesson.copy(durationMs = duration)
            } else {
                lesson
            }
        }
    }

    companion object {
        /// واجهة الكتالوج العامة (كاش CDN، ‏gzip): الجلب الكامل بطلب واحد.
        private const val CATALOG_URL = "https://minbar-adkassahk.vercel.app/api/catalog"
        private const val SYNC_INTERVAL_MS = 2 * 60 * 1_000L
        /// حدّ أدنى بين مسبارين — يبتلع عودات ON_RESUME المتلاحقة.
        private const val PROBE_INTERVAL_MS = 60 * 1_000L
        private const val PREFS = "content_repo_v1"
        /// حجم صفحة الدروس. 300 وثيقة تصل في زمن معقول حتى على شبكة ضعيفة،
        /// فيرى المستخدم محتوى يتراكم بدل شاشة فارغة حتى اكتمال كل الدروس.
        private const val LESSONS_PAGE_SIZE = 300L

        private const val KEY_MARK_CATEGORIES = "mark_categories"
        private const val KEY_MARK_SUBCATEGORIES = "mark_subcategories"
        private const val KEY_MARK_LESSONS = "mark_lessons"
        private const val KEY_MARK_UPDATED = "mark_updated_ms"
        private const val KEY_MARK_CATEGORIES_UPDATED = "mark_categories_updated_ms"
        private const val KEY_MARK_SUBCATEGORIES_UPDATED = "mark_subcategories_updated_ms"
        private const val KEY_LAST_PROBE = "last_probe_ms"
        /// آخر لحظة قُرئ فيها سجلّ الحذف `deleted_ids`.
        private const val KEY_DELETE_MARK = "delete_mark_ms"
        /// تراجعٌ عن اللحظة عند حفظ علامة الحذف — احتياطاً لفارق الساعتين.
        private const val DELETE_MARK_BACKOFF_MS = 2 * 60 * 1_000L
        /// سقف التغيير التفاضليّ. فوقه يصير الجلب الكامل أرخص وأبسط: صفحة
        /// واحدة من 300 وثيقة أقلّ كلفةً من عشرات الاستعلامات والدمج.
        private const val MAX_DELTA_DOCS = 200
        private const val KEY_HIDDEN_HISTORY = "hidden_history_v1"
        private const val KEY_HISTORY_STAMPS = "history_stamps_v1"
        private const val KEY_PLAYLIST_ORDER = "playlist_order_v1"
        @Volatile private var instance: ContentRepository? = null
        fun get(context: Context): ContentRepository = instance ?: synchronized(this) {
            instance ?: ContentRepository(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * دمج نتيجة المزامنة التفاضليّة في الكاش المحفوظ.
 *
 * دالّة خالصة بلا شبكة ولا تفضيلات كي تُختبر وحدها: تُبقي ترتيب الكاش كما
 * هو (فلا تقفز البطاقات في الواجهة)، وتستبدل المعدَّل مكانه، وتُلحق الجديد
 * في الآخر، وتُسقط ما ورد في [deleted] — والحذف مقدَّمٌ على التعديل لأن
 * وثيقةً حُذفت بعد تعديلها يجب ألّا تعود.
 */
/**
 * حدّ نصّي بصيغة ISO-8601 (UTC، ميلي ثانية بثلاث خانات دائماً) للاستعلام
 * التفاضلي على وثائق كُتب طابعها نصّاً (`updateCompat` في اللوحة يكتب بصيغة
 * `toISOString`). العرض الثابت شرط صحّة المقارنة المعجميّة زمنياً.
 * دالّة خالصة بلا شبكة كي تُختبر وحدها.
 */
internal fun isoUpdatedBound(sinceMs: Long): String =
    java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(sinceMs))

internal fun <T> mergeById(
    base: List<T>,
    changed: List<T>,
    deleted: Set<String>,
    id: (T) -> String,
): List<T> {
    val updates = changed.associateBy(id)
    val result = ArrayList<T>(base.size + changed.size)
    val seen = HashSet<String>(base.size + changed.size)
    base.forEach { item ->
        val key = id(item)
        if (key in deleted || !seen.add(key)) return@forEach
        result += updates[key] ?: item
    }
    changed.forEach { item ->
        val key = id(item)
        if (key in deleted || !seen.add(key)) return@forEach
        result += item
    }
    return result
}
