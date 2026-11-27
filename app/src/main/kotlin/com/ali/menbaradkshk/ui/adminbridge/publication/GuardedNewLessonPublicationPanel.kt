package com.ali.menbaradkshk.ui.adminbridge.publication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminLongPreparationReceipt
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraft
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuardedNewLessonPublicationPanel(
    client: GuardedNewLessonPublicationClient,
    draft: AdminUploadDraft,
    preparation: AdminLongPreparationReceipt,
    onBusyChanged: (Boolean) -> Unit,
) {
    if (!client.eligible(draft,preparation)) return
    val scope = rememberCoroutineScope()
    var review by remember(client,draft,preparation) { mutableStateOf<GuardedNewLessonPublicationClient.Review?>(null) }
    var confirm by remember(client,draft,preparation) { mutableStateOf(false) }
    var receipt by remember(client,draft,preparation) { mutableStateOf<GuardedNewLessonReceipt?>(null) }
    var working by remember(client,draft,preparation) { mutableStateOf(false) }
    var publishUncertain by remember(client,draft,preparation) { mutableStateOf(false) }
    var note by remember(client,draft,preparation) { mutableStateOf<String?>(null) }

    fun busy(engaged: Boolean) { working = engaged; onBusyChanged(engaged) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("نشر التسجيل درسًا عامًا", style = MaterialTheme.typography.titleMedium)
        Text("التجهيز الخاص لا ينشر التسجيل. اطلب التحقق من النسخة النهائية قبل اتخاذ قرار النشر.")
        val ready = review
        if (receipt != null) {
            Text("أُضيف الدرس إلى الدليل العام.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (ready == null) {
            Button(enabled = !working, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                if (working || !client.eligible(draft,preparation)) return@Button
                busy(true)
                note = null
                scope.launch {
                    try {
                        review = client.review(draft,preparation)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        note = if (publishUncertain) "تعذّر التحقق من النشر. راجع الدليل العام قبل أي محاولة أخرى."
                            else "لم تُثبت جاهزية النسخة النهائية لهذه المسودة. لم يبدأ النشر."
                    } finally { busy(false) }
                }
            }) { Text("تحقّق من النسخة النهائية") }
        } else {
            ReviewDetails(ready.details)
            Button(enabled = !working, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                if (working || ready !== review || !client.canConfirm(ready)) {
                    review = null
                    note = "انتهت صلاحية المراجعة. تحقّق من النسخة النهائية من جديد."
                } else confirm = true
            }) { Text("راجع وأكّد نشر الدرس") }
            TextButton(enabled = !working, modifier = Modifier.heightIn(min = 48.dp),
                onClick = { review = null; confirm = false }) { Text("ألغِ المراجعة") }
        }
        if (working) CircularProgressIndicator()
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (publishUncertain && receipt == null)
            Text("لم يصل تأكيد المحاولة السابقة. قد يكون الدرس منشورًا بالفعل؛ راجع الدليل العام قبل محاولة أخرى.")
    }
    val ready = review
    if (confirm && ready != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("تأكيد النشر العام") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سيصبح هذا التسجيل متاحًا للمستمعين في القسم المعروض.")
                ReviewDetails(ready.details)
            }
        },
        confirmButton = {
            TextButton(enabled = !working, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                confirm = false
                if (working || ready !== review || !client.canConfirm(ready)) {
                    review = null
                    note = "لم يبدأ طلب نشر جديد؛ انتهت صلاحية المراجعة."
                    return@TextButton
                }
                review = null
                note = null
                busy(true)
                scope.launch {
                    try {
                        receipt = client.publish(ready)
                        publishUncertain = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { publishUncertain = true }
                    finally { busy(false) }
                }
            }) { Text("أؤكّد النشر للعامة") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
    )
}

@Composable
internal fun ReviewDetails(details: GuardedNewLessonDetails) {
    val decimal = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("ar")) }
    val totalSeconds = (details.decodedDurationMs + 999) / 1000
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("العنوان: ${details.title}")
        Text("القسم: ${details.categoryName} / ${details.sectionName}")
        if (details.speaker.isNotBlank()) Text("المتحدث: ${details.speaker}")
        if (details.description.isNotBlank()) Text("الوصف: ${details.description}")
        Text("المدة: ${decimal.format(totalSeconds / 60)} د ${decimal.format(totalSeconds % 60)} ث")
        Text("حجم نسخة Opus: ${decimal.format(details.sizeBytes)} بايت")
        Text("بصمة الصوت النهائية (SHA-256)")
        Text(details.audioSha256, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}
