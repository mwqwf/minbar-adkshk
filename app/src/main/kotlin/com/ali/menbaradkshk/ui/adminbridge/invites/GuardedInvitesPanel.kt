package com.ali.menbaradkshk.ui.adminbridge.invites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuardedInvitesPanel(client: GuardedInviteInventory, onBack: () -> Unit) {
    if (!client.available()) return
    val scope = rememberCoroutineScope()
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.MEDIUM,Locale.forLanguageTag("ar")) }
    var page by remember(client) { mutableStateOf<GuardedInviteInventory.Inventory?>(null) }
    var verify by remember(client) { mutableStateOf<GuardedInviteInventory.OpenInvite?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    var note by remember(client) { mutableStateOf<String?>(null) }
    BackHandler { if (!busy) onBack() }
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("رجوع") }
        Text("الدعوات غير المستخدمة", style = MaterialTheme.typography.headlineSmall)
        Text("تظهر الدعوات السارية التي أصدرتها. لا تُعرض رموزها، وإلغاء الدعوة لا يمس الحسابات أو الجلسات القائمة.")
        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
            if (busy || !client.available()) return@Button
            page = null
            verify = null
            busy = true
            note = null
            scope.launch {
                try { page = client.list() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { note = "تعذّر تحميل الدعوات السارية." }
                finally { busy = false }
            }
        }) { Text(if (page == null) "اعرض الدعوات" else "حدّث الدعوات") }
        if (busy) CircularProgressIndicator()
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        page?.let { listed ->
            if (listed.truncated) Text("تظهر أحدث ثلاثين دعوة فقط.")
            if (listed.items.isEmpty()) Text("لا توجد دعوات سارية غير مستخدمة.")
            LazyColumn(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listed.items) { item ->
                    val ambiguous = listed.items.count { it.email == item.email && it.role == item.role &&
                        format.format(Date(it.createdAtMs)) == format.format(Date(item.createdAtMs)) &&
                        format.format(Date(it.expiresAtMs)) == format.format(Date(item.expiresAtMs)) } > 1
                    OutlinedCard {
                        Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            InviteSummary(item,format)
                            if (ambiguous) Text("توجد دعوات أخرى بالتفاصيل المعروضة نفسها. لا يمكن اختيار واحدة بأمان هنا.")
                            else TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),onClick = {
                                if (!busy && page === listed && client.canCancel(item)) verify = item
                                else note = "انتهت صلاحية التأكيد. حدّث الدعوات أولًا."
                            }) { Text("راجع إلغاء الدعوة") }
                        }
                    }
                }
            }
        }
    }
    val item = verify
    if (item != null) AlertDialog(
        onDismissRequest = { if (!busy) verify = null },
        title = { Text("تأكيد إلغاء الدعوة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InviteSummary(item,format)
                Text("لن يعمل هذا الرمز بعد إلغائه. لن تتغيّر عضوية أي حساب أو جلسته.")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),onClick = {
                if (busy || verify !== item || !client.canCancel(item)) {
                    verify = null
                    note = "انتهت صلاحية التأكيد. حدّث الدعوات أولًا."
                    return@TextButton
                }
                verify = null
                busy = true
                note = null
                scope.launch {
                    try {
                        client.cancel(item)
                        note = "أُلغيت الدعوة غير المستخدمة."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { note = "لم يتأكد الإلغاء. قد تكون الدعوة استُخدمت أو أُلغيت بالفعل. حدّث القائمة." }
                    finally { page = null; busy = false }
                }
            }) { Text("أؤكّد إلغاء الدعوة") }
        },
        dismissButton = { TextButton(onClick = { verify = null }) { Text("رجوع") } },
    )
}

@Composable
private fun InviteSummary(item: GuardedInviteInventory.OpenInvite,format:DateFormat) {
    Text(item.email, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr))
    Text(if (item.role == "admin") "الصلاحية: إداري" else "الصلاحية: مشرف")
    Text("صدرت: ${format.format(Date(item.createdAtMs))}")
    Text("تنتهي: ${format.format(Date(item.expiresAtMs))}")
}
