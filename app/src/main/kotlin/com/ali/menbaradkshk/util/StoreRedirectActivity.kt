package com.ali.menbaradkshk.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.ali.menbaradkshk.data.AppConfigRepository

/**
 * 🛒 وسيط صامت إلى المتجر — بلا واجهة ولا رابط ظاهر.
 *
 * **لماذا نشاط وسيط بدل رابط مباشر في الإشعار؟** ثلاثة أسباب مجتمعة:
 *  1. الرابط يبقى **مخفياً**: المستخدم يضغط الإشعار فيجد نفسه في المتجر،
 *     ولا يرى عنواناً ولا يُطلب منه اختيار متصفّح.
 *  2. التدرّج: `market://` يفتح تطبيق Play مباشرة، وإن غاب سقطنا إلى
 *     رابط الويب. `PendingIntent` واحد لا يستطيع هذا التدرّج بنفسه.
 *  3. أغلب مستخدمي التطبيق لا يقرؤون العربية جيّداً ولا يفهمون التقنية —
 *     فأي خطوة وسيطة (اختيار تطبيق، لصق رابط) تعني ضياع التحديث.
 *
 * بلا واجهة إطلاقاً (`Theme.NoDisplay`): يفتح ثم ينتهي في اللحظة نفسها.
 */
class StoreRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open(this, intent?.getStringExtra(EXTRA_URL).orEmpty())
        finish()
    }

    companion object {
        const val EXTRA_URL = "store_url"

        /** نيّة جاهزة للاستعمال داخل `PendingIntent` إشعارٍ ما. */
        fun intent(context: Context, url: String): Intent =
            Intent(context, StoreRedirectActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        /**
         * يفتح صفحة التطبيق في المتجر: تطبيق Play أولاً ثم الويب.
         *
         * المعرّف ثابت لا `packageName` — نسخة التطوير تحمل لاحقة `.dev`
         * وليست على المتجر، فبناء الرابط منها يفتح صفحة غير موجودة.
         */
        fun open(context: Context, url: String = ""): Boolean {
            val target = url.ifBlank { AppConfigRepository.PLAY_URL }
            val candidates = listOf(
                "market://details?id=${AppConfigRepository.STORE_PACKAGE}",
                target,
            )
            for (uri in candidates) {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(view) }.isSuccess) return true
            }
            return false
        }
    }
}
