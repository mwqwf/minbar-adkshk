package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.util.AtomicFile
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DraftImagePickerOffer(val account: String, val target: DraftImageTarget)
internal data class SelectedDraftImage(val offer: DraftImagePickerOffer, val uri: Uri)

private data class PendingDraftImage(val target: DraftImageTarget, val pixels: OwnedDraftJpeg)

/** Only the fresh JPEG awaiting the exact receipt; no original bytes, URI, token, name or backup. */
private class DraftImageVault(context: Context, account: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "transcript-image-pending-$account.bin"))

    fun read(): PendingDraftImage? = synchronized(lock) { readLocal() }

    fun begin(target: DraftImageTarget, image: OwnedDraftJpeg) = synchronized(lock) {
        val existing = readLocal()
        if (existing != null) {
            check(existing.target == target && existing.pixels.digest == image.digest)
        } else {
            val stream = file.startWrite()
            try {
                DataOutputStream(stream).apply {
                    writeUTF(target.lessonId); writeUTF(target.operationId)
                    writeInt(image.size); write(image.copyBytes()); flush()
                }
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            val saved = requireNotNull(readLocal())
            check(saved.target == target && saved.pixels.digest == image.digest)
        }
    }

    fun clear(target: DraftImageTarget, image: OwnedDraftJpeg) = synchronized(lock) {
        val existing = requireNotNull(readLocal())
        check(existing.target == target && existing.pixels.digest == image.digest)
        file.delete()
        check(readLocal() == null)
    }

    private fun readLocal(): PendingDraftImage? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return DataInputStream(file.openRead()).use {
            val target = DraftImageTarget(it.readUTF(), it.readUTF())
            val bytes = ByteArray(it.readInt().also { n -> require(n in 1..MAX_DRAFT_JPEG_BYTES) })
            it.readFully(bytes)
            require(it.read() == -1)
            PendingDraftImage(target, OwnedDraftJpeg(bytes))
        }
    }

    companion object { private val lock = Any() }
}

