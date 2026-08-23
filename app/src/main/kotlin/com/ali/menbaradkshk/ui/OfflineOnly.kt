package com.ali.menbaradkshk.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Lesson

/**
 * 📴 وضع «بلا إنترنت» — مرشّحٌ ظاهر لا رسالة خطأ.
 *
 * **المشكلة**: عند انقطاع الشبكة تبقى القوائم كاملة كما هي، فينقر المستخدم
 * درساً غير منزَّل وينتهي الأمر برسالة فشل تشغيل لا يفهم سببها. وجمهور
 * التطبيق إنترنته ضعيف متقطّع، فهذه هي الحال الغالبة لا النادرة.
 *
 * **الحلّ**: شريطٌ واحد بمفتاح واحد أعلى الرئيسية والمكتبة يقول ما جرى
 * («لا يوجد إنترنت») ويعرض المخرج في الجملة نفسها («أظهر المحفوظ فقط»).
 * حين يُفعَّل لا يظهر في القوائم إلا ما هو منزَّل فعلاً على الجهاز — فكل ما
 * يراه المستخدم يعمل بالنقر.
 *
 * ويُطفأ ذاتياً عند عودة الشبكة ويختفي الشريط: المستخدم لا يُطالَب بإلغاء
 * وضعٍ فرضته الظروف بعد زوالها.
 */

/// هل هناك إنترنت الآن؟ مراقبة حيّة عبر `ConnectivityManager` — بنفس نمط
/// فحص الشبكة في `DownloadQueueProcessor`، مع إضافة المراقبة المستمرّة لأنّ
/// الشريط يجب أن يظهر ويختفي مع الشبكة لا مع دخول الشاشة وخروجها.
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(ConnectivityManager::class.java)
    }
    // القراءة الأولى قبل وصول أوّل بلاغ: غياب المدير يُعدّ «متصلاً» كي لا
    // يظهر الشريط على جهاز لم نستطع قراءة حالته.
    var online by remember {
        mutableStateOf(
            manager?.let { hasInternet(it) } ?: true,
        )
    }
    DisposableEffect(manager) {
        val connectivity = manager ?: return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                // الشبكة المفقودة قد تكون واحدةً من عدّة، فنعيد السؤال العام.
                online = hasInternet(connectivity)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun hasInternet(manager: ConnectivityManager): Boolean {
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/// الشريط نفسه — لا يظهر إلا حين لا إنترنت، ويطفئ المفتاح عند عودتها.
@Composable
fun SavedOnlyBar(vm: AppViewModel) {
    val online = rememberOnline()
    val savedOnly by vm.savedOnly.collectAsState()
    LaunchedEffect(online) {
        if (online) vm.setSavedOnly(false)
    }
    if (online) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OrangeBrand.copy(alpha = 0.14f))
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = OrangeBrand,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "لا يوجد إنترنت",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "أظهر المحفوظ فقط",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // المفتاح وحده هدف لمسٍ صغير، فيُوسَّع إلى ٤٨dp كاملة كبقيّة أهداف
        // اللمس في التطبيق.
        Switch(
            checked = savedOnly,
            onCheckedChange = { vm.setSavedOnly(it) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/// معرّفات الدروس المنزَّلة فعلاً حين يكون الوضع مُفعَّلاً، و`null` حين لا
/// ترشيح — فالحالة الغالبة (شبكة موجودة) لا تكلّف قراءةً ولا مسحاً للقرص.
///
/// الخريطة تُقرأ مرّة واحدة لا مرّة لكل درس: `isDownloaded` يفكّ خريطة JSON
/// كاملةً من التفضيلات في كل نداء (نفس سبب اللفّ في `AudioItem`).
@Composable
fun rememberSavedOnlyIds(vm: AppViewModel): Set<String>? {
    val savedOnly by vm.savedOnly.collectAsState()
    val revision by vm.store.revision.collectAsState()
    return remember(savedOnly, revision) {
        if (!savedOnly) {
            null
        } else {
            vm.downloads.all()
                .filterValues { path -> java.io.File(path).isFile }
                .keys
        }
    }
}

/// ترشيح قائمة دروس بمعرّفات [ids] (لا شيء يتغيّر حين تكون `null`).
fun List<Lesson>.savedOnly(ids: Set<String>?): List<Lesson> =
    if (ids == null) this else filter { it.id in ids }

/// ترشيح أي قائمة دروس على «المحفوظ فقط» — للشاشات ذات القائمة الواحدة.
@Composable
fun rememberSavedOnly(vm: AppViewModel, lessons: List<Lesson>): List<Lesson> {
    val ids = rememberSavedOnlyIds(vm)
    return remember(ids, lessons) { lessons.savedOnly(ids) }
}
