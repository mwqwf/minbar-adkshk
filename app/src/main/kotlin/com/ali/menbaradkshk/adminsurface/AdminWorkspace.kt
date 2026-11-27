package com.ali.menbaradkshk.adminsurface

import com.ali.menbaradkshk.ui.adminbridge.feature.GuardedLessonFeatureClient
import com.ali.menbaradkshk.ui.adminbridge.order.GuardedLessonOrderClient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.adminsurface.analytics.AdminListeningAnalytics
import com.ali.menbaradkshk.adminsurface.review.AlertReadFilter
import com.ali.menbaradkshk.adminsurface.review.GuardedAlertReadAccess
import com.ali.menbaradkshk.adminsurface.review.GuardedAlertRow
import com.ali.menbaradkshk.adminsurface.review.matchingAlertReadFilter
import com.ali.menbaradkshk.adminsurface.review.readAdminAlerts
import com.ali.menbaradkshk.adminsurface.review.readFeedbackReview
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.GuardedTranscriptDraftClient
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftPanel
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftPending
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.validTranscriptDraftLesson
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.knownAvailableTranscriptLesson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Display role derived from the host's active server session, never from email or a preference. */
enum class AdminWorkspaceRole { OWNER, SUPERVISOR }

/** Host provides only the same-origin authenticated admin transport. No secrets enter this module. */
fun interface AdminWorkspaceApi {
    suspend fun get(path: String): JSONObject
}

/** Host maps a server denial to this type and handles session eviction. */
class AdminAccessRejected : Exception()

internal enum class Page(val label: String, val description: String, val ownerOnly: Boolean = false) {
    STATS("إحصاءات المحتوى", "أعداد الدروس والكتب والنصوص المفرّغة"),
    ANALYTICS("عدّادات الاستماع", "الدروس والأقسام الأعلى وترشيح تاريخ إضافة الدروس"),
    LESSONS("الدروس", "عناوين الدروس المتاحة في لوحة الإدارة"),
    TRASH("سلة المحذوفات", "عرض الدروس والأقسام المحذوفة من الفهرس"),
    SECTIONS("الأقسام", "الأقسام الرئيسية والفرعية"),
    SUBMISSIONS("طلبات النشر", "عرض الطلبات وحالة المراجعة"),
    SUSPICIONS("الدروس المشتبه بها", "عرض قائمة المراجعة الخاصة بالمالك", true),
    ALERTS("تنبيهات الإدارة", "عرض التنبيهات المتاحة لحسابك دون تغيير حالة القراءة"),
    FEEDBACK("ملاحظات المستمعين", "عرض أنواع الملاحظات والبلاغات ومقتطفاتها", true),
    AUDIT("سجل التدقيق", "عرض آخر العمليات الإدارية", true),
}

internal data class WorkspaceRow(
    val title: String, val description: String? = null, val submission: SubmissionSnapshot? = null,
    val lesson: LessonMetadata? = null,
    val archivedLesson: ArchivedLesson? = null,
    val alertRead: Boolean? = null,
    val sectionTarget: SectionNameTarget? = null,
    val guardedAlert: GuardedAlertRow? = null,
    val transcriptLessonReady: Boolean = false,
)
private data class PendingModeration(val submission: SubmissionSnapshot, val approve: Boolean)
private sealed interface LoadState {
    data object Loading : LoadState
    data object Failed : LoadState
    data object Rejected : LoadState
    data class Loaded(val rows: List<WorkspaceRow>) : LoadState
}

private fun Page.allows(role: AdminWorkspaceRole): Boolean = !ownerOnly || role == AdminWorkspaceRole.OWNER

/**
 * Render this only after live verification, and key it by the host's in-memory session epoch
 * (a non-secret generation counter), so protected data is discarded on any account/role switch.
 * It starts with read-only real views, not a replacement for all tools in the existing app.
 */