@Composable
internal fun TranscriptDraftImagePanel(
    account: String,
    client: TranscriptDraftImageClient,
    drafts: TranscriptDraftPending,
    selected: SelectedDraftImage?,
    choose: (DraftImagePickerOffer) -> Unit,
    consumed: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val vault = remember(account) {
        require(Regex("[a-f0-9]{64}").matches(account))
        DraftImageVault(context, account)
    }
    var target by remember(client) { mutableStateOf<DraftImageTarget?>(null) }
    var pending by remember(client) { mutableStateOf<PendingDraftImage?>(null) }
    var prepared by remember(client) { mutableStateOf<OwnedDraftJpeg?>(null) }
    var remote by remember(client) { mutableStateOf<OwnedDraftJpeg?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    var loaded by remember(client) { mutableStateOf(false) }
    var confirm by remember(client) { mutableStateOf(false) }
    var drop by remember(client) { mutableStateOf(false) }
    var message by remember(client) { mutableStateOf<String?>(null) }

    fun action(failure: String, block: suspend () -> Unit) {
        if (busy || !client.available()) return
        busy = true
        message = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = failure }
            finally { busy = false }
        }
    }

    LaunchedEffect(client, account) {
        try {
            val (latest, unfinished) = withContext(Dispatchers.IO) { drafts.load().second to vault.read() }
            if (!client.available()) return@LaunchedEffect
            pending = unfinished
            target = unfinished?.target ?: latest?.let { DraftImageTarget(it.lessonId, it.operationId) }
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "تعذّر فتح حالة الصورة المحلية." }
    }
    BackHandler { if (!busy) onBack() }
    if (!client.available()) return
    val current = target
    val local = pending?.pixels ?: prepared
    val preview = local ?: remote
    val rendered by produceState<Pair<OwnedDraftJpeg, androidx.compose.ui.graphics.ImageBitmap>?>(null, preview, client) {
        value = withContext(Dispatchers.IO) {
            if (!client.available() || preview == null) null
            else preview.copyBytes().let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { preview to it }
            }
        }
    }
    val bitmap = rendered?.takeIf { it.first === preview }?.second
    val input = selected?.takeIf { it.offer.account == account && it.offer.target == current }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("صورة مسودة النص الخاصة", style = MaterialTheme.typography.headlineSmall)
        Text("تخص آخر مسودة نص مؤكدة على هذا الجهاز، ولا تظهر للعموم. لا نرسل الصورة الأصلية ولا اسم الملف.")
        if (loaded && current == null) Text("احفظ مسودة نص خاصة أولًا.")
        if (current != null) {
            Text("الدرس: ${current.lessonId}")
            if (bitmap != null) Image(bitmap, contentDescription = "معاينة صورة المسودة", modifier = Modifier.heightIn(max = 320.dp))
            if (pending == null && !busy) {
                TextButton(onClick = { choose(DraftImagePickerOffer(account, current)) }) { Text("اختر صورة محلية") }
            }
            if (input != null && pending == null && !busy) {
                Button(onClick = {
                    val uri = input.uri
                    consumed()
                    remote = null
                    prepared = null
                    action("تعذّر تجهيز صورة خاصة ضمن الحد. لم تُرسل الصورة.") {
                        prepared = withContext(Dispatchers.IO) {
                            fun checkScope() {
                                coroutineContext.ensureActive()
                                check(client.available() && uri.scheme == "content" && uri.isHierarchical &&
                                    uri.authority?.let { it.length in 1..253 && it.all { c -> c.isLetterOrDigit() || c in "._-" } &&
                                        !it.equals(context.packageName, ignoreCase = true) &&
                                        !it.startsWith(context.packageName + ".", ignoreCase = true) } == true &&
                                    context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
                            }
                            prepareDraftImagePixels({ context.contentResolver.openInputStream(uri) ?: throw IOException() }, ::checkScope)
                        }
                        message = "جُهّزت نسخة JPEG خاصة بلا بيانات الملف الأصلي. لم تُرسل بعد."
                    }
                }) { Text("حضّر نسخة JPEG للمعاينة") }
            }
            if (local != null && bitmap != null && !busy) Button(onClick = { confirm = true }) {
                Text(if (pending == null) "مراجعة حفظ الصورة الخاصة" else "مراجعة إعادة حفظ نفس الصورة")
            }
            TextButton(enabled = !busy, onClick = {
                action("لم نتلقّ صورة خاصة مؤكدة. لم نغيّر شيئًا.") {
                    val received = client.read(current)
                    val awaiting = pending
                    if (awaiting != null) {
                        check(awaiting.target == current && awaiting.pixels.digest == received.digest)
                        withContext(Dispatchers.IO) { vault.clear(current, awaiting.pixels) }
                        pending = null
                        prepared = null
                    }
                    remote = received
                    message = "تأكدت معاينة الصورة الخاصة دون إعادة إرسالها."
                }
            }) { Text("تحقّق من الصورة الخاصة") }
            if (pending != null && !busy) TextButton(onClick = { drop = true }) { Text("اترك المحاولة على هذا الجهاز") }
        }
        message?.let { Text(it) }
        TextButton(onClick = onBack, enabled = !busy) { Text("رجوع") }
    }
    if (confirm && current != null && local != null && bitmap != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("احفظ الصورة مع المسودة الخاصة؟") },
        text = { Text("ستُحفظ الصورة المعروضة وحدها في مسودة هذا الدرس. لن تُنشر.") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
        confirmButton = {
            Button(onClick = {
                confirm = false
                action("لم نتلقّ تأكيدًا. تحقّق من الصورة أو أعِد محاولة حفظ النسخة نفسها فقط.") {
                    withContext(Dispatchers.IO) { vault.begin(current, local) }
                    pending = PendingDraftImage(current, local)
                    client.save(current, local)
                    withContext(Dispatchers.IO) { vault.clear(current, local) }
                    pending = null
                    prepared = null
                    remote = local
                    message = "تأكّد حفظ الصورة الخاصة. لم تُنشر."
                }
            }) { Text("احفظ الصورة الخاصة") }
        },
    )
    if (drop && current != null && local != null && pending != null) AlertDialog(
        onDismissRequest = { drop = false },
        title = { Text("اترك المحاولة على هذا الجهاز؟") },
        text = { Text("سيُحذف سجل المحاولة المحلية فقط. قد تبقى الصورة محفوظة في المسودة الخاصة على الخادم.") },
        dismissButton = { TextButton(onClick = { drop = false }) { Text("إلغاء") } },
        confirmButton = {
            TextButton(onClick = {
                drop = false
                action("تعذّر تأكيد إزالة المحاولة المحلية.") {
                    withContext(Dispatchers.IO) { vault.clear(current, local) }
                    pending = null
                    prepared = null
                    remote = null
                    message = "أُزيل سجل المحاولة المحلية فقط."
                }
            }) { Text("أزل السجل المحلي") }
        },
    )
}
