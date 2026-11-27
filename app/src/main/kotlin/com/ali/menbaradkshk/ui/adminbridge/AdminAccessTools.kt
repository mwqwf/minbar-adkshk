package com.ali.menbaradkshk.ui.adminbridge

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Result returned by the authorized host. The expiry is the server's absolute epoch-millis value. */
class AdminIssuedAccessCode(val code: String, val expiresAtMs: Long)

private enum class AdminAccessAction { Invite, Link }

private class VisibleAccessCode(val action: AdminAccessAction, val result: AdminIssuedAccessCode)

/**
 * UI only. The host provides session-bound requests and owns rejection/reauthentication.
 * Invite creation never changes the supplied role; the host must request the supervisor role.
 */
@Composable
fun AdminAccessTools(
    owner: Boolean,
    issueInvite: suspend (email: String, name: String) -> AdminIssuedAccessCode,
    issueLink: suspend () -> AdminIssuedAccessCode,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<AdminAccessAction?>(null) }
    var issuing by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf<VisibleAccessCode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ownerNow by rememberUpdatedState(owner)
    val scope = rememberCoroutineScope()
    val cleanEmail = email.trim()
    val cleanName = name.trim()
    val emailValid = cleanEmail.length <= 254 && Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()

    LaunchedEffect(owner) {
        if (!owner) {
            if (pending == AdminAccessAction.Invite) pending = null
            if (visible?.action == AdminAccessAction.Invite) visible = null
            email = ""
            name = ""
        }
    }
    LaunchedEffect(visible) {
        val shown = visible ?: return@LaunchedEffect
        // Recheck periodically so clock changes cannot leave a stale code on screen indefinitely.
        while (visible === shown) {
            val remaining = shown.result.expiresAtMs - System.currentTimeMillis()
            if (remaining <= 0) {
                visible = null
                message = "انتهت صلاحية الرمز."
                break
            }
            delay(remaining.coerceAtMost(1_000L))
        }
    }

    fun confirm(action: AdminAccessAction) {
        if (issuing || pending != action) return
        if (action == AdminAccessAction.Invite && (!ownerNow || !emailValid)) return
        pending = null
        issuing = true
        message = null
        visible = null
        scope.launch {
            try {
                val result = when (action) {
                    AdminAccessAction.Invite -> issueInvite(cleanEmail, cleanName)
                    AdminAccessAction.Link -> issueLink()
                }
                if (action != AdminAccessAction.Invite || ownerNow) {
                    if (result.code.isNotBlank() && result.expiresAtMs > System.currentTimeMillis()) {
                        visible = VisibleAccessCode(action, result)
                    } else {
                        message = "الرمز غير صالح الآن."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not expose response or exception details: they may contain access material.
                message = "تعذّر إصدار الرمز."
            } finally {
                issuing = false
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("أدوات الوصول", style = MaterialTheme.typography.titleLarge)
            Text("الدعوات للحسابات الإشرافية فقط. لا يوجد تسجيل عام.")
            if (owner) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(254) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("بريد المشرف") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !issuing,
                    isError = cleanEmail.isNotEmpty() && !emailValid,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("اسم المشرف (اختياري)") },
                    singleLine = true,
                    enabled = !issuing,
                )
                Button(onClick = { pending = AdminAccessAction.Invite }, enabled = emailValid && !issuing && pending == null) {
                    Text("إصدار دعوة مشرف")
                }
                Text("صلاحية الدعوة ٢٤ ساعة.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { pending = AdminAccessAction.Link }, enabled = !issuing && pending == null) {
                Text("ربط جهاز بحسابي الحالي")
            }
            Text("صلاحية رمز الربط ١٠ دقائق. لا ينشئ حسابًا جديدًا.", style = MaterialTheme.typography.bodySmall)
            if (issuing) Text("جارٍ إصدار الرمز…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }

        pending?.takeIf { it != AdminAccessAction.Invite || owner }?.let { action ->
            val invite = action == AdminAccessAction.Invite
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(if (invite) "إصدار دعوة مشرف؟" else "إصدار رمز ربط؟") },
                text = {
                    Text(
                        if (invite) "ستصدر دعوة لبريد $cleanEmail لمدة ٢٤ ساعة."
                        else "سيُصدر رمز لربط جهاز آخر بحسابك الحالي لمدة ١٠ دقائق."
                    )
                },
                confirmButton = { TextButton(onClick = { confirm(action) }, enabled = !issuing) { Text("تأكيد الإصدار") } },
                dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } },
            )
        }

        visible?.takeIf { it.action != AdminAccessAction.Invite || owner }?.let { shown ->
            AlertDialog(
                onDismissRequest = { visible = null },
                properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
                title = { Text(if (shown.action == AdminAccessAction.Invite) "رمز الدعوة" else "رمز ربط الجهاز") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(shown.result.code, style = MaterialTheme.typography.titleLarge)
                        }
                        Text("تنتهي الصلاحية: " + DateFormat.getDateTimeInstance().format(Date(shown.result.expiresAtMs)))
                    }
                },
                confirmButton = { TextButton(onClick = { visible = null }) { Text("إغلاق") } },
            )
        }
    }
}
