package com.ali.menbaradkshk.adminsurface.support

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuardedSupportReplyPanel(
    client:GuardedSupportReplyClient,
    thread:SupportInboxThread,
    messages:SupportInboxPage<SupportInboxMessage>,
    category:String,
    onBack:()->Unit,
    onReload:()->Unit,
) {
    if (!client.available(thread,messages))return
    val scope=rememberCoroutineScope()
    var draft by remember(client,thread,messages){ mutableStateOf("") }
    var review by remember(client,thread,messages){ mutableStateOf<GuardedSupportReplyClient.Review?>(null) }
    var busy by remember(client,thread,messages){ mutableStateOf(false) }
    var attempted by remember(client,thread,messages){ mutableStateOf(false) }
    var terminal by remember(client,thread,messages){ mutableStateOf<String?>(null) }
    val format=remember{DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.MEDIUM,Locale.forLanguageTag("ar"))}
    val date=remember(thread.lastMessageAtMs){format.format(Date(thread.lastMessageAtMs))}
    val last=messages.items.lastOrNull()?.text?.take(240)?.takeIf(String::isNotBlank)
    val text=exactSupportReplyText(draft)
    val goBack: () -> Unit = { if (attempted) onReload() else onBack() }
    BackHandler { if(!busy) goBack() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("رد في محادثة دعم منبر",style=MaterialTheme.typography.headlineSmall)
        Text(category)
        Text("آخر رسالة: $date")
        last?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
        Text("سيظهر الرد في هذه المحادثة عند فتح التطبيق أو تحديث الرسائل. لا يُرسل بريد أو إشعار خارجي.")
        if (!attempted) {
            OutlinedTextField(value=draft,onValueChange={ draft=it.take(2000) }, modifier=Modifier.fillMaxWidth(),
                minLines=3, maxLines=8, enabled=!busy, label={Text("نص الرد")})
            Button(enabled=!busy&&text!=null,modifier=Modifier.heightIn(min=48.dp),onClick={
                if (busy || text==null || !client.available(thread,messages))return@Button
                terminal=null
                busy=true
                scope.launch {
                    try { review=client.review(thread,messages,text) }
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){ terminal="لم يثبت أن المحادثة ما زالت مطابقة للمراجعة. حدّث صندوق الدعم قبل متابعة الرد." }
                    finally{busy=false}
                }
            }) { Text("راجع نص الرد") }
        }
        if (busy) CircularProgressIndicator()
        terminal?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
        if (terminal!=null) TextButton(enabled=!busy,modifier=Modifier.heightIn(min=48.dp),onClick=onReload) {
            Text("ارجع إلى صندوق الدعم لتحديث المحادثة")
        }
        TextButton(enabled=!busy,modifier=Modifier.heightIn(min=48.dp),onClick=goBack) {
            Text(if(attempted) "رجوع إلى صندوق الدعم" else "رجوع إلى المحادثة")
        }
    }
    val exact=review
    if(exact!=null)AlertDialog(
        onDismissRequest={review=null},
        title={Text("تأكيد إرسال الرد في المحادثة")},
        text={
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("$category · آخر رسالة: $date")
                Text(exact.text)
                Text("سيضاف هذا النص إلى هذه المحادثة وحدها.")
            }
        },
        confirmButton={
            TextButton(enabled=!busy,modifier=Modifier.heightIn(min=48.dp),onClick={
                if(busy||review!==exact||!client.canConfirm(exact)){
                    review=null
                    terminal="انتهت صلاحية المراجعة. لم يبدأ طلب إرسال جديد."
                    return@TextButton
                }
                review=null
                attempted=true
                busy=true
                terminal=null
                scope.launch {
                    try { client.confirm(exact); terminal="أضيف الرد إلى المحادثة." }
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){terminal="لم يصل تأكيد الرد. تحقق من المحادثة قبل أي محاولة أخرى."}
                    finally{busy=false}
                }
            }){Text("أؤكّد إرسال هذا الرد")}
        },
        dismissButton={TextButton(onClick={review=null}){Text("إلغاء")}},
    )
}
