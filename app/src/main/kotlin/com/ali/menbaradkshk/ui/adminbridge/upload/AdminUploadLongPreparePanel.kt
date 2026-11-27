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
import com.ali.menbaradkshk.adminsurface.LiveLessonDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun LiveLessonDestination.matches(intent: AdminLongPrepareIntent) =
    categoryId == intent.categoryId && subcategoryId == intent.subcategoryId &&
        categoryName == intent.expectedCategoryName && subcategoryName == intent.expectedSubcategoryName

@Composable
internal fun AdminUploadLongPreparePanel(
    upload: AdminLongUploadOrchestration,
    assembly: AdminLongAssemblyReceipt,
    fileName: String,
    loadDestinations: suspend () -> List<LiveLessonDestination>,
    onBack: () -> Unit,
    onPrepared: (AdminLongPreparationReceipt) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var destinations by remember(upload, assembly) { mutableStateOf<List<LiveLessonDestination>?>(null) }
    var frozen by remember(upload, assembly) { mutableStateOf<AdminLongPrepareIntent?>(null) }
    var title by remember(upload, assembly) { mutableStateOf("") }
    val suggestedTitle = remember(fileName) { suggestAdminLessonTitle(fileName) }
    var speaker by remember(upload, assembly) { mutableStateOf("") }
    var description by remember(upload, assembly) { mutableStateOf("") }
    var category by remember(upload, assembly) { mutableStateOf<String?>(null) }
    var choice by remember(upload, assembly) { mutableStateOf<LiveLessonDestination?>(null) }
    var reviewed by remember(upload, assembly) { mutableStateOf<AdminLongPrepareIntent?>(null) }
    var note by remember(upload, assembly) { mutableStateOf<String?>(null) }
    var pending by remember(upload, assembly) { mutableStateOf(false) }
    var job by remember(upload, assembly) { mutableStateOf<Job?>(null) }
    BackHandler { if (!pending) onBack() }

    LaunchedEffect(upload, assembly) {
        try {
            if (!upload.canPrepare) return@LaunchedEffect
            frozen = upload.savedPreparation(assembly)
            val found = loadDestinations()
            if (upload.canPrepare) destinations = found
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            note = "تعذّر تحميل الأقسام أو مراجعة المسودة. لم يُرسل طلب تجهيز."
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تجهيز مسودة خاصة من التسجيل المجمّع")
        Text("يحدد التجهيز وجهة الدرس فقط. لا يبدأ النشر أو تحويل الصوت.")
        val existing = frozen
        val targets = destinations
        if (existing != null) {
            Text("ثُبّتت تفاصيل التجهيز. بعد نتيجة غير مؤكدة، يمكن إعادة المحاولة بالتفاصيل نفسها فقط.")
            Text("العنوان: ${existing.title}")
            Text("${existing.expectedCategoryName} / ${existing.expectedSubcategoryName}")
        } else if (!targets.isNullOrEmpty()) {
            OutlinedTextField(title, { title = it.take(400) }, Modifier.fillMaxWidth(), enabled = !pending,
                label = { Text("عنوان الدرس") }, singleLine = true)
            if (suggestedTitle != null && title.isBlank()) {
                TextButton(enabled = !pending && upload.canPrepare, onClick = {
                    if (!pending && upload.canPrepare && reviewed == null && frozen == null && title.isBlank())
                        title = suggestedTitle
                }) { Text("استخدم عنوانًا من اسم الملف") }
            }
            OutlinedTextField(speaker, { speaker = it.take(160) }, Modifier.fillMaxWidth(), enabled = !pending,
                label = { Text("المتحدث (اختياري)") }, singleLine = true)
            OutlinedTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth(), enabled = !pending,
                label = { Text("الوصف (اختياري)") })
            StageDestinationPicker("اختر القسم", targets.distinctBy { it.categoryId }.map { it.categoryId to it.categoryName },
                category, !pending) {
                category = it
                choice = null
            }
            val options = targets.filter { it.categoryId == category }
            if (options.isNotEmpty()) StageDestinationPicker("اختر القسم الفرعي",
                options.map { it.subcategoryId to it.subcategoryName }, choice?.subcategoryId, !pending) { id ->
                choice = options.singleOrNull { it.subcategoryId == id }
            }
        } else if (targets != null) Text("لا توجد أقسام نشطة متاحة.")
        val proposed = existing ?: choice?.let {
            AdminLongPrepareIntent(title.trim(), speaker.trim(), description.trim(), it.categoryId, it.subcategoryId,
                it.categoryName, it.subcategoryName)
        }
        val valid = proposed != null && targets?.count { it.matches(proposed) } == 1 &&
            runCatching { proposed.encode(assembly) }.isSuccess
        if (existing != null && targets != null && targets.none { it.matches(existing) })
            Text("تغيّرت وجهة هذه المسودة منذ التأكيد. لن يستبدل التطبيق الوجهة المثبّتة.")
        if (proposed != null) Button(enabled = !pending && valid && upload.canPrepare, onClick = {
            reviewed = proposed
        }) { Text("راجع وأكّد التجهيز") }
        if (pending) TextButton(onClick = {
            note = "أوقفت انتظار التجهيز. قد تكون التفاصيل حُفظت؛ أعد التأكيد بنفس التفاصيل للتحقق."
            job?.cancel()
        }) { Text("أوقف انتظار التجهيز") }
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        TextButton(enabled = !pending, onClick = onBack) { Text("رجوع") }
    }
    val reviewedIntent = reviewed
    if (reviewedIntent != null && upload.canPrepare) AlertDialog(
        onDismissRequest = { reviewed = null },
        title = { Text("تأكيد تجهيز المسودة الخاصة") },
        text = { Text("سيُجهّز «${reviewedIntent.title}» في ${reviewedIntent.expectedCategoryName} / ${reviewedIntent.expectedSubcategoryName}. لن ينشر التطبيق الدرس.") },
        confirmButton = {
            TextButton(onClick = {
                reviewed = null
                val confirmation = try { upload.confirmPreparation(assembly, reviewedIntent) } catch (_: Exception) {
                    note = "انتهت صلاحية المراجعة. لم يُرسل طلب تجهيز."
                    return@TextButton
                }
                frozen = reviewedIntent
                pending = true
                note = null
                job = scope.launch {
                    try {
                        onPrepared(upload.prepare(confirmation))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        note = "لم يصل تأكيد التجهيز. يمكنك التحقق بتأكيد مستقل وبالتفاصيل نفسها."
                    } finally {
                        pending = false
                    }
                }
            }) { Text("أؤكد التجهيز") }
        },
        dismissButton = { TextButton(onClick = { reviewed = null }) { Text("إلغاء") } },
    )
}
