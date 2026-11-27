package com.ali.menbaradkshk.ui.adminbridge.transcriptdraft

import android.content.Context
import android.util.AtomicFile
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.ali.menbaradkshk.adminsurface.LessonMetadata
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Exactly one unresolved POST per owner, not a transcript library or a persisted session. */
internal class TranscriptDraftPending(context: Context, account: String) {
    private val pending: AtomicFile
    private val latest: AtomicFile
    private val archived: AtomicFile

    init {
        require(Regex("[a-f0-9]{64}").matches(account))
        val root = context.applicationContext.noBackupFilesDir
        pending = AtomicFile(File(root, "transcript-draft-pending-$account.json"))
        latest = AtomicFile(File(root, "transcript-draft-latest-$account.json"))
        archived = AtomicFile(File(root, "transcript-draft-locally-archived-$account.json"))
    }

    fun load(): Triple<GuardedTranscriptDraftIntent?, GuardedTranscriptDraftIntent?, GuardedTranscriptDraftIntent?> =
        synchronized(lock) { Triple(readLocked(pending), readLocked(latest), readLocked(archived)) }

    fun begin(intent: GuardedTranscriptDraftIntent) = synchronized(lock) {
        val previous = readLocked(pending)
        check(previous == null || previous == intent)
        if (previous == null) writeLocked(pending, intent)
    }

    fun complete(intent: GuardedTranscriptDraftIntent) = synchronized(lock) {
        check(readLocked(pending) == intent)
        // Keep one local reference to a verified receipt; GET is operation-scoped
        // and the owner would otherwise lose the lookup ID.
        writeLocked(latest, intent)
        pending.delete()
        check(readLocked(pending) == null)
    }

    fun archiveLocally(intent: GuardedTranscriptDraftIntent, progress: TranscriptDraftProgress) = synchronized(lock) {
        check(progress.mayArchiveLocally && readLocked(pending) == intent)
        writeLocked(archived, intent)
        pending.delete()
        check(readLocked(pending) == null)
    }

