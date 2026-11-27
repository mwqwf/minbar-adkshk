package com.ali.menbaradkshk.adminsurface

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal const val MODERATOR_ROSTER_PATH = "/admin/guarded-moderator-roster"
internal data class ModeratorRosterItem(val email: String, val role: String)
internal data class ModeratorRoster(val items: List<ModeratorRosterItem>, val truncated: Boolean)

private val emailShape = Regex("[^@\\s\\p{Cc}\\p{Cf}]+@[^@\\s\\p{Cc}\\p{Cf}]+")
internal fun validModeratorEmail(email: String) =
    email.length in 3..254 && email == email.trim().lowercase(Locale.ROOT) && email.matches(emailShape)

private val roleNames = mapOf("owner" to "مالك", "admin" to "مسؤول", "supervisor" to "مشرف",
    "blocked" to "محظور", "removed" to "أزيل")

internal fun parseModeratorRoster(response: JSONObject): ModeratorRoster {
    fun bad(): Nothing = throw IOException("Invalid owner roster")
    if (response.keys().asSequence().toSet() != setOf("items","truncated")) bad()
    val contents = response.opt("items") as? JSONArray ?: bad()
    val truncated = response.opt("truncated") as? Boolean ?: bad()
    if (contents.length() > 50) bad()
    val seen = mutableSetOf<String>()
    val items = (0 until contents.length()).map { index ->
        val row = contents.opt(index) as? JSONObject ?: bad()
        if (row.keys().asSequence().toSet() != setOf("email","role")) bad()
        val email = row.opt("email") as? String ?: bad()
        val role = row.opt("role") as? String ?: bad()
        if (!validModeratorEmail(email) || !seen.add(email) || role !in roleNames) bad()
        ModeratorRosterItem(email,role)
    }
    return ModeratorRoster(items,truncated)
}

internal class ModeratorRosterAccess(private val current: () -> Boolean, private val read: suspend () -> JSONObject) {
    fun available() = current()
    suspend fun request(): ModeratorRoster {
        if (!available()) throw AdminAccessRejected()
        val result = parseModeratorRoster(read())
        if (!available()) throw AdminAccessRejected()
        return result
    }
}

@Composable
internal fun ModeratorRosterPanel(access: ModeratorRosterAccess, suspension: ModeratorSuspensionAccess? = null,
    onBack: () -> Unit) {
    if (!access.available()) return
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var loaded by remember(access) { mutableStateOf<ModeratorRoster?>(null) }
    var busy by remember(access) { mutableStateOf(false) }
    var failed by remember(access) { mutableStateOf(false) }
    var suspensionResult by remember(access,suspension) { mutableStateOf<String?>(null) }
    var confirmation by remember(access,suspension) { mutableStateOf<ModeratorSuspensionSnapshot?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                TextButton(onClick = onBack) { Text("العودة إلى الإدارة") }
                Text("حسابات الإشراف", style = MaterialTheme.typography.headlineSmall)
                Text(if (suspension?.available() == true) "يمكن إيقاف حساب مشرف قائم بعد التحقّق والتأكيد."
                    else "عرض الحسابات وأدوارها فقط. لا يعدّل هذا العرض الصلاحيات.")
                Button(enabled = !busy,onClick = {
                    busy = true
                    failed = false
                    loaded = null
                    scope.launch {
                        try { loaded = access.request() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "جارٍ التحميل…" else "اعرض الحسابات") }
            }
        }
        suspensionResult?.let { note -> item { Text(note) } }
        if (failed) item { Text("تعذّر عرض الحسابات.") }
        loaded?.let { roster ->
            if (roster.items.isEmpty()) item { Text("لا توجد حسابات للعرض.") }
            if (roster.truncated) item { Text("تعرض القائمة أول ٥٠ حسابًا فقط.") }
            items(roster.items, key = { it.email }) { member ->
                Column(Modifier.padding(vertical=4.dp)) {
                    Text(member.email, style = MaterialTheme.typography.titleSmall)
                    Text(roleNames.getValue(member.role), style = MaterialTheme.typography.bodySmall)
                    if (suspension != null && suspension.available() && member.role in setOf("admin","supervisor")) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            suspensionResult = null
                            scope.launch {
                                try { confirmation = suspension.verify(member) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) {
                                    loaded = null
                                    suspensionResult = "تعذّر التحقّق من حالة الحساب. اعرض الحسابات من جديد."
                                } finally { busy = false }
                            }
                        }) { Text("راجِع إيقاف الحساب") }
                    }
                }
            }
        }
    }
    val snapshot = confirmation
    if (snapshot != null && suspension?.available() == true) {
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text("إيقاف حساب الإشراف") },
            text = { Text("سيُوقف الحساب ${snapshot.member.email} وتُلغى جلساته القائمة. لن يُحذف الحساب.") },
            confirmButton = {
                TextButton(enabled = !busy,onClick = {
                    confirmation = null
                    busy = true
                    scope.launch {
                        try {
                            suspension.suspendVerified(snapshot)
                            suspensionResult = "أُوقف حساب الإشراف."
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            suspensionResult = "لم يؤكَّد الإيقاف. اعرض حالة الحساب من جديد قبل أي محاولة."
                        } finally {
                            loaded = null
                            busy = false
                        }
                    }
                }) { Text("أكّد إيقاف الحساب") }
            }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("رجوع") } })
    }

}
