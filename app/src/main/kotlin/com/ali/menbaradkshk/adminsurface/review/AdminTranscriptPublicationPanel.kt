package com.ali.menbaradkshk.adminsurface.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AdminTranscriptPublicationPanel(client: AdminTranscriptPublication, onBack: () -> Unit) {
    if (!client.available()) return
    val scope = rememberCoroutineScope()
    var queue by remember(client) { mutableStateOf<TranscriptPublicationQueue?>(null) }
    var detail by remember(client) { mutableStateOf<TranscriptPublicationDetail?>(null) }
    var confirmation by remember(client) { mutableStateOf<PreparedTranscriptPublication?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    var message by remember(client) { mutableStateOf("") }
    DisposableEffect(client) { onDispose { client.discard() } }

    fun discardTicket() { confirmation = null; client.discard() }
    fun back() {
        if (busy) return
        discardTicket()
        if (detail == null) onBack() else { detail = null; message = "" }
    }
    fun perform(onFailure: () -> Unit, action: suspend () -> Unit) {
        if (busy || !client.available()) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailure()
            } finally {
                busy = false
            }
        }
    }
    BackHandler { back() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("نشر النصوص المعتمدة", style = MaterialTheme.typography.headlineSmall)
            Text("اعتماد النص يحفظه داخليًا فقط. النشر إجراء مستقل يجعل النص ظاهرًا للمستمعين.")
            TextButton(onClick = { back() }, enabled = !busy) { Text("رجوع") }
            if (busy) CircularProgressIndicator()
            if (message.isNotEmpty()) Text(message)
        }
        val visible = detail
        if (visible == null) {
            item {
                TextButton(enabled = !busy, onClick = {
                    perform(onFailure = { message = "تعذّر جلب المعتمَد للنشر." }) {
                        discardTicket(); queue = null; message = ""
                        queue = client.queue()
                    }
                }) { Text("اعرض النصوص المعتمدة") }
                if (queue?.truncated == true) Text("تُعرض أحدث ٢٠ مساهمة مؤهلة فقط.")
                if (queue?.items?.isEmpty() == true) Text("لا توجد نصوص معتمدة بانتظار النشر.")
            }
            items(queue?.items.orEmpty(), key = { it.id }) { item ->
                TextButton(enabled = !busy, onClick = {
                    perform(onFailure = { message = "تعذّر فتح النص. حدّث القائمة." }) {
                        discardTicket(); message = ""
                        detail = client.detail(item)
                    }
                }) { Text(item.title) }
            }
        } else {
            item { Text(visible.item.title, style = MaterialTheme.typography.titleMedium) }
            item { Text(visible.text) }
            item {
                TextButton(enabled = !busy, onClick = {
                    perform(onFailure = { message = "النص تغيّر أو الدرس غير مؤهل للنشر الآن." }) {
                        message = ""
                        val ticket = client.prepare(visible)
                        if (detail === visible && client.available()) confirmation = ticket else discardTicket()
                    }
                }) { Text("راجع تأكيد النشر العام") }
            }
        }
    }
    confirmation?.let { ticket ->
        AlertDialog(
            onDismissRequest = { discardTicket() },
            title = { Text("نشر النص للمستمعين؟") },
            text = { Text("سيُنشر النص الذي راجعته لدرس «${ticket.visible.item.title}» (${ticket.visible.item.lesson}). هذا ليس حفظًا خاصًا.") },
            dismissButton = { TextButton(onClick = { discardTicket() }) { Text("إلغاء") } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (confirmation !== ticket || detail !== ticket.visible || busy) return@TextButton
                    confirmation = null
                    perform(onFailure = {
                        detail = null; queue = null
                        message = "لم يتأكد النشر. حدّث القائمة وتحقّق من الدرس قبل أي محاولة أخرى."
                    }) {
                        client.publish(ticket)
                        detail = null; queue = null
                        message = "أكّدت الخدمة نشر النص للدرس المحدد."
                    }
                }) { Text("انشر هذا النص") }
            },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
}
