package com.ali.menbaradkshk.ui.adminbridge.publication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuardedNewLessonDiscoveryPanel(client: GuardedNewLessonDiscoveryClient, onBack: () -> Unit) {
    if (!client.available()) return
    val scope = rememberCoroutineScope()
    var page by remember(client) { mutableStateOf<GuardedNewLessonDiscoveryClient.Candidates?>(null) }
    var reviewed by remember(client) { mutableStateOf<GuardedNewLessonDiscoveryClient.Review?>(null) }
    var confirm by remember(client) { mutableStateOf(false) }
    var working by remember(client) { mutableStateOf(false) }
    var note by remember(client) { mutableStateOf<String?>(null) }
    var uncertain by remember(client) { mutableStateOf(false) }
    fun back() {
        if (working) return
        if (reviewed != null) { reviewed = null; confirm = false } else onBack()
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = ::back, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) { Text("رجوع") }
        Text("تسجيلات بانتظار مراجعة النشر", style = MaterialTheme.typography.headlineSmall)
        Text("هذه قائمة تسجيلات أُعدّ لها ملف صوتي. لا تعني أنها جاهزة للنشر قبل التحقق من كل تسجيل.")
        val ready = reviewed
        val visible = page
        if (ready == null) {
            Button(enabled = !working, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                if (working || !client.available()) return@Button
                page = null
                note = null
                working = true
                scope.launch {
                    try { page = client.list() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { note = "تعذّر جلب التسجيلات المتاحة للمراجعة." }
                    finally { working = false }
                }
            }) { Text(if (visible == null) "اعرض التسجيلات المتاحة" else "حدّث التسجيلات المتاحة") }
        }
        if (working) CircularProgressIndicator()
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (uncertain) Text("لم يصل تأكيد النشر في المحاولة السابقة. راجع الدليل العام قبل محاولة أخرى.")
        if (ready != null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewDetails(ready.details)
                Button(enabled = !working, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    if (client.canConfirm(ready) && reviewed === ready) confirm = true
                    else { reviewed = null; note = "انتهت صلاحية التحقق. راجع هذا التسجيل من جديد." }
                }) { Text("راجع وأكّد نشر الدرس") }
                TextButton(enabled = !working, modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { reviewed = null }) { Text("ألغِ المراجعة") }
            }
        } else if (visible != null) {
            if (visible.truncated) Text("هذه أحدث النتائج التي أمكن عرضها، وليست قائمة كاملة.")
            if (visible.rows.isEmpty()) Text("لا توجد تسجيلات حديثة بانتظار المراجعة في هذه القائمة.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible.rows, key = { it.id }) { item ->
                    TextButton(enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                        if (working || !client.available() || page !== visible) return@TextButton
                        working = true
                        note = null
                        scope.launch {
                            try { reviewed = client.review(item) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { note = "لم يثبت أن التسجيل صالح للنشر الآن. حدّث القائمة وأعد المراجعة." }
                            finally { working = false }
                        }
                    }) { Text(item.title) }
                }
            }
        }
    }
    val selected = reviewed
    if (confirm && selected != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("تأكيد النشر العام") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("سيصبح هذا التسجيل متاحًا للمستمعين في القسم المعروض.")
                ReviewDetails(selected.details)
            }
        },
        confirmButton = {
            TextButton(enabled = !working, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                confirm = false
                if (working || reviewed !== selected || !client.canConfirm(selected)) {
                    reviewed = null
                    note = "لم يبدأ طلب نشر جديد. انتهت صلاحية المراجعة."
                    return@TextButton
                }
                reviewed = null
                note = null
                working = true
                scope.launch {
                    try {
                        client.publish(selected)
                        page = null
                        note = "أُضيف الدرس إلى الدليل العام."
                        uncertain = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        uncertain = true
                        page = null
                    } finally { working = false }
                }
            }) { Text("أؤكّد النشر للعامة") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
    )
}
