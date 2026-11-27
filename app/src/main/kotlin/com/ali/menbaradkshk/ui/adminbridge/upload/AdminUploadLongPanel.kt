package com.ali.menbaradkshk.ui.adminbridge.upload

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.ali.menbaradkshk.adminsurface.LiveLessonDestination
import com.ali.menbaradkshk.ui.adminbridge.publication.GuardedNewLessonPublicationClient
import com.ali.menbaradkshk.ui.adminbridge.publication.GuardedNewLessonPublicationPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun AdminUploadLongPanel(
    upload: AdminLongUploadOrchestration,
    draft: AdminUploadDraft,
    loadDestinations: suspend () -> List<LiveLessonDestination>,
    onBack: () -> Unit,
    newLesson: GuardedNewLessonPublicationClient? = null,
) {
    val scope = rememberCoroutineScope()
    var review by remember(upload, draft) { mutableStateOf<AdminLongUploadOrchestration.Review?>(null) }
    var job by remember(upload, draft) { mutableStateOf<Job?>(null) }
    var busy by remember(upload, draft) { mutableStateOf(false) }
    var sending by remember(upload, draft) { mutableStateOf(false) }
    var showConfirmation by remember(upload, draft) { mutableStateOf(false) }
    var receipt by remember(upload, draft) { mutableStateOf<AdminLongUploadReceipt?>(null) }
    var note by remember(upload, draft) { mutableStateOf<String?>(null) }
    var assembling by remember(upload, draft) { mutableStateOf(false) }
    var assemblyDialog by remember(upload, draft) { mutableStateOf(false) }
    var assembly by remember(upload, draft) { mutableStateOf<AdminLongAssemblyReceipt?>(null) }
    var preparePage by remember(upload, draft) { mutableStateOf(false) }
    var prepared by remember(upload, draft) { mutableStateOf<AdminLongPreparationReceipt?>(null) }
    var attempted by remember(upload, draft) { mutableStateOf(false) }
    var publicationBusy by remember(upload,draft) { mutableStateOf(false) }
    val assembled = assembly
    if (preparePage && upload.canPrepare && assembled != null) {
        AdminUploadLongPreparePanel(upload, assembled, draft.fileName, loadDestinations, onBack = { preparePage = false },
            onPrepared = { prepared = it; preparePage = false })
        return
    }
    BackHandler { if (!busy && !publicationBusy) onBack() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("رفع التسجيل الطويل إلى مساحة خاصة", style = MaterialTheme.typography.headlineSmall)
        Text(draft.fileName)
        Text("${draft.byteCount} بايت · الأصل محفوظ على هذا الجهاز")
        Text("المراجعة محلية. لن يُرسل الملف قبل تأكيدك، ولا ينشر الرفع تسجيلًا أو يشغّل تحويله.")
        if (receipt == null && review == null) Button(enabled = !busy, onClick = {
            busy = true
            note = null
            job = scope.launch {
                try {
                    review = upload.review(draft)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    note = "تعذّر التحقق من المسودة أو صلاحية هذه الجلسة. لم يبدأ الرفع."
                } finally {
                    busy = false
                }
            }
        }) { Text("تحقّق من الملف على الجهاز") }

        val checked = review
        if (checked != null && receipt == null) {
            Text("تم التحقق من الملف: ${checked.manifest.parts.size} أجزاء.")
            Button(enabled = !busy, onClick = { showConfirmation = true }) {
                Text(if (attempted) "راجع وأكّد الاستئناف" else "راجع وأكّد الرفع")
            }
        }
        if (busy) {
            CircularProgressIndicator()
            TextButton(onClick = {
                note = when {
                    assembling -> "أوقفت انتظار التجميع. قد يكون الخادم قد أتمه؛ تحقّق مرة أخرى بعد تأكيدك."
                    sending -> "أوقفت المحاولة. قد تكون بعض الأجزاء حُفظت؛ لا تُستأنف إلا بعد تأكيدك."
                    else -> "أُلغي التحقق. الأصل محفوظ على الجهاز."
                }
                job?.cancel()
            }) { Text(when {
                assembling -> "أوقف انتظار التجميع"
                sending -> "أوقف محاولة الرفع"
                else -> "أوقف التحقق"
            }) }
        }
        val result = receipt
        val preparedProof = prepared
        if (preparedProof != null) {
            Text("اكتمل التجهيز الخاص. لا تنشر هذه الخطوة التسجيل ولا تحوّل صوته.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (newLesson?.eligible(draft,preparedProof) == true) {
                GuardedNewLessonPublicationPanel(newLesson,draft,preparedProof) { publicationBusy = it }
            }
        } else if (assembly != null) {
            Text("حُفظ التسجيل المجمّع في المساحة الخاصة وتحقّق الخادم من بصمته. لم يُنشر ولم يُحوّل.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (upload.canPrepare) Button(enabled = !busy, onClick = { preparePage = true }) {
                Text("اختر وجهة التجهيز الخاص")
            }
        } else if (result != null) {
            Text("تحقّق الخادم من جميع أجزاء التسجيل في المساحة الخاصة. لم يُؤكّد تجميعه في ملف واحد.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (upload.canAssemble) Button(enabled = !busy, onClick = { assemblyDialog = true }) {
                Text("راجع وأكّد تجميع الملف")
            }
        }
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        TextButton(onClick = onBack, enabled = !busy && !publicationBusy) { Text("رجوع إلى المسودات") }
    }
    val parts = receipt
    if (assemblyDialog && parts != null && upload.canAssemble) AlertDialog(
        onDismissRequest = { assemblyDialog = false },
        title = { Text("تأكيد التجميع الخاص") },
        text = { Text("سيجمع الخادم أجزاء «${draft.fileName}» في ملف خاص ويتحقق من بصمته. لن ينشره أو يحوّل صوته.") },
        confirmButton = {
            TextButton(onClick = {
                assemblyDialog = false
                val confirmation = try { upload.confirmAssembly(parts) } catch (_: Exception) {
                    note = "انتهت صلاحية التحقق أو الجلسة. لم يبدأ طلب التجميع."
                    return@TextButton
                }
                busy = true
                assembling = true
                note = null
                job = scope.launch {
                    try {
                        assembly = upload.assemble(confirmation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        note = "لم يصل تأكيد التجميع الخاص. يمكنك التحقق منه بتأكيد مستقل. الأصل محفوظ على الجهاز."
                    } finally {
                        busy = false
                        assembling = false
                    }
                }
            }) { Text("أؤكد التجميع") }
        },
        dismissButton = { TextButton(onClick = { assemblyDialog = false }) { Text("إلغاء") } },
    )
    val checked = review
    if (showConfirmation && checked != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text(if (attempted) "استئناف الرفع الخاص" else "تأكيد الرفع الخاص") },
            text = { Text("سيُرفع «${draft.fileName}» (${draft.byteCount} بايت) إلى مساحة خاصة. لن ينشر التسجيل.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    val confirmation = try { upload.confirmAfterReview(checked) } catch (_: Exception) {
                        note = "انتهت صلاحية الجلسة أو المراجعة. لم يبدأ طلب جديد."
                        review = null
                        return@TextButton
                    }
                    attempted = true
                    busy = true
                    sending = true
                    note = null
                    job = scope.launch {
                        try {
                            receipt = upload.send(confirmation)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            note = "لم يصل تأكيد اكتمال الرفع الخاص. الأصل محفوظ. يمكنك مراجعة الاستئناف وتأكيده."
                        } finally {
                            busy = false
                            sending = false
                        }
                    }
                }) { Text("أؤكد") }
            },
            dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("إلغاء") } },
        )
    }
}
