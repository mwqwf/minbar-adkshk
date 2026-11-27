package com.ali.menbaradkshk.ui.adminbridge.upload

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import com.ali.menbaradkshk.adminsurface.LiveLessonDestination
import com.ali.menbaradkshk.adminsurface.fetchLessonDestinations
import com.ali.menbaradkshk.ui.adminbridge.VerifiedAdminSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun LiveLessonDestination.matchesIntent(value: UploadStageIntent): Boolean =
    categoryId == value.categoryId && categoryName == value.expectedCategoryName &&
        subcategoryId == value.subcategoryId && subcategoryName == value.expectedSubcategoryName

private data class InspectedPrivateDraft(
    val mime: String,
    val frozen: UploadStageIntent?,
    val destinations: List<LiveLessonDestination>,
)

@Composable
internal fun AdminUploadDraftStagePanel(
    session: VerifiedAdminSession,
    store: AdminUploadDraftStore,
    draft: AdminUploadDraft,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var inspected by remember(session, draft) { mutableStateOf<InspectedPrivateDraft?>(null) }
    var review by remember(session, draft) { mutableStateOf<UploadStageIntent?>(null) }
    var notice by remember(session, draft) { mutableStateOf<String?>(null) }
    var title by remember(session, draft) { mutableStateOf("") }
    val suggestedTitle = remember(draft.fileName) { suggestAdminLessonTitle(draft.fileName) }
    var categoryId by remember(session, draft) { mutableStateOf<String?>(null) }
    var destination by remember(session, draft) { mutableStateOf<LiveLessonDestination?>(null) }
    var busy by remember(session, draft) { mutableStateOf(false) }
    var completed by remember(session, draft) { mutableStateOf(false) }
    var attempted by remember(session, draft) { mutableStateOf<UploadStageIntent?>(null) }
    var prepareReview by remember(session, draft) { mutableStateOf<UploadStageReceipt?>(null) }
    var prepared by remember(session, draft) { mutableStateOf<PrivateLessonPreparation?>(null) }
    var preparationUncertain by remember(session, draft) { mutableStateOf(false) }
    fun eligible() = store.isForAccount(session.accountKey) &&
        session.privateUploadMaxBytes?.let { draft.byteCount in 1..it.toLong() } == true
    BackHandler { if (!busy) onBack() }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("رفع مسودة خاصة", style = MaterialTheme.typography.headlineSmall)
        Text(draft.fileName, style = MaterialTheme.typography.titleMedium)
        Text("${draft.byteCount} بايت · النسخة الأصلية محفوظة على الجهاز")
        Text("الفحص لا يرفع الملف. يمكنك مراجعة التفاصيل ثم تأكيد الحفظ الخاص. لا ينشر هذا الإجراء الدرس.")
        if (!completed && eligible() && inspected == null) {
            Button(enabled = !busy, onClick = {
                if (busy || !eligible()) return@Button
                busy = true
                notice = null
                scope.launch {
                    try {
                        val (actual, mime) = session.inspectPrivateUpload(store, draft.id)
                        check(actual == draft && eligible())
                        val prior = withContext(Dispatchers.IO) { store.confirmedIntent(actual) }
                        val targets = fetchLessonDestinations(AdminWorkspaceApi { path -> session.request("GET", path) })
                            .filter { target ->
                                runCatching {
                                    UploadStageIntent("مسودة", "", "", target.categoryId, target.subcategoryId,
                                        target.categoryName, target.subcategoryName, mime).encode(draft)
                                }.isSuccess
                            }
                        check(eligible())
                        inspected = InspectedPrivateDraft(mime, prior, targets)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        notice = "تعذّر التحقق من الملف بصيغته الحالية أو جلب الأقسام المتاحة. لم تُرفع نسخة جديدة."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("افحص الملف واعرض الأقسام") }
        }
        val verified = inspected
        if (verified != null && !completed) {
            val locked = verified.frozen ?: attempted
            if (locked != null) {
                Text("توجد تفاصيل مؤكّدة لهذه المسودة. إن لم تعرف نتيجة المحاولة السابقة، أعد التأكيد بالتفاصيل نفسها.")
                Text("العنوان: ${locked.title}")
                Text("${locked.expectedCategoryName} / ${locked.expectedSubcategoryName}")
            } else if (verified.destinations.isNotEmpty()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(400) },
                    enabled = !busy,
                    label = { Text("عنوان الدرس") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestedTitle != null && title.isBlank()) {
                    TextButton(enabled = !busy && eligible(), onClick = {
                        if (!busy && eligible() && review == null && attempted == null && title.isBlank())
                            title = suggestedTitle
                    }) { Text("استخدم عنوانًا من اسم الملف") }
                }
                val groups = verified.destinations.distinctBy { it.categoryId }.map { it.categoryId to it.categoryName }
                StageDestinationPicker("اختر القسم", groups, categoryId, !busy) {
                    categoryId = it
                    destination = null
                }
                val children = verified.destinations.filter { it.categoryId == categoryId }
                if (children.isNotEmpty()) StageDestinationPicker("اختر القسم الفرعي",
                    children.map { it.subcategoryId to it.subcategoryName }, destination?.subcategoryId, !busy) { child ->
                    destination = children.singleOrNull { it.subcategoryId == child }
                }
            } else {
                Text("لا توجد أقسام نشطة متاحة لهذه المسودة.")
            }
            val proposed = locked ?: destination?.let { choice ->
                UploadStageIntent(title.trim(), "", "", choice.categoryId, choice.subcategoryId,
                    choice.categoryName, choice.subcategoryName, verified.mime)
            }
            val valid = proposed != null && proposed.mimeType == verified.mime &&
                verified.destinations.count { it.matchesIntent(proposed) } == 1 &&
                runCatching { proposed.encode(draft).toString().toByteArray(Charsets.UTF_8).size <= 8192 }.getOrDefault(false)
            if (locked != null && !valid) Text("لم تعد الوجهة الأصلية أو صيغة الملف قابلة للتحقق. بقيت المسودة على الجهاز.")
            Button(onClick = { if (!busy && eligible() && valid) review = proposed },
                enabled = !busy && eligible() && valid) { Text("راجع قبل الحفظ الخاص") }
            val shown = review
            if (shown != null) {
                AdminUploadPrivateReview(draft, shown, verified.mime, eligible() && valid && proposed == shown, busy,
                    onConfirm = { explicit ->
                        if (!busy && !completed && eligible() && valid && explicit == shown && explicit == proposed) {
                            busy = true
                            attempted = explicit
                            notice = null
                            scope.launch {
                                try {
                                    session.stagePrivateUpload(store, draft.id, explicit)
                                    completed = true
                                    notice = "حُفظت نسخة خاصة على الخادم، ولم يُنشر الدرس. النسخة الأصلية ما زالت على جهازك."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    notice = "لم نتلقَّ تأكيدًا بحفظ النسخة الخاصة. النسخة على جهازك؛ يمكنك إعادة التأكيد بالتفاصيل نفسها فقط."
                                } finally {
                                    busy = false
                                    review = null
                                }
                            }
                        }
                    }, onDismiss = { review = null })
            }
        }
        if (session.canAdoptStagedLesson && eligible() && prepared == null && review == null) {
            TextButton(enabled = !busy, onClick = {
                if (busy || !eligible() || !session.canAdoptStagedLesson) return@TextButton
                busy = true
                notice = null
                scope.launch {
                    try {
                        prepareReview = session.verifyCommittedForPreparation(store, draft)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        notice = "لم تُثبت نسخة خاصة مكتملة لهذه المسودة. تحقق من الحفظ الخاص أولًا."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (preparationUncertain) "تحقّق قبل إعادة تأكيد التجهيز" else "تحقّق لتجهيز النسخة الخاصة") }
        }
        prepareReview?.let { proven ->
            if (session.canAdoptStagedLesson && eligible()) {
                AlertDialog(onDismissRequest = { if (!busy) prepareReview = null },
                    title = { Text("تجهيز نسخة خاصة محفوظة") },
                    text = { Text("سيُجهَّز الملف «${draft.fileName}» كمسودة خاصة فقط. لن يُنشر الدرس ولن يتحوّل الصوت.") },
                    confirmButton = {
                        TextButton(enabled = !busy, onClick = {
                            if (busy || prepareReview != proven || !eligible() || !session.canAdoptStagedLesson)
                                return@TextButton
                            busy = true
                            notice = null
                            scope.launch {
                                try {
                                    prepared = session.preparePrivateLesson(store, draft, proven)
                                    preparationUncertain = false
                                    notice = "جُهّزت المسودة الخاصة. لم يُنشر الدرس ولم يتغيّر الصوت."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    preparationUncertain = true
                                    notice = "لم يصل تأكيد التجهيز. يمكنك التحقق ثم التأكيد مرةً أخرى للمسودة نفسها."
                                } finally {
                                    busy = false
                                    prepareReview = null
                                }
                            }
                        }) { Text("أكّد التجهيز الخاص") }
                    }, dismissButton = {
                        TextButton(enabled = !busy, onClick = { prepareReview = null }) { Text("رجوع") }
                    })
            }
        }
        if (busy) CircularProgressIndicator()
        notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        TextButton(onClick = onBack, enabled = !busy) { Text("رجوع إلى المسودات") }
    }
}

@Composable
internal fun StageDestinationPicker(
    label: String, options: List<Pair<String, String>>, current: String?, enabled: Boolean,
    choose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(options.singleOrNull { it.first == current }?.second ?: label)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            for ((id, name) in options) {
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; choose(id) })
            }
        }
    }
}
