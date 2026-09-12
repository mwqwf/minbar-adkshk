package com.ali.menbaradkshk.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 🔔 خلاصة الإشعارات — من `minbar-api` (قرار 2026-09-10):
 * - **العامة** (`/v1/notifications`): ما يبثّه المشرفون للجميع.
 * - **الخاصة** (`/v1/me/notifications` بمعرّف الجهاز): قرارات مساهماتي
 *   واقتراحاتي وردود المطوّر — لا تُسأل إلا لمن ساهم أو راسل من قبل.
 * استطلاعٌ خفيف كل خمس دقائق ما دامت الشاشة تجمع، بدل مستمعي Firestore.
 */
class NotificationsRepository(
    private val context: Context,
    private val submissions: SubmissionRepository,
    private val hasContributedBefore: () -> Boolean = { true },
    private val installedAtMs: () -> Long = { 0L },
) {
    private companion object {
        const val TAG = "NotificationsRepo"
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1_000
        const val POLL_MS = 5L * 60 * 1_000
    }

    /// حدّ القصّ الزمني: آخر ثلاثين يوماً ولا شيء قبل تثبيت التطبيق.
    private fun cutoffMs(): Long =
        maxOf(System.currentTimeMillis() - WINDOW_MS, installedAtMs())

    fun stream(limit: Long = 30): Flow<List<NotificationItem>> = callbackFlow {
        var publicItems = listOf<NotificationItem>()
        var privateItems = listOf<NotificationItem>()
        // ⚠️ الشبكة على IO لا على سياق الجامع: التدفّق يُجمع من `viewModelScope`
        // (الخيط الرئيسي)، وكان `MinbarApi` يُنادى عليه فيرمي
        // `NetworkOnMainThreadException` في كل دورة ويُبتلع في `runCatching` —
        // فلا تصل إشعارات الخادم أبداً بلا أي أثر ظاهر.
        val scope = CoroutineScope(coroutineContext + Job() + kotlinx.coroutines.Dispatchers.IO)

        fun emit() {
            val items = (publicItems + privateItems)
                .sortedByDescending(NotificationItem::createdAtMs)
                .take(limit.toInt())
            trySend(items)
        }

        val job = scope.launch {
            while (isActive) {
                runCatching {
                    publicItems = MinbarApi.notifications(cutoffMs(), limit.toInt()).toItems("public")
                    emit()
                }.onFailure { Log.w(TAG, "تعذّرت قراءة الإشعارات العامة", it) }
                if (hasContributedBefore()) {
                    runCatching {
                        privateItems = (
                            MinbarApi.getUser(context, "/v1/me/notifications?since=${cutoffMs()}")
                                .optJSONArray("items") ?: JSONArray()
                            ).toItems("private")
                        emit()
                    }.onFailure { Log.w(TAG, "تعذّرت قراءة إشعارات المستخدم", it) }
                }
                delay(POLL_MS)
            }
        }

        awaitClose {
            job.cancel()
            scope.cancel()
        }
    }

    private fun JSONArray.toItems(prefix: String): List<NotificationItem> =
        (0 until length())
            .mapNotNull { index -> optJSONObject(index) }
            .map { item -> fromJson("$prefix:" + item.optString("id"), item) }

    private fun fromJson(id: String, item: JSONObject): NotificationItem = NotificationItem(
        id = id,
        title = item.optString("title"),
        body = item.optString("body"),
        type = item.optString("type"),
        lessonId = item.optString("lessonId"),
        route = item.optString("route"),
        refId = item.optString("refId").ifBlank { item.optString("lessonId") },
        createdAtMs = item.optLong("createdAtMs", 0L),
    )
}
