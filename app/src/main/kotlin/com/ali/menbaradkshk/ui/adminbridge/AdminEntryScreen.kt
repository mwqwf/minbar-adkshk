package com.ali.menbaradkshk.ui.adminbridge

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ali.menbaradkshk.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Never mounts protected content while backgrounded or until the server has verified this session. */
@Composable
internal fun AdminEntryScreen(
    onBack: () -> Unit,
    allowInteractiveSignIn: Boolean = true,
    content: @Composable (VerifiedAdminSession, onSignOut: () -> Unit) -> Unit,
) {
    val activity = LocalActivity.current
    val googleWebClientId = BuildConfig.ADMIN_GOOGLE_WEB_CLIENT_ID
    val ctx = LocalContext.current.applicationContext
    val vault = remember(ctx) { AdminTokenVault(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var generation by remember { mutableIntStateOf(0) }
    var verified by remember { mutableStateOf<VerifiedAdminSession?>(null) }
    var unlockedToken by remember { mutableStateOf<String?>(null) }
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var logoutFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var retryable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }

    fun closeLease() {
        verified?.close()
        verified = null
    }

    fun startCheck() {
        closeLease()
        loading = !logoutFailed && pendingToken == null
        retryable = false
        generation++
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    startCheck()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    closeLease()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            closeLease()
        }
    }

    LaunchedEffect(resumed, generation, busy) {
        if (!resumed || busy || logoutFailed || pendingToken != null) return@LaunchedEffect
        val checking = generation
        loading = true
        retryable = false
        try {
            val token = unlockedToken ?: withContext(Dispatchers.IO) { vault.read() }
            if (generation != checking || !resumed) return@LaunchedEffect
            if (token == null) return@LaunchedEffect
            unlockedToken = token
            val grant = try {
                AdminBridgeApi.verify(token)
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode != 401 && e.statusCode != 403) throw e
                null
            }
            if (generation != checking || !resumed) return@LaunchedEffect
            if (grant == null) {
                blocked = true
                message = "لا تحمل هذه الجلسة صلاحية إشراف سارية."
            } else {
                blocked = false
                message = null
                verified = grant.openVerifiedSession(token) {
                    scope.launch {
                        if (generation == checking && resumed) startCheck()
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (generation == checking && resumed) {
                retryable = true
                message = "تعذّر التحقق من الجلسة الآن. أعد المحاولة."
            }
        } finally {
            if (generation == checking) loading = false
        }
    }

    fun signOut() {
        val old = unlockedToken ?: pendingToken
        closeLease()
        loading = false
        busy = true
        blocked = false
        retryable = false
        message = null
        generation++
        scope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) { vault.clear() }
                logoutFailed = false
                pendingToken = null
                unlockedToken = null
                if (old != null) scope.launch { runCatching { AdminBridgeApi.revoke(old) } }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                logoutFailed = true
                blocked = true
                message = "تعذّر مسح الجلسة من الجهاز. أعد محاولة تسجيل الخروج."
            } finally {
                busy = false
            }
        }
    }

    suspend fun persistToken(token: String) {
        pendingToken = token
        withContext(NonCancellable + Dispatchers.IO) { vault.write(token) }
        pendingToken = null
        unlockedToken = token
        startCheck()
    }

    BackHandler(enabled = busy) { /* A one-time code may already be in flight. */ }

    val active = verified
    if (resumed && active != null) {
        content(active, ::signOut)
        return
    }

    if (!allowInteractiveSignIn) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("مسودة درس خاصة", style = MaterialTheme.typography.headlineSmall)
            if (loading) {
                CircularProgressIndicator()
                Text("نتحقق من جلسة الإشراف. لم نفتح التسجيل.")
            } else {
                Text("لم نفتح التسجيل. ادخل إلى لوحة الإشراف في منبر، ثم أعد مشاركة الملف من التطبيق الآخر.")
            }
            TextButton(onClick = onBack) { Text("إغلاق") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("دخول المشرفين", style = MaterialTheme.typography.headlineSmall)
        Text("هذه الصفحة خاصة بالمشرفين. لا يوجد تسجيل عام، ومالك التطبيق (المتفائل) وحده يمنح المشرفين الجدد رمز الدخول.")
        Text("إذا كنت مشرفًا بالفعل، استخدم رمز ربط جهاز من جلستك الحالية. بعد الدخول يحتفظ التطبيق بجلستك ما دامت صالحة.")
        if (loading || busy) {
            CircularProgressIndicator()
        } else if (!blocked && !retryable && pendingToken == null) {
            if (googleWebClientId.isNotEmpty()) {
                OutlinedButton(
                    enabled = resumed && activity != null,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                when (val result = signInExistingAdminWithGoogle(activity, googleWebClientId)) {
                                    is AdminGoogleResult.Issued -> persistToken(result.token)
                                    AdminGoogleResult.Cancelled -> Unit
                                    AdminGoogleResult.NoAccount -> message = "لم يُعثر على حساب Google. استخدم رمز ربط جهاز إن أردت."
                                    AdminGoogleResult.NotAuthorized -> message = "هذا الحساب غير مخوّل للإشراف. للمشرف الجديد، ارجع إلى المالك (المتفائل)."
                                    AdminGoogleResult.Unavailable -> message = "تعذّر استخدام Google في هذه النسخة أو على هذا الجهاز. استخدم رمز ربط جهاز."
                                    AdminGoogleResult.Retry -> message = "تعذّر إتمام الدخول. أعد المحاولة أو استخدم رمز ربط جهاز."
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                message = "تعذّر حفظ الجلسة. أعد محاولة حفظها."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("دخول المشرف الحالي بـ Google") }
            } else {
                Text("الدخول بـ Google غير متاح في هذه النسخة. يمكن للمشرف الحالي استخدام رمز ربط جهاز.")
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("رمز الدخول أو ربط الجهاز") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
            )
            Button(
                onClick = {
                    val input = normalizedAdminCode(code) ?: return@Button
                    code = ""
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            val token = AdminBridgeApi.redeem(input)
                            persistToken(token)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: AdminBridgeFailure) {
                            message = when (error.statusCode) {
                                400, 401, 403 -> "الرمز غير صالح أو انتهت صلاحيته."
                                429 -> "محاولات كثيرة. انتظر قليلًا ثم أعد المحاولة."
                                else -> "تعذّر الدخول. أعد المحاولة أو اطلب رمزًا جديدًا."
                            }
                        } catch (_: Exception) {
                            message = "تعذّر حفظ الدخول أو الاتصال. أعد المحاولة."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = resumed && normalizedAdminCode(code) != null,
            ) { Text("تسجيل الدخول") }
        }
        message?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        if (!busy && !loading && pendingToken != null && !logoutFailed) {
            TextButton(onClick = {
                val token = pendingToken ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        persistToken(token)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        message = "تعذّر حفظ الجلسة. تحقّق من مساحة الجهاز ثم أعد المحاولة."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("إعادة حفظ الجلسة") }
        }
        if (!busy && !loading && retryable) TextButton(onClick = ::startCheck) { Text("إعادة التحقق") }
        if (!busy && !loading && (blocked || unlockedToken != null || pendingToken != null || retryable)) {
            TextButton(onClick = ::signOut) { Text("مسح الجلسة من هذا الجهاز") }
        }
        AdminAccountDeletionLink()
        TextButton(onClick = onBack, enabled = !busy) { Text("رجوع") }
    }
}