@Composable
internal fun AdminWorkspace(
    role: AdminWorkspaceRole,
    api: AdminWorkspaceApi,
    onBack: () -> Unit,
    onSessionRejected: () -> Unit,
    sessionTools: @Composable () -> Unit = {},
    submissionMutation: AdminSubmissionMutationApi? = null,
    lessonMutation: AdminLessonMutationApi? = null,
    trashRestore: AdminTrashRestoreApi? = null,
    sectionNames: SectionRenameClient? = null,
    guardedAlertRead: GuardedAlertReadAccess? = null,
    guardedFeature: GuardedLessonFeatureClient? = null,
    guardedOrder: GuardedLessonOrderClient? = null,
    transcriptDraft: GuardedTranscriptDraftClient? = null,
    transcriptPending: TranscriptDraftPending? = null,
) {
    val currentApi by rememberUpdatedState(api)
    val rejected by rememberUpdatedState(onSessionRejected)
    var page by remember(role) { mutableStateOf<Page?>(null) }
    var reload by remember(role) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val mutation by rememberUpdatedState(submissionMutation)
    var editingLesson by remember(role, page) { mutableStateOf<LessonMetadata?>(null) }
    var draftLesson by remember(role, page, transcriptDraft) { mutableStateOf<LessonMetadata?>(null) }
    val canDraft = role == AdminWorkspaceRole.OWNER && transcriptDraft?.available() == true && transcriptPending != null
    val archiveAuthority by rememberUpdatedState(trashRestore)
    var archiveSelection by remember(role, page) { mutableStateOf<ArchivedLesson?>(null) }
    var archiveBusy by remember(role, page) { mutableStateOf(false) }
    var moderation by remember(role, page) { mutableStateOf<PendingModeration?>(null) }
    var moderationBusy by remember(role, page) { mutableStateOf(false) }
    var resultMessage by remember(role, page) { mutableStateOf<String?>(null) }

    fun submit(action: PendingModeration, reason: String) {
        val write = mutation ?: return
        if (moderationBusy || moderation !== action || page != Page.SUBMISSIONS) return
        moderation = null
        moderationBusy = true
        resultMessage = null
        scope.launch {
            var writeStarted = false
            try {
                val decision = if (action.approve) ModerationDecision.Approve else ModerationDecision.Reject(reason)
                val command = withContext(Dispatchers.IO) {
                    prepareSubmissionCommand(action.submission, decision, currentApi)
                }
                writeStarted = true
                write.submit(command)
                resultMessage = if (action.approve) "قَبِل الخادم طلب الموافقة. تحقّق من الحالة المحدّثة."
                    else "قَبِل الخادم طلب الرفض. القائمة تتحدّث الآن."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AdminAccessRejected) {
                rejected()
            } catch (_: StaleSubmission) {
                resultMessage = "تغيّر الطلب أو قسمه. تُحدّث القائمة، ولم يُرسل قرار."
            } catch (_: Exception) {
                resultMessage = if (writeStarted) "لم يُؤكّد الخادم النتيجة. تحقّق من الحالة قبل إعادة المحاولة."
                    else "تعذّر التحقّق من الطلب، ولم يُرسل قرار."
            } finally {
                moderationBusy = false
                reload++
            }
        }
    }

    fun restoreFromArchive(item: ArchivedLesson) {
        val write = archiveAuthority ?: return
        if (archiveBusy || archiveSelection !== item || page != Page.TRASH) return
        archiveSelection = null
        archiveBusy = true
        resultMessage = null
        scope.launch {
            var sent = false
            try {
                val command = withContext(Dispatchers.IO) { prepareArchiveRestore(item, currentApi) }
                sent = true
                write.restore(command)
                resultMessage = "قَبِل الخادم استعادة الدرس. تُحدّث السلة الآن."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AdminAccessRejected) {
                rejected()
            } catch (_: ArchiveChanged) {
                resultMessage = "تغيّرت نسخة الدرس في السلة. لم يُرسل طلب استعادة."
            } catch (_: ArchiveParentsUnavailable) {
                resultMessage = "قسم الدرس أو فرعه غير متاح. لم يُرسل طلب استعادة."
            } catch (_: Exception) {
                resultMessage = if (sent) "لم تُؤكّد النتيجة. تحقّق من السلة قبل إعادة المحاولة."
                    else "تعذّر التحقّق من الدرس. لم يُرسل طلب استعادة."
            } finally {
                archiveBusy = false
                reload++
            }
        }
    }

    // Role is also a state key: no old privileged data flashes when an owner lands as supervisor.
    val selected = page?.takeIf { it.allows(role) }
    val canRenameSections = role == AdminWorkspaceRole.OWNER && sectionNames != null
    var renamingSection by remember(role, selected, sectionNames) { mutableStateOf<SectionNameTarget?>(null) }
    var alertReview by remember(role, selected, guardedAlertRead) { mutableStateOf<Pair<String,GuardedAlertRow>?>(null) }
    var markingAlert by remember(role, selected, guardedAlertRead) { mutableStateOf(false) }
    var alertFilter by remember(role, selected) { mutableStateOf(AlertReadFilter.All) }
    var load by remember(role, selected, reload) { mutableStateOf<LoadState>(LoadState.Loading) }
    LaunchedEffect(role, selected, reload, canRenameSections, guardedAlertRead) {
        val destination = selected ?: return@LaunchedEffect
        if (destination == Page.ANALYTICS) return@LaunchedEffect
        try {
            val rows = withContext(Dispatchers.IO) { if (destination == Page.ALERTS && guardedAlertRead != null) guardedAlertRead.rows()
                else loadRows(destination, role, currentApi, canRenameSections) }
            load = LoadState.Loaded(rows)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AdminAccessRejected) {
            load = LoadState.Rejected
            rejected()
        } catch (_: Exception) {
            load = LoadState.Failed
        }
    }

    val renameTarget = renamingSection
    if (selected == Page.SECTIONS && canRenameSections && sectionNames != null && renameTarget != null) {
        BackHandler { renamingSection = null }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { renamingSection = null }) { Text("العودة إلى الأقسام") }
                AdminSectionRenamePanel(renameTarget, true, sectionNames, sectionNames) {
                    reload++
                }
            }
        }
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { if (selected == null) onBack() else page = null },
                        enabled = !moderationBusy && !archiveBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (selected == null) "العودة إلى منبر" else "العودة إلى الإدارة")
                    }
                }
                Text(
                    selected?.label ?: "إدارة منبر إدكصهك",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    (if (role == AdminWorkspaceRole.OWNER) "جلسة المالك" else "جلسة المشرف") +
                        if (submissionMutation != null || lessonMutation != null || trashRestore != null) "" else " · عرض فقط",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (moderationBusy) Text("جارٍ التحقق وإرسال القرار…", Modifier.padding(12.dp))
            if (archiveBusy) Text("جارٍ التحقّق من الدرس واستعادته…", Modifier.padding(12.dp))
            resultMessage?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            if (selected == null) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "session-tools") { sessionTools() }
                    items(Page.entries.filter { it.allows(role) }, key = { it.name }) { entry ->
                        Card(
                            onClick = { page = entry },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.label, style = MaterialTheme.typography.titleMedium)
                                Text(entry.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else if (selected == Page.ANALYTICS) {
                AdminListeningAnalytics(api, onSessionRejected)
            } else {
                when (val state = load) {
                    LoadState.Loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("جارٍ تحميل البيانات…")
                    }
                    LoadState.Rejected -> Column(Modifier.padding(16.dp)) {
                        Text("تعذّر التحقق من صلاحية الإدارة.")
                    }
                    LoadState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("تعذّر تحميل البيانات. تحقّق من الاتصال ثم أعد المحاولة.")
                        Button(onClick = { reload++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("إعادة المحاولة")
                        }
                    }
                    is LoadState.Loaded -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val alerts = selected == Page.ALERTS
                        val visibleRows = if (alerts) state.rows.matchingAlertReadFilter(alertFilter) else state.rows
                        if (alerts && state.rows.isNotEmpty()) {
                            item(key = "alert-read-filter") {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (filter in AlertReadFilter.entries) {
                                        val count = state.rows.matchingAlertReadFilter(filter).size
                                        FilterChip(selected = alertFilter == filter, onClick = { alertFilter = filter },
                                            label = { Text("${filter.label} ($count)") })
                                    }
                                }
                            }
                        }
                        if (visibleRows.isEmpty()) item {
                            Text(when {
                                alerts && alertFilter == AlertReadFilter.Unread -> "لا توجد تنبيهات غير مقروءة في القائمة الحالية."
                                alerts && alertFilter == AlertReadFilter.Read -> "لا توجد تنبيهات مقروءة في القائمة الحالية."
                                else -> "لا توجد عناصر في هذه القائمة."
                            })
                        }
                        else items(visibleRows) { row ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                                    row.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    if (guardedAlertRead != null && selected == Page.ALERTS)
                                        row.guardedAlert?.takeIf { !it.read }?.let { alert ->
                                            TextButton(enabled = !markingAlert, onClick = { alertReview = row.title to alert },
                                                modifier = Modifier.heightIn(min=48.dp)) { Text("علّم كمقروء") }
                                        }
                                    if (canRenameSections && selected == Page.SECTIONS) row.sectionTarget?.let { target ->
                                        TextButton(onClick = { renamingSection = target },
                                            modifier = Modifier.heightIn(min = 48.dp)) { Text("تغيير الاسم") }
                                    }
                                    if (trashRestore != null && selected == Page.TRASH) row.archivedLesson?.let { lesson ->
                                        TextButton(
                                            onClick = { archiveSelection = lesson },
                                            enabled = !archiveBusy,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("استعادة الدرس") }
                                    }
                                    if (canDraft && selected == Page.LESSONS && row.transcriptLessonReady) row.lesson?.let { lesson ->
                                        if (validTranscriptDraftLesson(lesson.id, lesson.updatedAtMs)) {
                                            TextButton(onClick = { draftLesson = lesson }, modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text("مسودة نص خاصة")
                                            }
                                        }
                                    }
                                    if (lessonMutation != null && selected == Page.LESSONS) row.lesson?.let { lesson ->
                                        TextButton(
                                            onClick = { editingLesson = lesson },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("تعديل العنوان أو النقل") }
                                    }
                                    if (submissionMutation != null) row.submission?.let { submission ->
                                        if (submission.canApprove) {
                                            TextButton(
                                                onClick = { moderation = PendingModeration(submission, approve = true) },
                                                enabled = !moderationBusy,
                                                modifier = Modifier.heightIn(min = 48.dp),
                                            ) { Text("موافقة بعد المراجعة") }
                                        }
                                        TextButton(
                                            onClick = { moderation = PendingModeration(submission, approve = false) },
                                            enabled = !moderationBusy,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("رفض الطلب") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val reviewedAlert = alertReview
    if (selected == Page.ALERTS && guardedAlertRead != null && reviewedAlert != null) {
        AlertDialog(onDismissRequest = { alertReview = null },
            title = { Text("تعليم التنبيه كمقروء") },
            text = { Text("يُعلّم «${reviewedAlert.first}» كمقروء لحسابك فقط.") },
            confirmButton = {
                TextButton(enabled = !markingAlert,onClick = {
                    if (markingAlert || alertReview != reviewedAlert) return@TextButton
                    alertReview = null
                    markingAlert = true
                    resultMessage = null
                    scope.launch {
                        try {
                            guardedAlertRead.mark(reviewedAlert.second)
                            resultMessage = "عُلّم التنبيه كمقروء."
                        } catch (e: CancellationException) { throw e }
                        catch (_: AdminAccessRejected) { rejected() }
                        catch (_: Exception) { resultMessage = "لم تُؤكَّد حالة التنبيه. تحقّق منها قبل إعادة المحاولة." }
                        finally { markingAlert = false; reload++ }
                    }
                }) { Text("أكّد") }
            }, dismissButton = { TextButton(onClick = { alertReview = null }) { Text("رجوع") } })
    }
    archiveSelection?.let { item ->
        if (selected == Page.TRASH && trashRestore != null) AlertDialog(
            onDismissRequest = { archiveSelection = null },
            title = { Text("تأكيد استعادة درس") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title.take(240).ifBlank { "درس دون عنوان" })
                    Text("قد يعود ظهور هذا الدرس في فهرس المنبر حسب حالته. يستعيد الإجراء هذا الدرس وحده ولا يستعيد أي قسم.")
                }
            },
            confirmButton = {
                Button(onClick = { restoreFromArchive(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("أؤكّد الاستعادة")
                }
            },
            dismissButton = { TextButton(onClick = { archiveSelection = null }) { Text("إلغاء") } },
        )
    }
    val draftTarget = draftLesson
    if (canDraft && selected == Page.LESSONS && draftTarget != null) {
        TranscriptDraftPanel(draftTarget, transcriptDraft, transcriptPending) { draftLesson = null }
    }
    val chosenLesson = editingLesson
    if (selected == Page.LESSONS && chosenLesson != null && lessonMutation != null) {
        AdminLessonEditor(
            original = chosenLesson,
            guardedFeature = guardedFeature?.takeIf { role == AdminWorkspaceRole.OWNER },
            guardedOrder = guardedOrder?.takeIf { role == AdminWorkspaceRole.OWNER },
            api = api,
            write = lessonMutation,
            dismiss = { editingLesson = null },
            rejected = rejected,
            result = { message ->
                editingLesson = null
                resultMessage = message
                reload++
            },
        )
    }
    moderation?.let { action ->
        ModerationDialog(
            action = action,
            onCancel = { moderation = null },
            onConfirm = { reason -> submit(action, reason) },
        )
    }
}
@Composable
private fun ModerationDialog(
    action: PendingModeration,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember(action) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (action.approve) "تأكيد الموافقة" else "تأكيد الرفض") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(action.submission.title.take(240).ifBlank { "طلب دون عنوان" })
                if (action.approve) {
                    Text("الموافقة ترسل التسجيل للنشر. أكّد فقط إذا استمعت إليه وراجعت محتواه وحقوقه مسبقًا.")
                } else {
                    Text("سيصل السبب إلى المساهم. اكتب سببًا واضحًا دون معلومات خاصة.")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { if (it.length <= ModerationReasonMax) reason = it },
                        label = { Text("سبب الرفض") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason.trim()) },
                enabled = action.approve || reason.trim().length in 2..ModerationReasonMax,
            ) { Text(if (action.approve) "أوافق وأرسل للنشر" else "أرفض وأرسل السبب") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("إلغاء") } },
    )
}

