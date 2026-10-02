package com.ali.menbaradkshk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.BuildConfig

/**
 * 📖 التعريف بتطبيق «مصحفك» (أمر المالك 2026-10-02).
 *
 * ثلاثة مداخل: **زرٌّ دائم** (المصحف · الأذكار · «حول») يأخذ إلى المتجر مباشرةً
 * بلا إظهار رابط، و**تذكيرٌ** يصف التطبيق ويدعو إلى تنزيله — يظهر أوّلَ فتحٍ
 * بعد هذا التحديث، ثم مرّةً بين حينٍ وآخر، و**يختفي نهائياً** عند أوّل نقرةٍ
 * عليه. ومن ثبّت «مصحفك» فعلاً لا يُذكَّر، والزرّ عنده يفتح التطبيق نفسه.
 *
 * الحالة في ملفّ تفضيلاتٍ مستقلّ: لا ترفع `revision` المخزن العامّ فلا تُعيد
 * رسم شيء، وهي ثلاث قيم صغيرة تُقرأ من الذاكرة.
 */
object MushafakPromo {
    const val PACKAGE = "com.mushafak.app"
    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /// الفاصل بين تذكيرين — أسبوعٌ يكفي ليُرى ولا يُضجِر.
    private const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private const val KEY_CLICKED = "clicked"
    private const val KEY_LAST_SHOWN = "last_shown_ms"
    private const val KEY_SHOWN_VERSION = "shown_version"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("mushafak_promo", Context.MODE_PRIVATE)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null
    }.getOrDefault(false)

    /**
     * هل يُعرض التذكير الآن؟ لا — لمن نقره مرّةً أو ثبّت التطبيق. نعم — أوّلَ
     * فتحٍ في إصدارٍ لم يُعرض فيه بعد (فيظهر مع هذا التحديث)، أو بعد مضيّ أسبوع.
     */
    fun shouldRemind(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_CLICKED, false)) return false
        if (isInstalled(context)) return false
        if (p.getInt(KEY_SHOWN_VERSION, 0) != BuildConfig.VERSION_CODE) return true
        return now - p.getLong(KEY_LAST_SHOWN, 0L) >= INTERVAL_MS
    }

    fun markShown(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putLong(KEY_LAST_SHOWN, now)
            .putInt(KEY_SHOWN_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun markClicked(context: Context) {
        prefs(context).edit().putBoolean(KEY_CLICKED, true).apply()
    }

    /// يفتح «مصحفك» إن كان مثبّتاً، وإلا صفحته في المتجر (تطبيق Play أوّلاً
    /// ثم المتصفّح) — بلا إظهار الرابط للمستخدم.
    fun open(context: Context) {
        val installed = runCatching {
            context.packageManager.getLaunchIntentForPackage(PACKAGE)
        }.getOrNull()
        val candidates = listOfNotNull(
            installed,
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
                .setPackage("com.android.vending"),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL)),
        )
        for (intent in candidates) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }
}

/**
 * 📖 الزرّ الدائم: بطاقةٌ صغيرة بسطرين — اسمُ التطبيق وما يقدّمه — والنقرة
 * تذهب إلى المتجر مباشرةً (أو تفتح التطبيق إن كان مثبّتاً).
 */
@Composable
fun MushafakButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed = remember { MushafakPromo.isInstalled(context) }
    val accent = brandTintOnSurface(Teal)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier.clickable { MushafakPromo.open(context) }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(accent.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = accent) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("تطبيق «مصحفك»", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "حفظٌ وتسميعٌ وتلاواتٌ وأذكار — مجاناً",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (installed) "افتح" else "حمّل",
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 🔔 التذكير: يصف «مصحفك» ومزاياه ويدعو إلى تنزيله. **أيّ نقرةٍ على «حمّله
 * الآن» تُخفيه نهائياً**؛ و«لاحقاً» يؤجّله إلى الموعد التالي فقط.
 */
@Composable
fun MushafakReminderDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = Teal,
                modifier = Modifier.size(40.dp),
            )
        },
        title = { Text("جرّب تطبيق «مصحفك»", textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(
                    "رفيقُك اليوميّ مع القرآن، من صانع «منبر ادكصهك»:",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                listOf(
                    "📖 مصحفٌ كامل بالروايات وتلاوات كبار القرّاء",
                    "🎙️ تسميعٌ ذكيّ يصحّح حفظك وأنت تتلو",
                    "🗓️ وِردٌ يوميّ وخطّة حفظ ومراجعة",
                    "📿 أذكارٌ ومسبحة وتذكيرات",
                    "🆓 مجانيّ على متجر Google Play",
                ).forEach { Text(it, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                MushafakPromo.markClicked(context)
                MushafakPromo.open(context)
                onDismiss()
            }) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("حمّله الآن")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("لاحقاً") }
        },
    )
}
