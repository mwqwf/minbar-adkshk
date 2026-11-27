package com.ali.menbaradkshk.adminsurface.support

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

@Composable
internal fun GuardedSupportInboxPanel(access: GuardedSupportInboxAccess, replyClient: GuardedSupportReplyClient? = null,
                                     onBack: () -> Unit) {
    var selected by remember(access) { mutableStateOf<SupportInboxThread?>(null) }
    val thread = selected
    var retry by remember(access, thread) { mutableStateOf(0) }
    var inbox by remember(access, thread, retry) { mutableStateOf<SupportInboxPage<SupportInboxThread>?>(null) }
    var messages by remember(access, thread, retry) { mutableStateOf<SupportInboxPage<SupportInboxMessage>?>(null) }
    var failed by remember(access, thread, retry) { mutableStateOf(false) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("ar")) }
    var replyOpen by remember(access, thread, retry) { mutableStateOf(false) }
    val back: () -> Unit = { if (selected != null) selected = null else onBack() }
    LaunchedEffect(access, thread, retry) {
        if (!access.available()) return@LaunchedEffect
        try {
            if (thread == null) inbox = access.threads() else messages = access.messages(thread.id)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            if (access.available()) failed = true
        }
    }

    if (!access.available()) return
    val rendered = messages
    if (replyClient != null && thread != null && rendered != null && replyClient.available(thread,rendered) && replyOpen) {
        GuardedSupportReplyPanel(replyClient,thread,rendered,supportKindLabel(thread.kind),
            onBack={replyOpen=false},onReload={replyOpen=false;selected=null})
        return
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (thread == null) "رجوع إلى الإدارة" else "رجوع إلى صندوق الدعم")
        }
        Text(if (thread == null) "صندوق دعم منبر" else supportKindLabel(thread.kind),
            style = MaterialTheme.typography.headlineSmall)
        Text(if (replyClient == null) "قراءة فقط. لا تتغيّر حالة الرسائل، ولا تُعرض المرفقات هنا."
            else "عرض الرسائل لا يغيّر حالتها. لا تُعرض المرفقات هنا.")
        val inboxPage = inbox
        val messagePage = messages
        when {
            failed -> {
                Text("تعذّر تحميل صندوق الدعم.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("إعادة المحاولة") }
            }
            inboxPage == null && messagePage == null ->
                Text("جارٍ التحميل…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else -> {
                TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("تحديث العرض") }
                if (replyClient != null && thread != null && messagePage != null && replyClient.available(thread,messagePage)) {
                    TextButton(onClick={if(replyClient.available(thread,messagePage))replyOpen=true},
                        modifier=Modifier.heightIn(min=48.dp)) { Text("اكتب ردًا لهذه المحادثة") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (inboxPage != null) {
                        if (inboxPage.items.isEmpty()) item { Text("لا توجد محادثات ضمن هذا العرض.") }
                        if (inboxPage.truncated) item { Text("يعرض هذا الصندوق أحدث خمسين محادثة.") }
                        items(inboxPage.items, key = { it.id }) { t ->
                            TextButton(onClick = { if (access.available()) selected = t },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(supportKindLabel(t.kind), style = MaterialTheme.typography.titleMedium)
                                    Text((if (t.closed) "مغلقة" else "مفتوحة") +
                                        (if (t.unread) " · غير مقروءة" else "") + " · الرسائل: ${t.messageCount}")
                                    if (t.lastMessageAtMs > 0) Text(date.format(Date(t.lastMessageAtMs)))
                                }
                            }
                        }
                    }
                    if (messagePage != null) {
                        if (messagePage.items.isEmpty()) item { Text("لا توجد رسائل ضمن هذا العرض.") }
                        if (messagePage.truncated) item { Text("تظهر أحدث مئة رسالة في هذه المحادثة.") }
                        items(messagePage.items) { m ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (m.fromOwner) "المالك" else "المستمع", style = MaterialTheme.typography.titleMedium)
                                    if (m.createdAtMs > 0) Text(date.format(Date(m.createdAtMs)))
                                    if (m.text.isNotBlank()) Text(m.text)
                                    if (m.hasAudio) Text("مرفق صوتي غير معروض")
                                    if (m.hasImages) Text("صور مرفقة غير معروضة")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun supportKindLabel(kind: String) = when (kind) {
    "suggestion" -> "اقتراح"
    "bug" -> "بلاغ عطل"
    "lesson_help" -> "سؤال عن درس"
    "idea" -> "فكرة"
    "supervision" -> "طلب إشراف"
    else -> "محادثة دعم"
}
