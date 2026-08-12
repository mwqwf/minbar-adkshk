package com.ali.menbaradkshk.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// شاشة «حول التطبيق» — صفحة كاملة (لا حوار): تعريف مستمدّ من موقع منبر،
/// وإشارة المصدر المفتوح مع رابط حساب GitHub، ورابط نسخة الويب.
/// ⚠️ قاعدة دائمة: مع كل رفع لإصدار التطبيق يُراجَع هذا الوصف ويُحدَّث
/// بما استجدّ (رقم الإصدار يُقرأ آلياً فلا يحتاج تدخلاً).
@Composable
fun AboutScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
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
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Teal,
        )
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Text(
                "منصّة تحفظ إرث مشايخ ادكصهك وتوفّر لهم منبرًا لنشر صوتياتهم " +
                    "لأكبر قدر من الجمهور: مئات الصوتيات العلمية الدينية التي تشرح " +
                    "كتبًا مختلفة، وتسجيلات نادرة لأشهر المشايخ، محدّثة باستمرار — " +
                    "مكتبة مصنّفة بحسب العلوم، ومشغّل متقدّم، وتنزيل واستماع دون " +
                    "إنترنت، بلا حساب وبأقل بيانات.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
            )
        }
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ListItem(
                modifier = Modifier.clickable { openUrl(WEB_APP_URL) },
                colors = ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                leadingContent = { Icon(Icons.Filled.Language, null, tint = Teal) },
                headlineContent = { Text("استمع عبر الويب") },
                supportingContent = {
                    Text(
                        "نسخة ويب كاملة من منبر ادكصهك: تصفّح الأقسام وشغّل " +
                            "الصوتيات مباشرة في المتصفح — بنفس المحتوى محدَّثًا لحظيًا.",
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable { openUrl(GITHUB_URL) },
                colors = ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                leadingContent = { Icon(Icons.Filled.Code, null, tint = Teal) },
                headlineContent = { Text("مفتوح المصدر بالكامل") },
                supportingContent = {
                    Text(
                        "اطّلع على جميع مشاريعنا مفتوحة المصدر على GitHub، بما فيها " +
                            "تطبيق منبر ادكصهك بشقّيه (التطبيق ولوحة الإدارة) وبجميع " +
                            "نسخه: كوتلن وغيرها، ونسخة الويب أيضًا.\ngithub.com/mwqwf",
                    )
                },
            )
            ListItem(
                colors = ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                leadingContent = { Icon(Icons.Filled.Headphones, null, tint = Teal) },
                headlineContent = { Text("تطبيق صوتي بامتياز") },
                supportingContent = {
                    Text(
                        "مشغّل بسرعات متعددة ومؤقّت نوم وتشغيل خلفي، وقوائم ومفضّلة " +
                            "وسجلّ و«تابع الاستماع»، وبحث عربي ذكي، ومشاركة الدروس " +
                            "بتوقيت اللحظة، و«شارك درسًا» لرفع التسجيلات النادرة.",
                    )
                },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "منبر ادكصهك — دروس صوتية",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

const val GITHUB_URL = "https://github.com/mwqwf"
const val WEB_APP_URL = "https://minbar-adkassahk.vercel.app"
