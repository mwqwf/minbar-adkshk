package com.ali.menbaradkshk.adminsurface.announcements

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val audience = "الجمهور: جميع مستمعي منبر. يظهر الإعلان في التطبيق عند تحديث الإشعارات، وقد يعرضه الهاتف كإشعار."

@Composable
internal fun GeneralAnnouncementPanel(
    client: GeneralNoticeClient,
    store: GeneralNoticePending,
    onBack: () -> Unit,
) {
    var title by remember(client) { mutableStateOf("") }
    var body by remember(client) { mutableStateOf("") }
    var review by remember(client) { mutableStateOf<GeneralNoticeReview?>(null) }
    var pending by remember(client) { mutableStateOf<GeneralNoticeIntent?>(null) }
    var status by remember(client) { mutableStateOf<String?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    var loaded by remember(client) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client, store) {
        try {
            pending = withContext(Dispatchers.IO) { store.read() }
            pending?.let { title = it.title; body = it.body }
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "تعذّرت قراءة حالة الإعلان السابق. لم يُنشر شيء من هذه الشاشة."
        }
    }
    BackHandler { if (!busy) onBack() }
    if (!client.available()) return
    val exactTitle = cleanGeneralNoticeText(title, GENERAL_NOTICE_TITLE_MAX, false)
    val exactBody = cleanGeneralNoticeText(body, GENERAL_NOTICE_BODY_MAX, true)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("رجوع") }
        Text("إعلان عام", style = MaterialTheme.typography.headlineSmall)
        Text(audience)
        if (pending != null) Text("لم نتحقق من نتيجة المحاولة السابقة. راجع الإعلان نفسه دون تغيير؛ إعادة التأكيد لا تنشئ إعلانًا ثانيًا.")
        OutlinedTextField(
            value = title, onValueChange = { if (it.length <= GENERAL_NOTICE_TITLE_MAX) title = it },
            enabled = loaded && !busy && review == null && pending == null,
            singleLine = true, label = { Text("العنوان") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body, onValueChange = { if (it.length <= GENERAL_NOTICE_BODY_MAX) body = it },
            enabled = loaded && !busy && review == null && pending == null,
            minLines = 3, label = { Text("نص الإعلان") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = loaded && !busy && review == null && exactTitle != null && exactBody != null,
            onClick = {
                if (busy || !loaded || review != null || exactTitle == null || exactBody == null) return@Button
                val intent = pending ?: GeneralNoticeIntent(UUID.randomUUID().toString(), exactTitle, exactBody)
                busy = true
                status = null
                scope.launch {
                    try {
                        review = client.review(intent)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        status = "تعذّرت مراجعة الإعلان. لم تنشره هذه الخطوة."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("مراجعة قبل النشر") }
        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
    val approvedPreview = review ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) review = null },
        title = { Text("تأكيد نشر إعلان عام") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(approvedPreview.intent.title, style = MaterialTheme.typography.titleMedium)
                Text(approvedPreview.intent.body)
                Text(audience)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    if (busy || review !== approvedPreview || !client.available()) return@Button
                    review = null
                    busy = true
                    status = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.begin(approvedPreview.intent) }
                            pending = approvedPreview.intent
                            client.confirm(approvedPreview)
                            status = "تأكّد نشر الإعلان في سجل الإشعارات."
                            withContext(Dispatchers.IO) { store.clear(approvedPreview.intent) }
                            pending = null
                            title = ""
                            body = ""
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            if (status == null) {
                                status = if (pending == null) "تعذّر حفظ حالة المحاولة محليًا؛ لم يُرسل طلب النشر."
                                else "لم نتلقّ تأكيدًا بالنشر. راجع الإعلان نفسه قبل إعادة التأكيد."
                            } else {
                                status = "تأكّد النشر؛ تعذّر تحديث الحالة على الجهاز. لا تنشئ نسخة جديدة."
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("انشر للجميع") }
        },
        dismissButton = { TextButton(onClick = { review = null }, enabled = !busy) { Text("إلغاء") } },
    )
}
