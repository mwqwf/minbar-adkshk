package com.ali.menbaradkshk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.ali.menbaradkshk.ui.isDarkTheme

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
            // شريطا النظام يتبعان سمة **التطبيق** لا سمة الجهاز: المستخدم قد
            // يختار داكناً بينما النظام فاتح، فتصير الأيقونات غير مقروءة لو
            // تُركت للسلوك التلقائي. والشريط العلوي داكن في السمتين (Teal /
            // DarkAppBar) فأيقونات الحالة فاتحة دائماً، بينما شريط التنقّل
            // السفلي يتبع سطح التطبيق. هذا يحلّ محلّ statusBarColor/
            // navigationBarColor المتوقّفتين في أندرويد 15.
            val dark = isDarkTheme(themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                )
            }
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

    /// «مشاركة إلى منبر»: صوتيات ← «شارك درساً»، وصور/نص ← «ساهم بالنص»
    /// (باختيار الدرس). حمولة تطبيق آخر قد تكون تالفة — لا تُسقط التطبيق.
    private fun captureShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val type = intent.type.orEmpty()
        val uris: List<Uri> = runCatching { sharedStreamUris(intent) }.getOrDefault(emptyList())
        when {
            type.startsWith("audio/") && uris.isNotEmpty() -> {
                // تُستهلك النيّة فوراً كي لا تتكرّر الملفات إن عاد النشاط إليها.
                runCatching { intent.removeExtra(Intent.EXTRA_STREAM) }
                viewModel.receiveSharedAudio(uris)
            }

            type.startsWith("image/") && uris.isNotEmpty() -> {
                runCatching { intent.removeExtra(Intent.EXTRA_STREAM) }
                viewModel.receiveSharedTranscript(text = "", imageUris = uris)
            }

            type.startsWith("text/") -> {
                val text = runCatching {
                    intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                }.getOrDefault("")
                if (text.isNotBlank()) {
                    runCatching { intent.removeExtra(Intent.EXTRA_TEXT) }
                    viewModel.receiveSharedTranscript(text = text, imageUris = emptyList())
                }
            }
        }
    }

    private fun sharedStreamUris(intent: Intent): List<Uri> {
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
