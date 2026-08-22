package com.ali.menbaradkshk.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.R
import com.ali.menbaradkshk.data.LocalStore
import java.util.concurrent.Executors

/// ودجت الشاشة الرئيسية: يعرض آخر درس مُشغَّل ويفتح التطبيق عليه،
/// وزرّه يرسل أمر تشغيل/إيقاف إلى جلسة الوسائط مباشرة.
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // ⚠️ الرسم يقرأ فهرس الدروس كاملاً ويحلّله (LocalStore.lessons)؛
        // تنفيذه على خيط البثّ الرئيسي كان يعلّق الواجهة عند كل تحديث ودجت.
        // goAsync يُبقي العملية حيّة حتى ينتهي الرسم على خيط خلفيّ.
        val pending = goAsync()
        val appContext = context.applicationContext
        worker.execute {
            try {
                appWidgetIds.forEach { id -> render(appContext, appWidgetManager, id) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val store = LocalStore.get(context)
        val lastId = store.recentPlayedIds().firstOrNull()
        val lesson = lastId?.let { id -> store.lessons().firstOrNull { it.id == id } }

        val views = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
            setTextViewText(R.id.widget_title, lesson?.displayTitle ?: context.getString(R.string.app_name))
            setTextViewText(
                R.id.widget_subtitle,
                lesson?.speaker?.takeIf { it.isNotBlank() }
                    ?: context.getString(if (lesson == null) R.string.widget_empty else R.string.widget_label),
            )

            // فتح التطبيق على الدرس نفسه (رابط عميق) أو على الرئيسية.
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (lesson != null) {
                    action = Intent.ACTION_VIEW
                    data = android.net.Uri.parse(
                        "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}",
                    )
                }
            }
            setOnClickPendingIntent(
                R.id.widget_icon,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(
                    context,
                    widgetId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            // زر التشغيل/الإيقاف: أمر وسائط إلى مستقبل أزرار الوسائط المعلن
            // في المانيفست — وهو مسار media3 الرسميّ نفسه الذي تسلكه أزرار
            // السمّاعة.
            // ⛔ لا تُعِده إلى PendingIntent.getService: بدء خدمة والتطبيق في
            // الخلفية ممنوع منذ أندرويد 8 (ويرمي استثناءً في 12+)، فكان نقر
            // الودجت بعد موت العملية لا يبلغ الخدمة أبداً — أي أن استئناف
            // التشغيل (onPlaybackResumption) لا يُشتغل، وهو غرضه الوحيد.
            val toggleIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, MediaButtonReceiver::class.java)
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                )
            }
            setOnClickPendingIntent(
                R.id.widget_play,
                PendingIntent.getBroadcast(
                    context,
                    widgetId + 1_000,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        /// خيط واحد لكل الرسم الخلفيّ — الودجت نادر التحديث فلا داعي لمجمّع.
        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "widget-render").apply { isDaemon = true }
        }

        /// يُحدِّث كل النسخ المثبّتة من الودجت (يُستدعى عند تغيّر الدرس المُشغَّل).
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, NowPlayingWidget::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, NowPlayingWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