internal suspend fun loadRows(
    page: Page, role: AdminWorkspaceRole, api: AdminWorkspaceApi, canRenameSections: Boolean = false,
): List<WorkspaceRow> {
    if (!page.allows(role)) throw AdminAccessRejected()
    // Only fixed API paths from the existing administration application are admitted.
    return when (page) {
        Page.STATS -> readStats(api.get("/admin/stats"))
        Page.ANALYTICS -> error("Analytics owns its read-only screen state.")
        Page.LESSONS -> rows(api.get("/admin/lessons")).sortedByDescending { it.optLong("created_at_ms") }
            .map {
                WorkspaceRow(
                    it.readable("title", "درس دون عنوان"),
                    lesson = liveLessonMetadata(it),
                    transcriptLessonReady = knownAvailableTranscriptLesson(it.opt("audio_status"), it.opt("publish_at_ms"), System.currentTimeMillis()),
                )
            }
        Page.SECTIONS -> {
            val groups = rows(api.get("/admin/categories"))
            val subcategories = rows(api.get("/admin/subcategories"))
            val counts = subcategories.groupingBy { it.optString("category_id") }.eachCount()
            val actionable = canRenameSections && role == AdminWorkspaceRole.OWNER
            val children = if (actionable) subcategories.groupBy { it.optString("category_id") } else emptyMap()
            groups.flatMap { group ->
                val id = group.optString("id")
                val root = WorkspaceRow(
                    group.readable("name", "قسم دون اسم"),
                    "الأقسام الفرعية: ${arabicDigits(counts[id] ?: 0)}",
                    sectionTarget = if (actionable) sectionNameTarget(SectionNameKind.Category, group) else null,
                )
                listOf(root) + children[id].orEmpty().map { child ->
                    WorkspaceRow(child.readable("name", "قسم فرعي دون اسم"), "قسم فرعي من «${root.title}»",
                        sectionTarget = sectionNameTarget(SectionNameKind.Subcategory, child))
                }
            }
        }
        Page.TRASH -> readArchive(api.get(AdminTrashPath))
        Page.SUBMISSIONS -> rows(api.get(SubmissionListPath))
            .sortedWith(compareByDescending<JSONObject> { it.optString("status") == "pending" }.thenByDescending { it.optLong("created_at_ms") })
            .map {
                val status = when (it.optString("status")) {
                    "pending" -> "بانتظار المراجعة"
                    "approved", "approved_edited" -> "مقبول"
                    "rejected" -> "مرفوض"
                    else -> "الحالة غير معروفة"
                }
                WorkspaceRow(it.readable("title", "طلب دون عنوان"), status, parsePendingSubmission(it))
            }
        Page.SUSPICIONS -> rows(api.get("/admin/suspicions")).map {
            WorkspaceRow(
                it.readable("title", "درس دون عنوان"),
                if (it.optString("status") in setOf("pending", "flagged")) "قيد المراجعة" else "عنصر في سجل المراجعة",
            )
        }
        Page.ALERTS -> readAdminAlerts(role, api)
        Page.FEEDBACK -> readFeedbackReview(role, api)
        Page.AUDIT -> {
            val sinceMs = (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000).coerceAtLeast(0L)
            rows(api.get("/admin/audit?since=$sinceMs")).sortedByDescending { it.optLong("at_ms") }
                .map { WorkspaceRow(it.readable("title", "عملية إدارية")) }
        }
    }
}

private fun readStats(json: JSONObject): List<WorkspaceRow> = listOf(
    "الدروس" to "lessons",
    "الكتب" to "books",
    "النصوص المفرّغة" to "transcripts",
).map { (label, key) ->
    // A malformed/missing count is a load failure, not a misleading empty collection.
    require(json.has(key) && !json.isNull(key))
    val value = json.optInt(key, -1)
    require(value >= 0)
    WorkspaceRow(label, arabicDigits(value))
}

private fun rows(json: JSONObject): List<JSONObject> {
    val array = requireNotNull(json.optJSONArray("items"))
    return (0 until array.length()).map { index -> array.getJSONObject(index) }
}

/** Avoid showing IDs, credentials, emails, raw rejection messages or raw JSON as fallbacks. */
private fun JSONObject.readable(key: String, fallback: String): String =
    if (isNull(key)) fallback else optString(key).trim().take(240).ifBlank { fallback }

private fun arabicDigits(value: Int): String = value.toString().map {
    if (it in '0'..'9') ('٠'.code + (it - '0')).toChar() else it
}.joinToString("")
