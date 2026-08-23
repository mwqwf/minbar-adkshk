package com.ali.menbaradkshk.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Native store with a deliberately non-destructive migration from Flutter.
 *
 * Flutter's legacy plugin writes to FlutterSharedPreferences.xml and prefixes
 * every key with "flutter.". We copy those values once into a new file, keep
 * the old file untouched for rollback, and continue to understand Flutter's
 * encoded doubles and string lists.
 */
class LocalStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(NATIVE_FILE, Context.MODE_PRIVATE)

    /// ⚠️ كاش المحتوى في **ملفّ مستقلّ**: كان يسكن ملفّ التفضيلات نفسه، فكانت
    /// كل كتابة شخصيّة صغيرة (الموضع والمدّة تُكتبان كل بضع ثوانٍ أثناء
    /// التشغيل) تُعيد تسلسل مئات الكيلوبايتات من الدروس معها إلى القرص. لا
    /// تُعِد مفاتيح `cache_*` إلى `preferences` مهما بدا توحيد الملفّين أبسط.
    private val cachePrefs = appContext.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
    private val legacy = appContext.getSharedPreferences(FLUTTER_FILE, Context.MODE_PRIVATE)

    /// لقطة واحدة من ملفّ فلاتر القديم: `getAll()` تنسخ الخريطة كاملةً عند كل
    /// نداء، وكانت تُنفَّذ في كل قراءة. تُبطَل عند أي كتابة على الملفّ القديم.
    @Volatile
    private var legacySnapshot: Map<String, Any?>? = null
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /// نبض مستقلّ لما يتغيّر أثناء التشغيل وحده (الموضع والثواني المستمعة):
    /// من يحتاجه يراقبه، ومن لا يحتاجه لا يُعاد حسابه كل خمس ثوانٍ.
    private val _positionRevision = MutableStateFlow(0L)
    val positionRevision: StateFlow<Long> = _positionRevision.asStateFlow()

    init {
        migrateFromFlutter()
        migrateCacheToOwnFile()
        // ⛔ `repairDownloadIndex` لا تُنادى هنا: كانت تفحص كل ملفّ منزَّل على
        // القرص في **كل** إقلاع وعلى الخيط الرئيسي بلا فائدة (كل قارئ للمسار
        // يتحقّق من الملفّ بنفسه). صارت تُنفَّذ ضمن `pruneDownloads` بعد
        // المزامنة الناجحة — على خيط خلفيّ.
    }

    private fun migrateFromFlutter() {
        if (preferences.getBoolean(MIGRATED_KEY, false)) return
        val editor = preferences.edit()
        var migratedCount = 0
        legacy.all.forEach { (rawKey, rawValue) ->
            if (!rawKey.startsWith(FLUTTER_PREFIX)) return@forEach
            val key = rawKey.removePrefix(FLUTTER_PREFIX)
            if (preferences.contains(key)) return@forEach
            when (rawValue) {
                is Boolean -> editor.putBoolean(key, rawValue)
                is Int -> editor.putLong(key, rawValue.toLong())
                is Long -> editor.putLong(key, rawValue)
                is Float -> editor.putFloat(key, rawValue)
                is String -> editor.putString(key, decodeFlutterStringList(rawValue) ?: rawValue)
                is Set<*> -> editor.putString(
                    key,
                    JSONArray(rawValue.filterIsInstance<String>()).toString(),
                )
                else -> return@forEach
            }
            migratedCount += 1
        }
        editor
            .putBoolean(MIGRATED_KEY, true)
            .putInt(MIGRATED_COUNT_KEY, migratedCount)
            .putLong(MIGRATED_AT_KEY, System.currentTimeMillis())
            .commit()
    }

    /// ينقل كاش المحتوى مرّةً واحدةً من ملفّ التفضيلات إلى ملفّه الخاصّ، ثم
    /// يمسحه من القديم. النقل لا الإفراغ: من فتح التطبيق بلا شبكة بعد التحديث
    /// يجب أن يجد مكتبته كما تركها.
    private fun migrateCacheToOwnFile() {
        if (cachePrefs.getBoolean(CACHE_MIGRATED_KEY, false)) return
        val editor = cachePrefs.edit()
        val cleanup = preferences.edit()
        listOf(KEY_CACHE_CATEGORIES, KEY_CACHE_SUBCATEGORIES, KEY_CACHE_LESSONS).forEach { key ->
            val value = (raw(key) as? String)?.let { decodeFlutterStringList(it) ?: it }
            if (!value.isNullOrEmpty()) editor.putString(key, value)
            cleanup.remove(key)
        }
        editor.putBoolean(CACHE_MIGRATED_KEY, true).apply()
        cleanup.apply()
    }

    private fun decodeFlutterStringList(value: String): String? {
        if (!value.startsWith(JSON_LIST_PREFIX)) return null
        val json = value.removePrefix(JSON_LIST_PREFIX)
        return runCatching { JSONArray(json).toString() }.getOrNull()
    }

    /// ⚠️ لا تُعِد `preferences.all[key]` هنا: `getAll()` تبني نسخةً كاملةً من
    /// خريطة الملفّ عند **كل** قراءة، وصفوف القوائم تقرأ الموضع والمدّة لكل
    /// درس ظاهر أثناء التشغيل. القراءة النوعيّة لا تنسخ شيئاً.
    private fun raw(key: String): Any? {
        if (preferences.contains(key)) return prefValue(key)
        return legacyValue(key)
    }

    /// نوع المفتاح الواحد قد يختلف بحسب ما هاجر من فلاتر، فتُجرَّب الأنواع
    /// بالترتيب — والنصّ أوّلاً لأنّ أغلب قيمنا JSON مُسلسل.
    private fun prefValue(key: String): Any? =
        runCatching { preferences.getString(key, null) }.getOrNull()
            ?: runCatching { preferences.getLong(key, 0L) }.getOrNull()
            ?: runCatching { preferences.getBoolean(key, false) }.getOrNull()
            ?: runCatching { preferences.getFloat(key, 0f) }.getOrNull()
            ?: runCatching { preferences.getInt(key, 0) }.getOrNull()

    private fun legacyValue(key: String): Any? {
        val snapshot = legacySnapshot ?: legacy.all.also { legacySnapshot = it }
        return snapshot["$FLUTTER_PREFIX$key"]
    }

    private fun string(key: String, default: String = ""): String =
        (raw(key) as? String)?.let { decodeFlutterStringList(it) ?: it } ?: default

    private fun long(key: String, default: Long = 0L): Long = when (val value = raw(key)) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }

    private fun bool(key: String, default: Boolean = false): Boolean = when (val value = raw(key)) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: default
        else -> default
    }

    private fun double(key: String, default: Double = 0.0): Double = when (val value = raw(key)) {
        is Float -> value.toDouble()
        is Double -> value
        is Number -> value.toDouble()
        is String -> value
            .removePrefix(DOUBLE_PREFIX)
            .toDoubleOrNull()
            ?: default
        else -> default
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
        _revision.value += 1L
    }

    /// ⚠️ كتابة لا تُحرّك [revision]: الموضع والثواني المستمعة تُكتبان كل خمس
    /// ثوانٍ أثناء التشغيل، وكل رفعة لـ`revision` تُبطل كل `remember(revision,…)`
    /// في الواجهة فيُعاد حساب الفهرس كلّه مرّتين في كل نبضة. تبقى [write] لكلّ
    /// ما يراه المستخدم فوراً (مفضّلة/تنزيل/قائمة/إعداد/إتمام درس).
    private fun writeQuiet(block: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
        _positionRevision.value += 1L
    }

    private fun jsonArray(key: String): JSONArray =
        runCatching { JSONArray(string(key, "[]")) }.getOrElse { JSONArray() }

    private fun jsonObject(key: String): JSONObject =
        runCatching { JSONObject(string(key, "{}")) }.getOrElse { JSONObject() }

    private fun putJson(key: String, value: Any) = write { putString(key, value.toString()) }

    /// كاش المحتوى يُقرأ ويُكتب من ملفّه وحده — انظر تعليق [cachePrefs].
    private fun cacheArray(key: String): JSONArray =
        runCatching { JSONArray(cachePrefs.getString(key, "[]") ?: "[]") }
            .getOrElse { JSONArray() }

    private fun putCache(key: String, value: JSONArray) {
        cachePrefs.edit().putString(key, value.toString()).apply()
        _revision.value += 1L
    }

    fun categories(): List<Category> = cacheArray(KEY_CACHE_CATEGORIES).objects(Category::fromJson)
    fun subcategories(): List<Subcategory> =
        cacheArray(KEY_CACHE_SUBCATEGORIES).objects(Subcategory::fromJson)
    fun lessons(): List<Lesson> = cacheArray(KEY_CACHE_LESSONS).objects(Lesson::fromJson)

    fun setCategories(items: List<Category>) =
        putCache(KEY_CACHE_CATEGORIES, JSONArray(items.map(Category::toJson)))
    fun setSubcategories(items: List<Subcategory>) =
        putCache(KEY_CACHE_SUBCATEGORIES, JSONArray(items.map(Subcategory::toJson)))
    fun setLessons(items: List<Lesson>) =
        putCache(KEY_CACHE_LESSONS, JSONArray(items.map(Lesson::toJson)))

    fun lastSyncMs(): Long = long(KEY_LAST_SYNC)
    fun setLastSyncMs(value: Long) = write { putLong(KEY_LAST_SYNC, value) }

    fun downloads(): Map<String, String> = jsonObject(KEY_DOWNLOADS).stringMap()
    fun localAudioPath(lessonId: String): String? =
        downloads()[lessonId]?.takeIf { File(it).isFile }

    fun setDownload(lessonId: String, path: String) {
        val json = jsonObject(KEY_DOWNLOADS).put(lessonId, path)
        putJson(KEY_DOWNLOADS, json)
        trackEvent("download")
    }

    /// يحذف تنزيلات دروس لم تعد موجودة في الفهرس (ملفات يتيمة تستهلك المساحة).
    /// يُستدعى بعد مزامنة ناجحة فقط كي لا تُحذف عند فشل الجلب.
    fun pruneDownloads(validIds: Set<String>): Int {
        // موضع تنظيف الفهرس: هنا (خيط خلفيّ بعد مزامنة) لا في الإقلاع.
        repairDownloadIndex()
        val orphans = downloads().filterKeys { it !in validIds }
        if (orphans.isEmpty()) return 0
        orphans.values.forEach { runCatching { File(it).delete() } }
        // ⚠️ كتابة **واحدة** لا واحدة لكل يتيم: كل كتابة تُعيد تسلسل الملفّ
        // كلّه وترفع `revision` فتُبطل كل `remember(revision, …)` في الواجهة.
        val json = jsonObject(KEY_DOWNLOADS)
        orphans.keys.forEach { json.remove(it) }
        putJson(KEY_DOWNLOADS, json)
        return orphans.size
    }

    fun removeDownload(lessonId: String) {
        val json = jsonObject(KEY_DOWNLOADS)
        json.remove(lessonId)
        putJson(KEY_DOWNLOADS, json)
    }

    private fun repairDownloadIndex() {
        val current = downloads()
        if (current.isEmpty()) return
        val valid = current.filterValues { File(it).isFile }
        if (valid.size != current.size) {
            putJson(KEY_DOWNLOADS, JSONObject(valid))
        }
    }

    fun intMap(key: String): Map<String, Long> = jsonObject(key).longMap()
    private fun setIntMap(key: String, value: Map<String, Long>) =
        putJson(key, JSONObject(value))
    /// نظيرة [setIntMap] بلا رفع [revision] — للخرائط التي تُكتب دورياً أثناء
    /// التشغيل وحده (المواضع والثواني اليوميّة). انظر [writeQuiet].
    private fun setIntMapQuiet(key: String, value: Map<String, Long>) =
        writeQuiet { putString(key, JSONObject(value).toString()) }
    fun stringList(key: String): List<String> = jsonArray(key).strings()
    private fun setStringList(key: String, value: List<String>) =
        putJson(key, JSONArray(value))

    fun playCounts(): Map<String, Long> = intMap(KEY_PLAY_COUNTS)
    fun incrementPlayCount(id: String) {
        val values = playCounts().toMutableMap()
        values[id] = (values[id] ?: 0L) + 1L
        setIntMap(KEY_PLAY_COUNTS, values)
    }

    fun recentPlayedIds(): List<String> = stringList(KEY_RECENT)
    fun addRecentPlayed(id: String) {
        val values = recentPlayedIds().toMutableList().apply {
            remove(id)
            add(0, id)
            while (size > 60) removeAt(lastIndex)
        }
        setStringList(KEY_RECENT, values)
    }

    fun categoryVisits(): Map<String, Long> = intMap(KEY_CATEGORY_VISITS)
    fun incrementCategoryVisit(id: String) = incrementMapValue(KEY_CATEGORY_VISITS, id)
    fun subcategoryVisits(): Map<String, Long> = intMap(KEY_SUBCATEGORY_VISITS)
    fun incrementSubcategoryVisit(id: String) = incrementMapValue(KEY_SUBCATEGORY_VISITS, id)

    private fun incrementMapValue(key: String, id: String) {
        if (id.isBlank()) return
        val values = intMap(key).toMutableMap()
        values[id] = (values[id] ?: 0L) + 1L
        setIntMap(key, values)
    }

    fun positions(): Map<String, Long> = intMap(KEY_POSITIONS)
    fun position(lessonId: String): Long = positions()[lessonId] ?: 0L
    /// ⚠️ كتابة صامتة: هذه تُنفَّذ كل خمس ثوانٍ طوال التشغيل، ورفع [revision]
    /// معها كان يُعيد حساب الفهرس كلّه في كل نبضة. انظر [writeQuiet].
    fun setPosition(lessonId: String, milliseconds: Long) {
        val values = positions().toMutableMap()
        if (milliseconds <= 0L) values.remove(lessonId) else values[lessonId] = milliseconds
        setIntMapQuiet(KEY_POSITIONS, values)
    }

    fun completedIds(): List<String> = stringList(KEY_COMPLETED)
    fun markCompleted(lessonId: String) {
        val values = completedIds().toMutableList()
        if (lessonId !in values) {
            values += lessonId
            setStringList(KEY_COMPLETED, values)
        }
        // ⚠️ تصفير الموضع هنا بكتابة **مرئيّة** لا بـ[setPosition] الصامتة:
        // إتمام الدرس يراه المستخدم فوراً في شريط تقدّم الصفّ.
        val remaining = positions().toMutableMap()
        if (remaining.remove(lessonId) != null) setIntMap(KEY_POSITIONS, remaining)
        recordDailyListen()
    }

    fun durations(): Map<String, Long> = intMap(KEY_DURATIONS)
    fun duration(lessonId: String): Long = durations()[lessonId] ?: 0L
    fun setDuration(lessonId: String, milliseconds: Long) {
        if (lessonId.isBlank() || milliseconds <= 0L) return
        val values = durations().toMutableMap()
        values[lessonId] = milliseconds
        setIntMap(KEY_DURATIONS, values)
    }

    /// قراءة فقط: العلامة تُكتب بـ[markViewCounted] بعد نجاح الكتابة للخادم فقط،
    /// كي لا تضيع المشاهدة نهائياً عند فشل النداء (الخادم يمنع التكرار بنفسه).
    fun isViewCountedToday(lessonId: String): Boolean {
        if (lessonId.isBlank()) return false
        return jsonObject(KEY_VIEW_COUNTED).optString(lessonId) == todayKey()
    }

    fun markViewCounted(lessonId: String) {
        if (lessonId.isBlank()) return
        putJson(KEY_VIEW_COUNTED, jsonObject(KEY_VIEW_COUNTED).put(lessonId, todayKey()))
    }

    fun favoriteIds(): List<String> = stringList(KEY_FAVORITES)
    fun isFavorite(lessonId: String): Boolean = lessonId in favoriteIds()
    fun toggleFavorite(lessonId: String) {
        val values = favoriteIds().toMutableList()
        if (!values.remove(lessonId)) values.add(0, lessonId)
        setStringList(KEY_FAVORITES, values)
    }

    fun searchHistory(): List<String> = stringList(KEY_SEARCH_HISTORY)
    fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val values = searchHistory().toMutableList().apply {
            remove(trimmed)
            add(0, trimmed)
            while (size > 20) removeAt(lastIndex)
        }
        setStringList(KEY_SEARCH_HISTORY, values)
    }
    fun clearSearchHistory() = write { remove(KEY_SEARCH_HISTORY) }

    fun playlists(): List<Playlist> = jsonArray(KEY_PLAYLISTS).objects(Playlist::fromJson)
    private fun setPlaylists(items: List<Playlist>) =
        putJson(KEY_PLAYLISTS, JSONArray(items.map(Playlist::toJson)))

    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(
            id = "pl_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            name = name.trim().ifEmpty { "قائمة" },
            lessonIds = emptyList(),
            createdAtMs = System.currentTimeMillis(),
        )
        setPlaylists(listOf(playlist) + playlists())
        return playlist
    }

    fun deletePlaylist(id: String) = setPlaylists(playlists().filterNot { it.id == id })
    fun renamePlaylist(id: String, name: String) = setPlaylists(
        playlists().map {
            if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it
        },
    )

    fun addToPlaylist(playlistId: String, lessonId: String) = setPlaylists(
        playlists().map {
            if (it.id == playlistId && lessonId !in it.lessonIds) {
                it.copy(lessonIds = it.lessonIds + lessonId)
            } else it
        },
    )

    fun removeFromPlaylist(playlistId: String, lessonId: String) = setPlaylists(
        playlists().map {
            if (it.id == playlistId) it.copy(lessonIds = it.lessonIds - lessonId) else it
        },
    )

    fun playbackSpeed(): Double = double(KEY_SPEED, 1.0).coerceIn(0.75, 2.0)
    fun setPlaybackSpeed(value: Double) =
        write { putFloat(KEY_SPEED, value.coerceIn(0.75, 2.0).toFloat()) }
    fun skipSeconds(): Int = long(KEY_SKIP, 15L).toInt()
    fun setSkipSeconds(value: Int) = write { putLong(KEY_SKIP, value.toLong()) }

    /// موعد انتهاء مؤقّت النوم (0 = لا مؤقّت). يُحفظ هنا لا في نطاق الواجهة
    /// وحده: سحب التطبيق من التطبيقات الحديثة كان يقتل المؤقّت بينما يستمرّ
    /// التشغيل عبر الخدمة، فيبقى الصوت شغّالاً طوال الليل. الخدمة تقرأ هذه
    /// القيمة وتوقف التشغيل في موعده، والواجهة تستعيد المؤقّت عند إعادة الفتح.
    fun sleepEndsAtMs(): Long = long(KEY_SLEEP_ENDS_AT)
    fun setSleepEndsAtMs(value: Long) = write { putLong(KEY_SLEEP_ENDS_AT, value) }
    fun clearSleepTimer() = write { putLong(KEY_SLEEP_ENDS_AT, 0L) }

    // المظهر الافتراضي: اتّباع النظام (طلب المستخدم 2026-07-23).
    fun themeMode(): String = string(KEY_THEME, "system")
    fun setThemeMode(value: String) = write { putString(KEY_THEME, value) }
    // المدى مطابق لمنزلق الإعدادات وللأصل (0.8–1.4).
    fun fontScale(): Float = double(KEY_FONT_SCALE, 1.0).toFloat().coerceIn(0.8f, 1.4f)
    fun setFontScale(value: Float) =
        write { putFloat(KEY_FONT_SCALE, value.coerceIn(0.8f, 1.4f)) }

    /**
     * 🔎 حجم خطّ الأذكار — مستقلّ عن `fontScale` العام عمداً.
     *
     * منزلق الإعدادات العام محصور في 0.8–1.4 لأنّ تكبيره أكثر يكسر تخطيط
     * القوائم والأشرطة. لكنّ الأذكار **نصّ يُقرأ ويُردَّد**، وكثير من قرّائه
     * كبار سنّ لا يبصرون 19sp. فله مقياسه الخاصّ يصل إلى **64sp** — ثلاثة
     * أضعاف الافتراضي — بلا أن يمسّ بقيّة التطبيق بشيء.
     */
    fun adhkarFontSp(): Float =
        double(KEY_ADHKAR_FONT, ADHKAR_FONT_DEFAULT.toDouble()).toFloat()
            .coerceIn(ADHKAR_FONT_MIN, ADHKAR_FONT_MAX)

    fun setAdhkarFontSp(value: Float) = write {
        putFloat(KEY_ADHKAR_FONT, value.coerceIn(ADHKAR_FONT_MIN, ADHKAR_FONT_MAX))
    }

    /**
     * 🔍 حجم خطّ **شاشة الذكر المكبَّرة** — مقياس مستقلّ تماماً عن خطّ القائمة.
     *
     * ⚠️ كان الاثنان مقياساً واحداً، وكانت الشاشة المكبَّرة ترفع القيمة إلى
     * أرضيّة ٦٠ بينما سقف القائمة ٦٤ — فلم يبقَ إلا أربع نقاط للحركة،
     * والتصغير يُبتلع بالأرضيّة فلا يتحرّك شيء أصلاً. النتيجة: أزرار تعمل
     * ولا يتغيّر الخطّ = «تجمُّد» في نظر المستخدم.
     *
     * والفصل صحيح مفهوميّاً لا التفافاً على العطل: خطّ القائمة يجب أن يبقى
     * في حدود تُبقي البطاقات مقروءة، أمّا الشاشة المكبَّرة فغرضها الوحيد
     * **أكبر خطّ ممكن** لكبير السنّ — فمداها واسع (٤٠–١٦٠).
     */
    /**
     * ⭐ **خطّ الأذكار عريضاً** — مفعَّل افتراضياً.
     *
     * **لماذا افتراضاً؟** لأنّ التكبير وحده لا يكفي لضعيف البصر: الحجم يمدّ
     * الحرف والوزن يُغمِّقه، وثخانة الحرف هي ما يفصله عن الخلفيّة فعلاً.
     * وعائلة Amiri فيها `amiri_bold` حقيقيّ، فالوزن مرسومٌ لا مُصطنَع
     * بتغليظ حسابيّ يشوّه الحرف العربي.
     *
     * ويبقى المخرج قائماً لمن يفضّل الرفيع — تبديلٌ واحد بجوار الحجم.
     */
    fun adhkarBold(): Boolean = bool(KEY_ADHKAR_BOLD, true)
    fun setAdhkarBold(value: Boolean) = write { putBoolean(KEY_ADHKAR_BOLD, value) }

    fun adhkarZoomFontSp(): Float =
        double(KEY_ADHKAR_ZOOM_FONT, ZOOM_FONT_DEFAULT.toDouble()).toFloat()
            .coerceIn(ZOOM_FONT_MIN, ZOOM_FONT_MAX)

    fun setAdhkarZoomFontSp(value: Float) = write {
        putFloat(KEY_ADHKAR_ZOOM_FONT, value.coerceIn(ZOOM_FONT_MIN, ZOOM_FONT_MAX))
    }

    // ---- 🕌 تفضيلات المصحف ----

    /// الرواية المختارة — حفص افتراضاً (الرواية الرئيسيّة).
    fun quranRiwaya(): String = string(KEY_QURAN_RIWAYA, QuranRepository.DEFAULT_RIWAYA)
    fun setQuranRiwaya(value: String) = write { putString(KEY_QURAN_RIWAYA, value) }

    /// القارئ المختار لكل رواية على حدة (لكل رواية قرّاؤها).
    fun quranReciter(riwaya: String): String = string("$KEY_QURAN_RECITER$riwaya", "")
    fun setQuranReciter(riwaya: String, value: String) =
        write { putString("$KEY_QURAN_RECITER$riwaya", value) }

    /// حجم خطّ المصحف — مدى أوسع من خطّ الواجهة لأنّ الرسم العثماني يحتاج
    /// حجماً أكبر ليُقرأ بوضوح، ولأنّ كثيراً من قرّائه كبار سنّ.
    fun quranFontSp(): Float =
        double(KEY_QURAN_FONT, QURAN_FONT_DEFAULT.toDouble()).toFloat()
            .coerceIn(QURAN_FONT_MIN, QURAN_FONT_MAX)

    fun setQuranFontSp(value: Float) = write {
        putFloat(KEY_QURAN_FONT, value.coerceIn(QURAN_FONT_MIN, QURAN_FONT_MAX))
    }

    /**
     * ⭐ خطّ المصحف عريضاً — **مفعَّل افتراضياً**.
     *
     * **لماذا افتراضاً؟** لأنّ التكبير وحده لا يحلّ ضعف البصر: الحرف الكبير
     * الرفيع يبقى باهتاً، والوزن هو ما يفصله عن الخلفيّة. وجمهور المصحف فيه
     * كبار سنّ، فالافتراض يجب أن يكون في صفّهم — ومن أراد الرفيع أوقفه بضغطة.
     *
     * وعائلة Amiri فيها وزنٌ عريض حقيقيّ (`amiri_bold`)، فالعرض رسمٌ أصليّ لا
     * تغليظٌ مصطنع يُشوّه الرسم العثمانيّ بعلاماته.
     */
    fun quranBold(): Boolean = bool(KEY_QURAN_BOLD, true)
    fun setQuranBold(value: Boolean) = write { putBoolean(KEY_QURAN_BOLD, value) }

    /**
     * نمط عرض المصحف: `true` مصوَّر (صفحات المصحف الورقي)، `false` مكتوب.
     *
     * **الافتراض مصوَّر** (قرار صريح): من يفتح مصحفاً يتوقّع صفحة المصحف التي
     * حفظ مواضعها عينُه — الإطار والخطّ وموضع الآية في الصفحة. والمكتوب خيارٌ
     * ثانٍ لمن أراد تكبير الخطّ أو النسخ أو القراءة بلا إنترنت قبل التنزيل.
     *
     * والاختيار يُحفظ فلا يُسأل عنه في كل فتح.
     */
    fun quranImageMode(): Boolean = bool(KEY_QURAN_IMAGE_MODE, true)
    fun setQuranImageMode(value: Boolean) = write { putBoolean(KEY_QURAN_IMAGE_MODE, value) }

    /**
     * آخر موضع قراءة (فهرس مسطّح) — يفتح المصحف حيث تركه صاحبه، و`-1` إن لم
     * يقرأ بعدُ.
     *
     * ⚠️ الافتراض **`-1` لا `0`**: الصفر موضعٌ صحيح (الفاتحة، الآية ١)، فلمّا
     * كان هو أيضاً «لا موضع» كانت بطاقة «تابع القراءة» تختفي كلّما فتح المستخدم
     * الفاتحة — وهي أكثر السور فتحاً — فيُمحى موضعه في البقرة بلا أن يفعل شيئاً.
     * تمييز «لا شيء» عن «أوّل المصحف» شرطُ صحّة لا تجميل.
     */
    fun quranLastAyah(): Int = long(KEY_QURAN_LAST, -1L).toInt()
    fun setQuranLastAyah(value: Int) = write { putLong(KEY_QURAN_LAST, value.toLong()) }

    /**
     * ⭐ علامات الآيات — فهارس مسطّحة يحفظها القارئ ليرجع إليها.
     *
     * **لماذا منفصلة عن «موضع القراءة»؟** لأنّهما حاجتان مختلفتان: الموضع
     * واحدٌ يتحرّك مع القارئ تلقائياً، والعلامة قرارٌ صريح يبقى (آية يحفظها،
     * أو موضع في ورد آخر). خلطهما كان يجعل كل تمريرة تمحو ما علّمه بيده.
     *
     * والأحدث أوّلاً كالمفضّلة في بقيّة التطبيق — نمطٌ واحد يتعلّمه المستخدم
     * مرّة.
     */
    fun quranBookmarks(): List<Int> =
        stringList(KEY_QURAN_BOOKMARKS).mapNotNull(String::toIntOrNull)

    fun isQuranBookmarked(flatAyah: Int): Boolean = flatAyah in quranBookmarks()

    /// يُرجع `true` إن أُضيفت العلامة، و`false` إن أُزيلت — كي تعرف الواجهة
    /// ماذا تقول للمستخدم بلا قراءة ثانية للقرص.
    fun toggleQuranBookmark(flatAyah: Int): Boolean {
        val values = quranBookmarks().toMutableList()
        val added = !values.remove(flatAyah)
        if (added) values.add(0, flatAyah)
        setStringList(KEY_QURAN_BOOKMARKS, values.map(Int::toString))
        return added
    }

    // ---- 🕌 وِرد المصحف اليوميّ ----

    /**
     * ⭐ المقدار اليوميّ **بالصفحات**، و`0` يعني لا وِرد.
     *
     * الصفحة وحدة الوِرد عند الناس («وردي صفحتان»)، وهي أيضاً الوحدة الوحيدة
     * التي يعرفها الفهرس فعلاً (بداية كل صفحة في `index.jz`) — فبها يُقاس
     * التقدّم من موضع القراءة المحفوظ نفسه بلا عدّادٍ ثانٍ.
     */
    fun quranWardPages(): Int = long(KEY_QURAN_WARD_PAGES).toInt()

    fun setQuranWardPages(value: Int) =
        write { putLong(KEY_QURAN_WARD_PAGES, value.coerceAtLeast(0).toLong()) }

    fun quranWardEnabled(): Boolean = quranWardPages() > 0

    /// وقت التذكير — الساعة `-1` تعني بلا تذكير، كنظيرها في وِرد الدروس
    /// ([wardHour]) كي لا يتعلّم المستخدم قاعدتين لشيء واحد.
    fun quranWardHour(): Int = long(KEY_QURAN_WARD_HOUR, -1L).toInt()
    fun quranWardMinute(): Int = long(KEY_QURAN_WARD_MINUTE, 0L).toInt()

    fun setQuranWardTime(hour: Int, minute: Int) = write {
        putLong(KEY_QURAN_WARD_HOUR, hour.toLong())
        putLong(KEY_QURAN_WARD_MINUTE, minute.toLong())
    }

    /// إيقاف الوِرد يُسقط المقدار والتذكير معاً: مقدارٌ بلا تذكير يبقى يعرض
    /// سطراً في الفهرس لمن ظنّ أنّه أوقف الميزة كلّها. وبيانات اليوم تُمحى
    /// معهما كي تبدأ إعادةُ التفعيل يوماً نظيفاً لا ببقايا عدٍّ قديم.
    fun disableQuranWard() = write {
        putLong(KEY_QURAN_WARD_PAGES, 0L)
        putLong(KEY_QURAN_WARD_HOUR, -1L)
        remove(KEY_QURAN_WARD_DAY)
        remove(KEY_QURAN_WARD_LAST_PAGE)
        remove(KEY_QURAN_WARD_COUNT)
    }

    /**
     * 🕌 عدّ صفحات الوِرد — يُستدعى من مسار حفظ موضع القراءة نفسه (تمريرٌ
     * استقرّ أو صفحةٌ ثبتت)، لا من التركيب ولا بمؤقّت.
     *
     * يعدّ **الصفحات المقروءة فعلاً**: لا يزيد العدّ إلا حين تتقدّم الصفحة
     * خطوةً واحدة متّصلة عن آخر صفحة مزورة — ففتحُ سورة بعيدة قفزُ تصفّحٍ لا
     * قراءة فلا يُحتسب، والعدّ لا ينقص أبداً فالرجوعُ إلى الوراء لا يمحو ما
     * قُرئ ولا يجمّد التقدّم. (كان التقدّم فرقَ موضعين، فمن فتح سورةً
     * تصفّحاً «تمّ وِرده» بلا قراءة، ومن رجع يقرأ من أوّل المصحف صار الفرق
     * سالباً فلا يتحرّك.)
     */
    fun recordQuranWardPage(page: Int) {
        if (page < 0 || quranWardPages() <= 0) return
        val today = adhkarDayKey()
        val sameDay = string(KEY_QURAN_WARD_DAY, "") == today
        val last = if (sameDay) long(KEY_QURAN_WARD_LAST_PAGE, -1L).toInt() else -1
        // الصفحة نفسها — لا كتابة أصلاً، فلا يُرفَع `revision` عبثاً.
        if (sameDay && page == last) return
        val counted = if (sameDay) long(KEY_QURAN_WARD_COUNT).toInt() else 0
        val advanced = last >= 0 && page == last + 1
        write {
            putString(KEY_QURAN_WARD_DAY, today)
            putLong(KEY_QURAN_WARD_LAST_PAGE, page.toLong())
            putLong(KEY_QURAN_WARD_COUNT, (counted + if (advanced) 1 else 0).toLong())
        }
    }

    /**
     * ما بقي من وِرد اليوم بالصفحات: `0` تمّ، و`-1` لا وِرد أصلاً.
     *
     * ⚠️ **قراءة خالصة بلا أي كتابة**: تُستدعى من `remember` أثناء التركيب،
     * وكتابةٌ هنا كانت ترفع [revision] فتُبطل كل `remember(revision, …)` في
     * التطبيق كلّه مع كل إعادة تركيب. التصفير اليومي والعدّ كلاهما في
     * [recordQuranWardPage] وحدها.
     */
    fun quranWardRemaining(): Int {
        val target = quranWardPages()
        if (target <= 0) return -1
        // يوم جديد لم تُقرأ فيه صفحة بعدُ — الوِرد كامل، والمفاتيح تُدوَّر
        // عند أوّل قراءة لا هنا.
        if (string(KEY_QURAN_WARD_DAY, "") != adhkarDayKey()) return target
        return (target - long(KEY_QURAN_WARD_COUNT).toInt()).coerceAtLeast(0)
    }

    fun autoDownloadEnabled(): Boolean = bool(KEY_AUTO_DOWNLOAD)
    fun setAutoDownloadEnabled(value: Boolean) = write { putBoolean(KEY_AUTO_DOWNLOAD, value) }
    fun autoDownloadTarget(): String? = string(KEY_AUTO_TARGET).takeIf { it.isNotBlank() }
    fun setAutoDownloadTarget(value: String?) = write {
        if (value == null) remove(KEY_AUTO_TARGET) else putString(KEY_AUTO_TARGET, value)
    }
    fun autoDownloadWifiOnly(): Boolean = bool(KEY_WIFI_ONLY, true)
    fun setAutoDownloadWifiOnly(value: Boolean) = write { putBoolean(KEY_WIFI_ONLY, value) }

    fun notificationsEnabled(): Boolean = bool(KEY_NOTIFICATIONS, true)
    fun setNotificationsEnabled(value: Boolean) = write { putBoolean(KEY_NOTIFICATIONS, value) }
    fun continueReminderEnabled(): Boolean = bool(KEY_CONTINUE_REMINDER, true)
    fun setContinueReminderEnabled(value: Boolean) =
        write { putBoolean(KEY_CONTINUE_REMINDER, value) }

    fun followedSubcategories(): List<String> = stringList(KEY_FOLLOWED_SUBS)
    fun isFollowingSubcategory(id: String): Boolean = id in followedSubcategories()
    fun toggleFollowSubcategory(id: String) {
        val values = followedSubcategories().toMutableList()
        if (!values.remove(id)) values += id
        setStringList(KEY_FOLLOWED_SUBS, values)
    }

    fun notificationLastSeenMs(): Long = long(KEY_NOTIFICATION_SEEN)
    fun setNotificationLastSeenMs(value: Long) = write { putLong(KEY_NOTIFICATION_SEEN, value) }
    fun submissionsLastSeenMs(): Long = long(KEY_SUBMISSIONS_SEEN)
    fun setSubmissionsLastSeenMs(value: Long) = write { putLong(KEY_SUBMISSIONS_SEEN, value) }
    fun dismissedNotificationIds(): List<String> = stringList(KEY_DISMISSED_NOTIFICATIONS)
    fun dismissNotification(id: String) {
        val values = dismissedNotificationIds().toMutableList()
        if (id !in values) values += id
        while (values.size > 500) values.removeAt(0)
        setStringList(KEY_DISMISSED_NOTIFICATIONS, values)
    }

    /// إخفاء دفعة إشعارات بكتابة واحدة (زر «مسح الكل»).
    fun dismissNotifications(ids: List<String>) {
        if (ids.isEmpty()) return
        val values = (dismissedNotificationIds() + ids).distinct().toMutableList()
        while (values.size > 500) values.removeAt(0)
        setStringList(KEY_DISMISSED_NOTIFICATIONS, values)
    }

    /// التراجع عن «مسح الكل»: يُعيد إظهار كل ما استُبعد يدوياً. الإشعارات
    /// نفسها لا تُحذف من الخادم، فالاستبعاد محليّ بحت وقابل للنقض.
    fun clearDismissedNotifications() =
        setStringList(KEY_DISMISSED_NOTIFICATIONS, emptyList())

    // ---- الأذكار (محلّي بالكامل: لا شبكة ولا حساب) ----

    /// عدّاد الذكر الحالي داخل قسم: مفتاحه «القسم:الفهرس» وقيمته ما تبقّى.
    /// يُصفَّر تلقائياً بتغيّر اليوم كي لا يبدأ المستخدم من تقدّم الأمس.
    private fun adhkarDayKey(): String {
        val cal = java.util.Calendar.getInstance()
        // ⚠️ `Locale.ROOT` إلزاميّ: بلا لغة صريحة تتبع `format` لغةَ الجهاز،
        // فتُكتب الأرقام **هنديّة** (٢٠٢٦-٠٨-٢٣) على إعدادٍ عربيّ ذي ترقيم
        // `arab`. والمفتاح مُخزَّن، فتغيير المستخدم للغة أو للترقيم يجعل مفتاح
        // اليوم لا يطابق المحفوظ ⇒ **تنكسر سلسلة المداومة ويُصفَّر عدّاد الوِرد
        // ظلماً**. هذه مفاتيح لا نصوص عرض.
        return "%04d-%02d-%02d".format(
            java.util.Locale.ROOT,
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /// يُنادى قبل أي قراءة/كتابة لعدّادات اليوم: إن تغيّر اليوم مُسحت.
    private fun ensureAdhkarDay() {
        val today = adhkarDayKey()
        if (string("adhkar_day", "") == today) return
        write {
            putString("adhkar_day", today)
            putString("adhkar_counts", "{}")
        }
    }

    /// كم مرّة تبقّت من الذكر رقم [index] في [section] (null = لم يُبدأ بعد).
    fun adhkarDone(section: String, index: Int): Long? {
        ensureAdhkarDay()
        return intMap("adhkar_counts")["$section:$index"]
    }

    fun setAdhkarDone(section: String, index: Int, done: Long) {
        ensureAdhkarDay()
        val values = intMap("adhkar_counts").toMutableMap()
        values["$section:$index"] = done
        setIntMap("adhkar_counts", values)
    }

    /// عدد الأذكار المكتملة في قسم اليوم — لشريط التقدّم في قائمة الأقسام.
    fun adhkarCompleted(section: String, totals: List<Int>): Int {
        ensureAdhkarDay()
        val values = intMap("adhkar_counts")
        return totals.indices.count { i ->
            (values["$section:$i"] ?: 0L) >= totals[i].toLong()
        }
    }

    fun resetAdhkarSection(section: String, count: Int) {
        ensureAdhkarDay()
        val values = intMap("adhkar_counts").toMutableMap()
        repeat(count) { values.remove("$section:$it") }
        setIntMap("adhkar_counts", values)
    }

    /// مفتاح الأمس — مشترك بين تحديث السلسلة وقراءتها الحيّة كي لا يفترق
    /// حسابُ «هل السلسلة متّصلة؟» في الموضعين.
    private fun adhkarYesterdayKey(): String =
        java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }.let {
            // نفس علّة [adhkarDayKey]: مفتاح مخزَّن لا نصّ عرض ⇒ `Locale.ROOT`.
            "%04d-%02d-%02d".format(
                java.util.Locale.ROOT,
                it.get(java.util.Calendar.YEAR),
                it.get(java.util.Calendar.MONTH) + 1,
                it.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }

    /// سلسلة أيام المداومة على الأذكار (تُحدَّث عند إتمام أي قسم).
    fun adhkarStreak(): Int = long("adhkar_streak").toInt()

    /// ⚠️ للعرض تُستعمل هذه لا [adhkarStreak]: القيمة المخزَّنة لا تُصفَّر إلّا
    /// عند أوّل إتمام بعد الانقطاع، فمن ترك الأذكار أسبوعاً كان يرى «مداومتك…»
    /// كأنّها متّصلة. السلسلة حيّة فقط إن كان آخر إتمام اليوم أو الأمس.
    fun adhkarStreakLive(): Int {
        val last = string("adhkar_streak_day", "")
        val alive = last == adhkarDayKey() || last == adhkarYesterdayKey()
        return if (alive) adhkarStreak() else 0
    }

    fun noteAdhkarCompletion() {
        val today = adhkarDayKey()
        val last = string("adhkar_streak_day", "")
        if (last == today) return
        val yesterday = adhkarYesterdayKey()
        val next = if (last == yesterday) adhkarStreak() + 1 else 1
        write {
            putString("adhkar_streak_day", today)
            putLong("adhkar_streak", next.toLong())
        }
    }

    // تذكيرات الأذكار: مفتاح لكل نوع + ساعته ودقيقته.
    /// **مفعَّلة افتراضياً**: التذكير هو جوهر فائدة الصفحة، ومن ينتظر أن
    /// يكتشف المستخدم مفتاحاً ليُفعّله يخسر أغلب المستخدمين. والإيقاف متاح
    /// بنقرة، وتغيير الوقت كذلك — والقيمة المخزَّنة تَغلِب الافتراض دائماً.
    fun adhkarReminder(kind: String): Boolean = bool("adhkar_rem_$kind", default = true)
    fun setAdhkarReminder(kind: String, value: Boolean) =
        write { putBoolean("adhkar_rem_$kind", value) }

    fun adhkarReminderHour(kind: String, default: Int): Int =
        long("adhkar_rem_h_$kind", default.toLong()).toInt()

    fun adhkarReminderMinute(kind: String, default: Int): Int =
        long("adhkar_rem_m_$kind", default.toLong()).toInt()

    fun setAdhkarReminderTime(kind: String, hour: Int, minute: Int) = write {
        putLong("adhkar_rem_h_$kind", hour.toLong())
        putLong("adhkar_rem_m_$kind", minute.toLong())
    }

    // ---- أوّل تشغيل (سؤالان ثم لا شيء بعدهما أبداً) ----
    /// هل انتهى المستخدم من شاشتَي أوّل تشغيل؟ تُكتب حتى لو تخطّاهما: الغرض
    /// ألّا يُسأل مرّتين، لا أن يُلاحَق حتى يجيب.
    fun onboardingDone(): Boolean = bool("onboarding_done")
    fun markOnboardingDone() = write { putBoolean("onboarding_done", true) }

    // ---- تلميحات الإرشاد للمستخدم الجديد (تُعرض مرة واحدة) ----
    fun hintSeen(key: String): Boolean = bool("hint_$key")
    fun markHintSeen(key: String) = write { putBoolean("hint_$key", true) }

    /// لحظة أول فتح للتطبيق — تُسجَّل عند أول قراءة وتبقى ثابتة. تلميحات
    /// «بعد مدة قصيرة من أول دخول» (كتلميح زر «حول») تُقاس عليها.
    fun firstOpenMs(): Long {
        val existing = preferences.getLong("first_open_ms", 0L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        write { putLong("first_open_ms", now) }
        return now
    }

    // ---- طابور التحميل الخلفي (يُعالَج عبر WorkManager ويصمد لإغلاق التطبيق) ----
    /// قفل يحمي دورة قراءة-تعديل-كتابة الطابور من التزامن بين الخيوط.
    private val queueLock = Any()

    fun downloadQueue(): List<String> = stringList("download_queue")

    /// معرّفات عناصر الطابور المقيَّدة بـ«واي فاي فقط».
    ///
    /// القيد صار **لكل عنصر** لا حالة عامّة واحدة: العلم المشترك القديم كان
    /// يُكتب بلا شرط عند كل إضافة، فضغطة تحميل يدويّة واحدة (بالافتراضي
    /// `wifiOnly=false`) تمحو قيد الدفعة التلقائية فتنزل عشرات الدروس على
    /// بيانات الجوّال خلافاً لإعداد المستخدم الصريح.
    ///
    /// ترحيل آمن للقيمة القديمة: إن لم تُكتب هذه القائمة بعد وكان العلم العام
    /// مرفوعاً، فكل ما في الطابور مقيَّد — وهو سلوك النسخة السابقة حرفيّاً.
    fun downloadQueueWifiOnlyIds(): Set<String> {
        if (raw(KEY_QUEUE_WIFI_IDS) == null) {
            return if (downloadQueueWifiOnly()) downloadQueue().toSet() else emptySet()
        }
        return stringList(KEY_QUEUE_WIFI_IDS).toSet()
    }

    /// [wifiOnly] يُحفظ لكل معرّف مُضاف في هذه الدفعة وحدها، فلا تُخفَّض قيود
    /// دفعات سابقة أبداً (دفعات التحميل التلقائي).
    fun addToDownloadQueue(ids: List<String>, label: String, wifiOnly: Boolean = false) {
        synchronized(queueLock) {
            val current = downloadQueue().toMutableList()
            val added = ids.filter { it !in current }
            if (added.isEmpty()) return
            // يُقرأ قبل كتابة الطابور الجديد كي يبقى ترحيل العلم القديم صحيحاً.
            val restricted = downloadQueueWifiOnlyIds().toMutableSet()
            if (wifiOnly) restricted += added
            current += added
            write {
                putString("download_queue", JSONArray(current).toString())
                putString("download_queue_label", label)
                putLong("download_queue_total", (downloadQueueTotal() + added.size).toLong())
                putString(KEY_QUEUE_WIFI_IDS, JSONArray(restricted.toList()).toString())
                // العلم العام يبقى للتوافق فقط ولا يُخفَّض: «هل في الطابور مقيَّد؟».
                putBoolean("download_queue_wifi_only", restricted.isNotEmpty())
            }
        }
    }

    fun removeFromDownloadQueue(id: String) {
        synchronized(queueLock) {
            val current = downloadQueue().toMutableList()
            if (!current.remove(id)) return
            val restricted = downloadQueueWifiOnlyIds() - id
            write {
                putString("download_queue", JSONArray(current).toString())
                putString(KEY_QUEUE_WIFI_IDS, JSONArray(restricted.toList()).toString())
                putBoolean("download_queue_wifi_only", restricted.isNotEmpty())
                if (current.isEmpty()) putLong("download_queue_total", 0L)
            }
        }
    }

    fun clearDownloadQueue() = synchronized(queueLock) { clearQueueLocked() }

    /// يمسح الطابور فقط إن كان فارغاً فعلاً، كي لا تُبتلع دفعة أُضيفت أثناء
    /// انتهاء المعالج.
    fun clearDownloadQueueIfEmpty() = synchronized(queueLock) {
        if (downloadQueue().isEmpty()) clearQueueLocked()
    }

    private fun clearQueueLocked() = write {
        // الإيقاف حالة طابورٍ لا حالة تطبيق: طابور فارغ يعني بداية نظيفة،
        // وإلّا بقي علم الإيقاف مرفوعاً فلا يبدأ التحميل التالي أبداً.
        remove("download_queue_paused")
        remove("download_queue")
        remove("download_queue_wifi_only")
        remove(KEY_QUEUE_WIFI_IDS)
        putLong("download_queue_total", 0L)
    }

    /// ⏸ إيقاف مؤقّت لطابور التحميل — **لا إلغاء**: الملفات الجزئية وترتيب
    /// الطابور يبقيان، والاستئناف يُكمل من البايت الذي وقف عنده بـRange.
    /// محفوظ على القرص كي يصمد لإغلاق التطبيق: بلا ذلك كان WorkManager
    /// يوقظ العامل بعد دقائق فيستأنف تحميلاً أوقفه المستخدم عمداً — وغالباً
    /// أوقفه لأنّه على بيانات الجوّال.
    fun downloadQueuePaused(): Boolean = bool("download_queue_paused")

    fun setDownloadQueuePaused(paused: Boolean) = write {
        putBoolean("download_queue_paused", paused)
    }

    fun downloadQueueLabel(): String = string("download_queue_label")
    fun downloadQueueTotal(): Int = long("download_queue_total").toInt()
    fun downloadQueueWifiOnly(): Boolean = bool("download_queue_wifi_only")

    fun wardHour(): Int = long(KEY_WARD_HOUR, -1L).toInt()
    fun wardMinute(): Int = long(KEY_WARD_MINUTE, 0L).toInt()
    fun wardEnabled(): Boolean = wardHour() >= 0
    fun setWardTime(hour: Int, minute: Int) = write {
        putLong(KEY_WARD_HOUR, hour.toLong())
        putLong(KEY_WARD_MINUTE, minute.toLong())
    }
    fun disableWard() = write { putLong(KEY_WARD_HOUR, -1L) }

    fun weeklyGoalMinutes(): Int = long(KEY_WEEKLY_GOAL).toInt().coerceAtLeast(0)
    fun setWeeklyGoalMinutes(value: Int) =
        write { putLong(KEY_WEEKLY_GOAL, value.coerceAtLeast(0).toLong()) }
    fun dailySeconds(): Map<String, Long> = intMap(KEY_DAILY_SECONDS)
    /// ⚠️ كتابة صامتة كنظيرتها في [setPosition]: تُنادى كل خمس ثوانٍ أثناء
    /// التشغيل. انظر [writeQuiet].
    fun addListenSeconds(seconds: Long) {
        if (seconds <= 0L) return
        val values = dailySeconds().toMutableMap()
        val key = todayKey()
        values[key] = (values[key] ?: 0L) + seconds
        values.keys
            .sortedByDescending { parseLegacyDate(it) ?: LocalDate.MIN }
            .drop(120)
            .forEach { values.remove(it) }
        setIntMapQuiet(KEY_DAILY_SECONDS, values)
    }
    fun todaySeconds(): Long = dailySeconds()[todayKey()] ?: 0L
    fun totalSeconds(): Long = dailySeconds().values.sum()
    fun weekSeconds(): Long {
        val values = dailySeconds()
        return (0L..6L).sumOf { offset ->
            values[legacyDateKey(LocalDate.now().minusDays(offset))] ?: 0L
        }
    }

    fun streakDays(): Int = long(KEY_STREAK).toInt()
    fun lastListenDate(): String? = string(KEY_LAST_LISTEN_DATE).takeIf { it.isNotBlank() }
    fun recordDailyListen() {
        val today = todayKey()
        val last = lastListenDate()
        if (last == today) return
        val streak = runCatching {
            val previous = requireNotNull(last?.let(::parseLegacyDate))
            val days = java.time.temporal.ChronoUnit.DAYS.between(previous, LocalDate.now())
            if (days == 1L) streakDays() + 1 else 1
        }.getOrDefault(1)
        write {
            putLong(KEY_STREAK, streak.toLong())
            putString(KEY_LAST_LISTEN_DATE, today)
        }
    }

    fun bookmarks(lessonId: String): List<Bookmark> {
        val array = jsonObject(KEY_BOOKMARKS).optJSONArray(lessonId) ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Bookmark(
                        lessonId = lessonId,
                        positionMs = item.optLong("ms"),
                        note = item.optString("note"),
                        savedAtMs = item.optLong("savedAt"),
                    ),
                )
            }
        }.sortedBy(Bookmark::positionMs)
    }

    fun allBookmarks(): List<Bookmark> {
        val root = jsonObject(KEY_BOOKMARKS)
        return buildList {
            root.keys().forEach { lessonId -> addAll(bookmarks(lessonId)) }
        }.sortedByDescending(Bookmark::savedAtMs)
    }

    fun addBookmark(lessonId: String, positionMs: Long, note: String) {
        val root = jsonObject(KEY_BOOKMARKS)
        val array = root.optJSONArray(lessonId) ?: JSONArray()
        array.put(
            JSONObject()
                .put("ms", positionMs)
                .put("note", note.trim())
                .put("savedAt", System.currentTimeMillis()),
        )
        val sorted = (0 until array.length())
            .mapNotNull(array::optJSONObject)
            .sortedBy { it.optLong("ms") }
        root.put(lessonId, JSONArray(sorted))
        putJson(KEY_BOOKMARKS, root)
    }

    fun removeBookmark(lessonId: String, savedAtMs: Long) {
        val root = jsonObject(KEY_BOOKMARKS)
        val array = root.optJSONArray(lessonId) ?: return
        val remaining = (0 until array.length())
            .mapNotNull(array::optJSONObject)
            .filterNot { it.optLong("savedAt") == savedAtMs }
        if (remaining.isEmpty()) root.remove(lessonId) else root.put(lessonId, JSONArray(remaining))
        putJson(KEY_BOOKMARKS, root)
    }

    fun submitterName(): String = string(KEY_SUBMITTER_NAME)
    fun setSubmitterName(value: String) = write { putString(KEY_SUBMITTER_NAME, value.trim()) }
    fun knownSubmissionStatuses(): Map<String, String> {
        val raw = string(KEY_KNOWN_SUBMISSION_STATUSES, "{}")
        runCatching { JSONObject(raw).stringMap() }.getOrNull()?.let { return it }

        // Older Flutter versions stored "id=status" entries as a StringList.
        return runCatching {
            JSONArray(raw).strings().mapNotNull { item ->
                val separator = maxOf(item.lastIndexOf('='), item.lastIndexOf('|'))
                if (separator <= 0 || separator == item.lastIndex) null
                else item.substring(0, separator) to item.substring(separator + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }
    fun setKnownSubmissionStatuses(value: Map<String, String>) =
        putJson(KEY_KNOWN_SUBMISSION_STATUSES, JSONObject(value))

    fun trackEvent(name: String) {
        val values = intMap(KEY_ANALYTICS).toMutableMap()
        values[name] = (values[name] ?: 0L) + 1L
        setIntMap(KEY_ANALYTICS, values)
    }

    // ---- نسخة احتياطية محلية (مفضّلة/قوائم/سجل/تقدّم/لحظات) ----
    private val backupKeys = listOf(
        KEY_FAVORITES, KEY_PLAYLISTS, KEY_RECENT, KEY_COMPLETED, KEY_POSITIONS,
        KEY_DURATIONS, KEY_BOOKMARKS, KEY_FOLLOWED_SUBS, KEY_SEARCH_HISTORY,
        KEY_CATEGORY_VISITS, KEY_SUBCATEGORY_VISITS, KEY_PLAY_COUNTS,
        KEY_DAILY_SECONDS, KEY_STREAK, KEY_LAST_LISTEN_DATE, KEY_WEEKLY_GOAL,
    )

    /// يصدّر البيانات الشخصية كنص JSON (بلا تنزيلات ولا كاش المحتوى).
    fun exportBackup(): String {
        val root = JSONObject()
            .put("app", "menbaradkshk")
            .put("version", 1)
            .put("exportedAtMs", System.currentTimeMillis())
        val data = JSONObject()
        backupKeys.forEach { key ->
            when (val value = raw(key)) {
                null -> Unit
                is String -> data.put(key, decodeFlutterStringList(value) ?: value)
                is Long -> data.put(key, value)
                is Int -> data.put(key, value.toLong())
                is Boolean -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                else -> Unit
            }
        }
        return root.put("data", data).toString(2)
    }

    /// يستورد نسخة احتياطية سابقة. يعيد عدد الحقول المستعادة، أو -1 إن كان الملف غير صالح.
    fun importBackup(json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return -1
        if (root.optString("app") != "menbaradkshk") return -1
        val data = root.optJSONObject("data") ?: return -1
        var restored = 0
        write {
            backupKeys.forEach { key ->
                if (!data.has(key)) return@forEach
                when (val value = data.get(key)) {
                    is String -> putString(key, value)
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Double -> putFloat(key, value.toFloat())
                    else -> return@forEach
                }
                restored += 1
            }
        }
        return restored
    }

    fun clearPersonalData() {
        // ملفات الصوت تُحذف **قبل** محو الفهرس: محو `KEY_DOWNLOADS` وحده كان
        // يُفقد المسارات فتبقى الدروس المنزَّلة (مئات الميغابايتات) في
        // `filesDir/lessons` إلى الأبد بلا أي طريق لحذفها من التطبيق.
        runCatching { downloads().values.forEach { File(it).delete() } }
        val editor = preferences.edit()
        PERSONAL_KEYS.forEach { editor.remove(it) }
        editor.apply()
        val legacyEditor = legacy.edit()
        PERSONAL_KEYS.forEach { legacyEditor.remove("$FLUTTER_PREFIX$it") }
        legacyEditor.apply()
        // ⛔ اللقطة تُبطَل هنا وإلّا عادت القيم الممحوّة من الملفّ القديم للظهور.
        legacySnapshot = null
        _revision.value += 1L
    }

    fun migrationSummary(): MigrationSummary = MigrationSummary(
        completed = preferences.getBoolean(MIGRATED_KEY, false),
        migratedKeys = preferences.getInt(MIGRATED_COUNT_KEY, 0),
        migratedAtMs = preferences.getLong(MIGRATED_AT_KEY, 0L),
        legacyFilePresent = legacy.all.isNotEmpty(),
    )

    private fun todayKey(): String = legacyDateKey(LocalDate.now(ZoneId.systemDefault()))

    private fun legacyDateKey(date: LocalDate): String =
        "${date.year}-${date.monthValue}-${date.dayOfMonth}"

    private fun parseLegacyDate(value: String): LocalDate? {
        val parts = value.split('-')
        if (parts.size != 3) return null
        return runCatching {
            LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrNull()
    }

    data class MigrationSummary(
        val completed: Boolean,
        val migratedKeys: Int,
        val migratedAtMs: Long,
        val legacyFilePresent: Boolean,
    )

    companion object {
        private const val NATIVE_FILE = "minbar_native_preferences"
        /// ملفّ كاش المحتوى الثقيل — مفصول عن الحالة الساخنة عمداً.
        private const val CACHE_FILE = "minbar_content_cache"
        private const val CACHE_MIGRATED_KEY = "_cache_split_v1"
        private const val FLUTTER_FILE = "FlutterSharedPreferences"
        private const val FLUTTER_PREFIX = "flutter."
        private const val MIGRATED_KEY = "_native_migration_v1"
        private const val MIGRATED_COUNT_KEY = "_native_migration_count"
        private const val MIGRATED_AT_KEY = "_native_migration_at"
        private const val DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
        private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        private const val JSON_LIST_PREFIX = "$LIST_PREFIX!"

        const val KEY_CACHE_CATEGORIES = "cache_categories"
        const val KEY_CACHE_SUBCATEGORIES = "cache_subcategories"
        const val KEY_CACHE_LESSONS = "cache_lessons"
        const val KEY_LAST_SYNC = "cache_last_sync_ms"
        const val KEY_DOWNLOADS = "downloads_audio"
        const val KEY_PLAY_COUNTS = "pers_play_counts"
        const val KEY_RECENT = "pers_recent_played"
        const val KEY_CATEGORY_VISITS = "pers_cat_visits"
        const val KEY_SUBCATEGORY_VISITS = "pers_sub_visits"
        const val KEY_POSITIONS = "pers_positions"
        const val KEY_COMPLETED = "pers_completed"
        const val KEY_DURATIONS = "pers_durations"
        const val KEY_VIEW_COUNTED = "pers_view_counted"
        const val KEY_FAVORITES = "pers_favorites"
        const val KEY_SEARCH_HISTORY = "pers_search_hist"
        const val KEY_PLAYLISTS = "pers_playlists"
        const val KEY_STREAK = "pers_streak_days"
        const val KEY_LAST_LISTEN_DATE = "pers_last_listen_date"
        const val KEY_SPEED = "pref_speed"
        const val KEY_SKIP = "pref_skip_sec"
        const val KEY_SLEEP_ENDS_AT = "pref_sleep_ends_at"
        /// قيد «واي فاي فقط» لكل عنصر في طابور التحميل (يحلّ محلّ العلم العام).
        const val KEY_QUEUE_WIFI_IDS = "download_queue_wifi_ids"
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_ADHKAR_FONT = "adhkar_font_sp"

        /**
         * مدى خطّ الأذكار بالنقاط: من مقروء عادي إلى **أقصى ما يمكن**.
         *
         * ١٢٠ نقطة ≈ ستّة أضعاف الافتراضي — وهو الحدّ الذي يبقى بعده الحرف
         * العربيّ كاملاً على شاشة الهاتف بلا قصّ أفقيّ (الأعرض في أذكارنا
         * كلمات كـ«أستغفر» تشغل عندها عرض الشاشة كلّه). ما فوقه يقطع الكلمة
         * فيصير التكبير ضرراً لا عوناً — وهذا هو السقف التقنيّ الفعليّ لا
         * اختياراً محافظاً.
         *
         * والمقصد صريح: كبار السنّ وضعاف البصر يقرؤون الذكر بلا نظّارة.
         */
        const val ADHKAR_FONT_MIN = 14f
        const val ADHKAR_FONT_DEFAULT = 19f
        const val ADHKAR_FONT_MAX = 120f

        const val KEY_ADHKAR_ZOOM_FONT = "adhkar_zoom_font_sp"
        const val KEY_ADHKAR_BOLD = "adhkar_bold"

        /// مدى الشاشة المكبَّرة: من مقروء مريح إلى **ضخم** لضعاف البصر.
        /// السقف ١٦٠ مختار عملياً: أكبر منه لا تتّسع الكلمة الواحدة عرضاً.
        const val ZOOM_FONT_MIN = 40f
        const val ZOOM_FONT_DEFAULT = 90f
        const val ZOOM_FONT_MAX = 160f

        const val KEY_QURAN_RIWAYA = "quran_riwaya"
        const val KEY_QURAN_RECITER = "quran_reciter_"
        const val KEY_QURAN_FONT = "quran_font_sp"
        const val KEY_QURAN_LAST = "quran_last_ayah"
        const val KEY_QURAN_IMAGE_MODE = "quran_image_mode"
        const val KEY_QURAN_BOOKMARKS = "quran_bookmarks"
        const val KEY_QURAN_BOLD = "quran_bold"
        const val KEY_QURAN_WARD_PAGES = "quran_ward_pages"
        const val KEY_QURAN_WARD_HOUR = "quran_ward_hour"
        const val KEY_QURAN_WARD_MINUTE = "quran_ward_minute"
        const val KEY_QURAN_WARD_DAY = "quran_ward_day"
        const val KEY_QURAN_WARD_LAST_PAGE = "quran_ward_last_page"
        const val KEY_QURAN_WARD_COUNT = "quran_ward_count"
        const val QURAN_FONT_MIN = 20f
        const val QURAN_FONT_DEFAULT = 26f
        const val QURAN_FONT_MAX = 72f
        const val KEY_AUTO_DOWNLOAD = "auto_dl_enabled"
        const val KEY_AUTO_TARGET = "auto_dl_target"
        const val KEY_WIFI_ONLY = "auto_dl_wifi_only"
        const val KEY_CONTINUE_REMINDER = "pref_continue_reminder"
        const val KEY_ANALYTICS = "analytics_event_counts"
        const val KEY_NOTIFICATION_SEEN = "notif_last_seen_ms"
        const val KEY_SUBMISSIONS_SEEN = "my_subs_seen_ms"
        const val KEY_NOTIFICATIONS = "notif_enabled"
        const val KEY_DISMISSED_NOTIFICATIONS = "notif_dismissed"
        const val KEY_BOOKMARKS = "pers_bookmarks"
        const val KEY_FOLLOWED_SUBS = "pers_followed_subs"
        const val KEY_WARD_HOUR = "ward_hour"
        const val KEY_WARD_MINUTE = "ward_minute"
        const val KEY_DAILY_SECONDS = "stat_daily_seconds"
        const val KEY_WEEKLY_GOAL = "goal_weekly_min"
        const val KEY_KNOWN_SUBMISSION_STATUSES = "submission_known_statuses_v1"
        const val KEY_SUBMITTER_NAME = "submitter_name_v1"

        private val PERSONAL_KEYS = setOf(
            KEY_DOWNLOADS,
            KEY_PLAY_COUNTS,
            KEY_RECENT,
            KEY_CATEGORY_VISITS,
            KEY_SUBCATEGORY_VISITS,
            KEY_POSITIONS,
            KEY_COMPLETED,
            KEY_DURATIONS,
            KEY_VIEW_COUNTED,
            KEY_FAVORITES,
            KEY_SEARCH_HISTORY,
            KEY_PLAYLISTS,
            KEY_STREAK,
            KEY_LAST_LISTEN_DATE,
            KEY_BOOKMARKS,
            KEY_FOLLOWED_SUBS,
            KEY_DAILY_SECONDS,
            KEY_ANALYTICS,
            KEY_NOTIFICATION_SEEN,
            KEY_DISMISSED_NOTIFICATIONS,
            KEY_SUBMISSIONS_SEEN,
            KEY_WEEKLY_GOAL,
            KEY_KNOWN_SUBMISSION_STATUSES,
            KEY_SUBMITTER_NAME,
            // ⚠️ علامات المصحف وموضع القراءة وعدّادات الأذكار وسلسلتها بيانات
            // شخصيّة بحتة يكتبها المستخدم بنفسه؛ كانت تنجو من «حذف بياناتي»
            // فيبقى «تابع القراءة» وعلاماته بعد وعدٍ صريح بمحو كل شيء.
            KEY_QURAN_BOOKMARKS,
            KEY_QURAN_LAST,
            // عدّاد صفحات الوِرد اليومي أثرُ قراءةٍ شخصيّ، فبقاؤه بعد «حذف
            // بياناتي» يجعل «بقي كذا» يحكي يوماً وُعد صاحبه بمحوه. (أمّا
            // المقدار ووقت التذكير فإعدادٌ لا بيانات — يبقيان كوِرد الدروس.)
            KEY_QURAN_WARD_DAY,
            KEY_QURAN_WARD_LAST_PAGE,
            KEY_QURAN_WARD_COUNT,
            "adhkar_counts",
            "adhkar_day",
            "adhkar_streak",
            "adhkar_streak_day",
        )

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: LocalStore(context).also { instance = it }
        }
    }
}

private fun <T> JSONArray.objects(parser: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        runCatching { parser(item) }.getOrNull()?.let(::add)
    }
}

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) {
        optString(index).takeIf(String::isNotEmpty)?.let(::add)
    }
}

private fun JSONObject.stringMap(): Map<String, String> = buildMap {
    keys().forEach { key -> put(key, optString(key)) }
}

private fun JSONObject.longMap(): Map<String, Long> = buildMap {
    keys().forEach { key ->
        val value = opt(key).longValue()
        put(key, value)
    }
}
