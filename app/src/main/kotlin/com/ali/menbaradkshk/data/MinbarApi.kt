package com.ali.menbaradkshk.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * ☁️ عميل واجهة منبر (`minbar-api` على Cloudflare Workers + D1) — بديل قراءات
 * Firestore ودوال Functions كلّها (قرار المالك 2026-09-10 بعد عطل الفوترة).
 *
 * **قاعدتان:**
 * - **قاعدتان للنطاق لا واحدة**: الأولى `minbar-api` على Cloudflare، والثانية
 *   مرآة قراءة عبر نطاق الموقع (Vercel) تُعيد الاستجابة نفسها حرفياً. بعض
 *   الشبكات الجزائرية تحجب عناوين Cloudflare (حادثة 2026-08-30)، فمن وصله
 *   الموقع وصلته المكتبة. النطاق الناجح يُذكر لبقيّة الجلسة.
 * - **لا كاش خفيّ**: كل نداء يسأل الخادم فعلاً؛ ما كان يفعله Firestore SDK
 *   من إعادة الكاش عند سقوط الخادم جعل جهازاً يظنّ «لا جديد» أياماً.
 */
object MinbarApi {
    private val BASES = listOf(
        "https://minbar-api.mushafak.workers.dev",
        "https://minbar-adkassahk.vercel.app",
    )
    private const val UA = "MinbarAdkassahk/${com.ali.menbaradkshk.BuildConfig.VERSION_NAME}"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 30_000

    /** مرآة الصوت خارج Cloudflare (بروكسي الموقع) — تُشتقّ من البصمة. */
    fun audioMirrorUrl(sha256: String): String =
        "https://minbar-adkassahk.vercel.app/audio/$sha256.ogg"

    /** الرابط الأصلي للصوت على R2 من البصمة (للتراجع عن المرآة أيضاً). */
    fun audioPrimaryUrl(sha256: String): String = "https://media.menbar.app/serving/$sha256.ogg"

    fun isMirrorUrl(url: String): Boolean = url.contains("vercel.app/audio/")

    @Volatile private var preferred = 0

    class ApiException(val code: Int, message: String) : IOException(message)