    private fun writeLocked(file: AtomicFile, intent: GuardedTranscriptDraftIntent) {
        val record = JSONObject()
            .put("lessonId", intent.lessonId).put("op", intent.operationId)
            .put("revision", intent.lessonUpdatedAtMs).put("text", intent.text)
            .toString().encodeToByteArray()
        require(record.size <= TRANSCRIPT_DRAFT_MAX_RESPONSE_BYTES)
        val stream = file.startWrite()
        try {
            stream.write(record)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        check(readLocked(file) == intent)
    }

    private fun readLocked(file: AtomicFile): GuardedTranscriptDraftIntent? {
        if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return null
        val record = file.openRead().use {
            require(it.channel.size() in 1..TRANSCRIPT_DRAFT_MAX_RESPONSE_BYTES.toLong())
            JSONObject(it.readBytes().decodeToString())
        }
        require(record.keys().asSequence().toSet() == setOf("lessonId", "op", "revision", "text"))
        val revision = when (val v = record.opt("revision")) {
            is Int -> v.toLong()
            is Long -> v
            else -> error("Invalid local revision")
        }
        return GuardedTranscriptDraftIntent(record.getString("lessonId"), record.getString("op"), revision, record.getString("text"))
    }

    private companion object { val lock = Any() }
}

@Composable
internal fun TranscriptDraftPanel(
    original: LessonMetadata?,
    client: GuardedTranscriptDraftClient,
    store: TranscriptDraftPending,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember(client, original) { mutableStateOf<GuardedTranscriptDraftIntent?>(null) }
    var latest by remember(client, original) { mutableStateOf<GuardedTranscriptDraftIntent?>(null) }
    var showLatest by remember(client, original) { mutableStateOf(false) }
    var archived by remember(client, original) { mutableStateOf<GuardedTranscriptDraftIntent?>(null) }
    var showArchived by remember(client, original) { mutableStateOf(false) }
    var progress by remember(client, original) { mutableStateOf(TranscriptDraftProgress()) }
    var archiveReview by remember(client, original) { mutableStateOf(false) }
    var text by remember(client, original) { mutableStateOf("") }
    var loaded by remember(client, original) { mutableStateOf(false) }
    var busy by remember(client, original) { mutableStateOf(false) }
    var confirmed by remember(client, original) { mutableStateOf(false) }
    var notice by remember(client, original) { mutableStateOf<String?>(null) }

    LaunchedEffect(client, original, store) {
        try {
            val (unfinished, lastSaved, localArchive) = withContext(Dispatchers.IO) { store.load() }
            pending = unfinished
            archived = localArchive
            latest = lastSaved?.takeIf { it.lessonId == original?.id }
            pending?.let { text = it.text }
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice = "تعذّرت قراءة حالة المسودة المحلية. لم نرسل طلبًا."
        }
    }

    val sameLatest = original != null && matchesLatestTranscriptDraft(latest, original.id, original.updatedAtMs, text)
    fun act(recover: Boolean) {
        if (!loaded || busy || confirmed || !client.available()) return
        val intent = pending ?: if (original != null && !recover && isPassiveDraftText(text) && !sameLatest) {
            GuardedTranscriptDraftIntent(original.id, UUID.randomUUID().toString(), original.updatedAtMs, text)
        } else return
        busy = true
        notice = null
        scope.launch {
            try {
                if (recover) {
                    client.recover(intent)
                } else {
                    withContext(Dispatchers.IO) { store.begin(intent) }
                    pending = intent
                    client.save(intent)
                }
                confirmed = true
                notice = "تأكّد حفظ المسودة الخاصة. لم يُنشر النص ولم يتغيّر نص الدرس."
                withContext(Dispatchers.IO) { store.complete(intent) }
                pending = null
                latest = intent.takeIf { it.lessonId == original?.id }
                progress = TranscriptDraftProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (_: TranscriptDraftConflict) {
                progress = progress.conflict()
                notice = "رفض الخادم الحفظ لهذه المحاولة. تحقّق من حالة المحاولة نفسها دون إعادة إرسالها."
            } catch (_: TranscriptDraftNotFound) {
                progress = progress.missing()
                notice = if (progress.mayArchiveLocally) {
                    "لم نتلقّ تأكيدًا بالحفظ. يمكنك أرشفة النص محليًا وفتح محاولة أخرى؛ لا يعني ذلك أن الخادم لا يحتفظ بنسخة."
                } else {
                    "لم يتأكد وجود المسودة. هذا الرد لا يميّز بين غياب المسودة وعدم إتاحة التحقق."
                }
            } catch (_: Exception) {
                progress = progress.unverified()
                notice = when {
                    confirmed -> "تأكّد الحفظ، لكن تعذّر إزالة سجل المحاولة المحلي. لا تُنشئ نسخة أخرى."
                    pending == null -> "تعذّر حفظ المحاولة على هذا الجهاز. لم نرسل النص."
                    else -> "لم نتلقّ تأكيدًا بالحفظ. تحقّق من المسودة نفسها، أو أعد إرسال النص نفسه فقط."
                }
            } finally {
                busy = false
            }
        }
    }

    if (!client.available()) return
    if (archiveReview) {
        val target = pending
        AlertDialog(
            onDismissRequest = { archiveReview = false },
            title = { Text("أرشفة المحاولة على هذا الجهاز؟") },
            text = { Text("سيبقى النص قابلًا للنسخ. لن يُحذف شيء من الخادم وقد تكون المسودة الخاصة محفوظة هناك. ستُستبدل أي نسخة مؤرشفة محليًا من محاولة سابقة.") },
            confirmButton = {
                TextButton(onClick = {
                    if (target == null || !progress.mayArchiveLocally || !client.available() || busy) return@TextButton
                    val proof = progress
                    archiveReview = false
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.archiveLocally(target, proof) }
                            pending = null
                            archived = target
                            showArchived = true
                            text = ""
                            progress = TranscriptDraftProgress()
                            notice = "أُرشفت المحاولة محليًا. لم نغيّر شيئًا على الخادم."
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { notice = "تعذّر تأكيد الأرشفة المحلية. أبقينا المحاولة دون تغيير." }
                        finally { busy = false }
                    }
                }) { Text("أرشف محليًا") }
            },
            dismissButton = { TextButton(onClick = { archiveReview = false }) { Text("إلغاء") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("مسودة نص خاصة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                original?.let { Text(it.title) }
                Text("يحفظ هذا الإجراء النص للمالك كمَسودة فقط. لا يستبدل نص الدرس ولا ينشره.")
                if (original != null) Text("يشترط الخادم درسًا متاحًا بإصداره الحالي.")
                val attempt = pending
                if (attempt != null) {
                    Text("محاولة حفظ قائمة للدرس: ${attempt.lessonId}")
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(attempt.text) }
                    if (!confirmed) {
                        TextButton(onClick = { act(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("تحقّق من الحفظ دون إعادة إرسال")
                        }
                        if (progress.mayArchiveLocally) TextButton(
                            onClick = { archiveReview = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("أرشف المحاولة على هذا الجهاز") }
                    }
                } else if (original != null) {
                    OutlinedTextField(
                        value = text, onValueChange = { if (it.length <= TRANSCRIPT_DRAFT_MAX_TEXT) text = it },
                        enabled = loaded && !busy && !confirmed,
                        label = { Text("نص المسودة") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                    val saved = latest
                    if (saved != null && !confirmed) {
                        TextButton(onClick = { showLatest = !showLatest }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (showLatest) "أخفِ آخر مسودة محفوظة" else "اعرض آخر مسودة محفوظة على هذا الجهاز")
                        }
                        if (showLatest) androidx.compose.foundation.text.selection.SelectionContainer { Text(saved.text) }
                        if (sameLatest) Text("هذا النص مطابق لآخر مسودة محفوظة لهذا الإصدار من الدرس.")
                    }
                    if (text.isNotEmpty() && !isPassiveDraftText(text)) {
                        Text("اكتب نصًا عاديًا دون روابط أو رموز تنسيق. لن نعدّل النص تلقائيًا.")
                    }
                }
                if (original == null && pending == null && loaded && !confirmed) Text("لا توجد محاولة حفظ معلّقة.")
                archived?.let { retained ->
                    TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (showArchived) "أخفِ النص المؤرشف محليًا" else "اعرض النص المؤرشف محليًا")
                    }
                    if (showArchived) androidx.compose.foundation.text.selection.SelectionContainer { Text(retained.text) }
                }
                notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            if (!confirmed && (original != null || pending != null)) Button(
                onClick = { act(false) },
                enabled = loaded && !busy && (pending != null || (original != null && isPassiveDraftText(text) && !sameLatest)),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (pending == null) "احفظ مسودة خاصة" else "أعِد حفظ المسودة نفسها") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("إغلاق") } },
    )
}
