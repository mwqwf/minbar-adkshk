package com.ali.menbaradkshk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ali.menbaradkshk.ui.AppViewModel
import com.ali.menbaradkshk.ui.MinbarApp
import com.ali.menbaradkshk.ui.MinbarTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) viewModel.showMessage("يمكنك تفعيل الإشعارات لاحقًا من إعدادات النظام.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(true)
            },
        )
        viewModel.handleDeepLink(intent?.data)
        // إعادة إنشاء النشاط (تدوير الشاشة) تعيد النيّة نفسها — لا تُلتقط مرّتين.
        if (savedInstanceState == null) captureShare(intent)
        setContent {
            // القراءة الفعلية لـrevision داخل النطاق شرط إعادة التركيب عند
            // تغيير السمة أو حجم الخط من الإعدادات.
            val revision by viewModel.store.revision.collectAsState()
            val themeMode = remember(revision) { viewModel.store.themeMode() }
            val fontScale = remember(revision) { viewModel.store.fontScale() }
            MinbarTheme(
                themeMode = themeMode,
                fontScale = fontScale,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MinbarApp(viewModel, ::requestNotificationPermission)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleDeepLink(intent.data)
        captureShare(intent)
    }

    /// «مشاركة إلى منبر»: يستخرج روابط الصوت من نيّة SEND / SEND_MULTIPLE
    /// ويمرّرها إلى الـViewModel ليفتح نموذج «شارك درساً» معبّأً بها.
    private fun captureShare(intent: Intent?) {
        if (intent == null) return
        // حمولة تطبيق آخر قد تكون تالفة أو من نوع غير Uri — لا تُسقط التطبيق.
        val uris: List<Uri> = runCatching { sharedAudioUris(intent) }.getOrDefault(emptyList())
        if (uris.isEmpty()) return
        // تُستهلك النيّة فوراً كي لا تتكرّر الملفات إن عاد النشاط إليها.
        runCatching { intent.removeExtra(Intent.EXTRA_STREAM) }
        viewModel.receiveSharedAudio(uris)
    }

    private fun sharedAudioUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list.orEmpty().filterNotNull()
            }

            else -> emptyList()
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
