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

    private fun open(base: String, path: String, method: String, body: String?): HttpURLConnection {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
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
    private fun request(path: String, method: String = "GET", body: String? = null): String {
        var last: IOException? = null
        val order = if (preferred == 0) listOf(0, 1) else listOf(1, 0)
        for (index in order) {
            val base = BASES[index]
            var connection: HttpURLConnection? = null
            try {
                connection = open(base, path, method, body)
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
}
