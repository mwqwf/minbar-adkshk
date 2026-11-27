package com.ali.menbaradkshk.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// تعريف موجز ثابت؛ تفاصيل التغييرات تبقى في ملاحظات الإصدار.
@Composable
fun AboutScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { vm.showMessage("تعذّر فتح الرابط.") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Mic, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            "منبر ادكصهك",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (versionName.isNotEmpty()) {
            Text(
                "الإصدار $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "وقفٌ لله لحفظ دروس مشايخ إدكصهك وتسجيلاتهم ونشرها. " +
                "تصفّح المكتبة، واستمع إلى الدروس ونزّلها للسماع بلا إنترنت. ويضمّ المنبر المصحف والأذكار.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
        )
        DeveloperPrayer(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth()) {
            AboutLink(Icons.Filled.Language, "منبر على الويب") { openUrl(WEB_APP_URL) }
            AboutLink(Icons.Filled.PrivacyTip, "سياسة الخصوصية") { openUrl("$WEB_APP_URL/privacy") }
            AboutLink(Icons.Filled.Code, "الشيفرة المصدرية والترخيص") { openUrl(GITHUB_URL) }
            AboutLink(Icons.Filled.OndemandVideo, "قناتنا على يوتيوب") { openUrl(YOUTUBE_URL) }
        }
        MushafakButton()
    }
}

@Composable
private fun AboutLink(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
    )
}

/** تذييل اختياري للقراءة، بلا زرّ أو تنبيه. */
@Composable
internal fun DeveloperPrayer(modifier: Modifier = Modifier) {
    Text(
        "لا تنسوا مطوّر التطبيق ووالديه من صالح دعائكم.",
        modifier = modifier,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

const val GITHUB_URL = "https://github.com/mwqwf/minbar-adkshk"
const val WEB_APP_URL = "https://minbar-adkassahk.vercel.app"
const val YOUTUBE_URL = "https://youtube.com/@Al-Mutafail"