    private fun open(base: String, path: String, method: String, body: String?, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = (if (connection.responseCode >= 400) connection.errorStream else connection.inputStream)
            ?: return ""
        val raw = if (connection.contentEncoding == "gzip") GZIPInputStream(stream) else stream
        return raw.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * ينفّذ الطلب على النطاق المفضَّل ثم البديل. الأخطاء ذات المعنى (404، 4xx)
     * لا تُعاد على النطاق الآخر — الخادم أجاب فعلاً؛ الانقطاع وحده يُبدِّل النطاق.
     */
    private fun request(path: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): String {
        var last: IOException? = null
        val order = if (preferred == 0) listOf(0, 1) else listOf(1, 0)
        for (index in order) {
            val base = BASES[index]
            var connection: HttpURLConnection? = null
            try {
                connection = open(base, path, method, body, headers)
                val code = connection.responseCode
                val text = readBody(connection)
                if (code in 200..299) {
                    preferred = index
                    return text
                }
                if (code in 400..499) throw ApiException(code, text.take(300))
                last = ApiException(code, "HTTP $code من $base")
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                last = e
            } finally {
                connection?.disconnect()
            }
        }
        throw last ?: IOException("تعذّر الاتصال بخادم منبر")
    }

    private fun getJson(path: String): JSONObject = JSONObject(request(path))
    private fun postJson(path: String, body: JSONObject): JSONObject =
        JSONObject(request(path, "POST", body.toString()).ifBlank { "{}" })

    // ---------- المحتوى ----------

    /** الكتالوج كاملاً (نفس عقد `/api/catalog`). */
    fun catalog(): JSONObject = getJson("/v1/catalog")

    /** علامات الخادم: الأعداد الثلاثة وأحدث طابع في كل مجموعة + أرضية الدلتا. */
    fun probe(): JSONObject = getJson("/v1/probe")

    /** ما تغيّر منذ العلامات المحفوظة (وسجلّ الحذف منذ علامته). */
    fun delta(
        lessonsSince: Long,
        categoriesSince: Long,
        subcategoriesSince: Long,
        deletedSince: Long,
    ): JSONObject = getJson(
        "/v1/delta?since=$lessonsSince&categoriesSince=$categoriesSince" +
            "&subcategoriesSince=$subcategoriesSince&deletedSince=$deletedSince",
    )

    /** النص المشروح للدرس؛ `null` إن لم يكن له نصّ. */
    fun transcript(lessonId: String): JSONObject? = try {
        getJson("/v1/transcripts/${encode(lessonId)}")
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    /** بحث في النصوص المشروحة: `[{lessonId}]` للدروس التي يرد فيها النصّ. */
    fun searchTranscripts(query: String, limit: Int = 25): JSONArray =
        getJson("/v1/transcripts/search?q=${encode(query)}&limit=$limit").optJSONArray("items") ?: JSONArray()

    /** إعداد بعينه (مثل `android` لتذكير التحديث)؛ `null` إن لم يُضبط. */
    fun config(key: String): JSONObject? = try {
        getJson("/v1/config/$key")
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    /** خلاصة الإشعارات العامة منذ لحظة. */
    fun notifications(sinceMs: Long, limit: Int = 30): JSONArray =
        getJson("/v1/notifications?since=$sinceMs&limit=$limit").optJSONArray("items") ?: JSONArray()

    // ---------- الكتابة ----------

    fun incrementView(lessonId: String) {
        request("/v1/lessons/${encode(lessonId)}/view", "POST", "{}")
    }

    fun feedback(lessonId: String, kind: String, text: String, contact: String = "") {
        postJson(
            "/v1/feedback",
            JSONObject().put("lessonId", lessonId).put("kind", kind).put("text", text).put("contact", contact),
        )
    }

    /**
     * تسجيل الجهاز (رمز FCM + الإصدار + المواضيع). يعوّض `reportAppVersion`:
     * الخادم نفسه يطبّق حرّاس الإعلان (حزمة المتجر · مثبَّت من Play · مهلة ساعة).
     */
    fun registerDevice(context: Context, token: String, summary: String): JSONObject {
        val app = context.applicationContext
        val installer = runCatching {
            val pm = app.packageManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(app.packageName)
            }
        }.getOrNull().orEmpty()
        val store = LocalStore.get(app)
        val topics = JSONArray()
        if (store.notificationsEnabled()) {
            topics.put("content")
            store.followedSubcategories().forEach { topics.put("sec_$it") }
        }
        return postJson(
            "/v1/devices",
            JSONObject()
                .put("token", token)
                .put("platform", "android")
                .put("packageName", app.packageName)
                .put("versionCode", com.ali.menbaradkshk.BuildConfig.VERSION_CODE)
                .put("versionName", com.ali.menbaradkshk.BuildConfig.VERSION_NAME)
                .put("installer", installer)
                .put("summary", summary)
                .put("topics", topics),
        )
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    // ---------- مجتمع المستمعين: بمعرّف الجهاز (X-Device-Id) ----------

    /** معرّف التثبيت المستقرّ — نفس ما يسجّله [AppConfigRepository.installId]. */
    fun deviceId(context: Context): String = AppConfigRepository.get(context).installId()

    /** رابط قراءة عام لمرفق في R2 بمفتاحه (مرفقات الدعم وصور النصوص). */
    fun mediaUrl(key: String): String = "${BASES[0]}/media/$key"

    private fun userHeaders(context: Context): Map<String, String> = mapOf("X-Device-Id" to deviceId(context))

    private fun requestUser(context: Context, path: String, method: String, body: String?): String =
        request(path, method, body, userHeaders(context))

    fun getUser(context: Context, path: String): JSONObject = JSONObject(requestUser(context, path, "GET", null))
    fun postUser(context: Context, path: String, body: JSONObject): JSONObject =
        JSONObject(requestUser(context, path, "POST", body.toString()).ifBlank { "{}" })
    fun putUser(context: Context, path: String, body: JSONObject): JSONObject =
        JSONObject(requestUser(context, path, "PUT", body.toString()).ifBlank { "{}" })
    fun deleteUser(context: Context, path: String): JSONObject =
        JSONObject(requestUser(context, path, "DELETE", null).ifBlank { "{}" })

    /**
     * رفع مرفق المستخدم (`PUT /v1/upload/{kind}/{name}`) ببثّ ثابت الطول وتبليغ
     * النسبة. يعيد `{key, sha256, sizeBytes}`. النطاق الأول ثم البديل عند
     * الانقطاع (لا عند رفض الخادم).
     */
    fun uploadUser(
        context: Context,
        kind: String,
        name: String,
        uri: android.net.Uri,
        contentType: String,
        onProgress: (Int) -> Unit = {},
    ): JSONObject {
        val resolver = context.contentResolver
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(total > 0L) { "تعذّر قراءة الملف." }
        val path = "/v1/upload/$kind/" + encode(name)
        var last: IOException? = null
        val order = if (preferred == 0) listOf(0, 1) else listOf(1, 0)
        for (index in order) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(BASES[index] + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 120_000
                    requestMethod = "PUT"
                    doOutput = true
                    setFixedLengthStreamingMode(total)
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Content-Type", contentType)
                    setRequestProperty("X-Device-Id", deviceId(context))
                }
                connection.outputStream.use { output ->
                    resolver.openInputStream(uri)!!.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        var lastPercent = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sent += count
                            val percent = ((sent * 100L) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                val code = connection.responseCode
                val text = readBody(connection)
                if (code in 200..299) {
                    preferred = index
                    return JSONObject(text)
                }
                if (code in 400..499) throw ApiException(code, text.take(300))
                last = ApiException(code, "HTTP $code")
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                last = e
            } finally {
                connection?.disconnect()
            }
        }
        throw last ?: IOException("تعذّر رفع الملف")
    }
}
