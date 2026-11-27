package com.ali.menbaradkshk.adminsurface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ali.menbaradkshk.ui.adminbridge.feature.GuardedLessonFeatureClient
import com.ali.menbaradkshk.ui.adminbridge.order.GuardedLessonOrderClient
import com.ali.menbaradkshk.ui.adminbridge.AdminBridgeFailure

private enum class LessonEditMode { PICK, TITLE, MOVE, FEATURE }

private enum class FeatureChoice(val label: String, val durationMs: Long? = null) {
    Permanent("تمييز حتى الإزالة"),
    Day("تمييز لمدة ٢٤ ساعة", 24L * 60 * 60 * 1000),
    Week("تمييز لمدة ٧ أيام", 7L * 24 * 60 * 60 * 1000),
    Remove("إزالة التمييز"),
}

private fun describeDeadline(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("ar")).format(Date(ms))

@Composable
internal fun AdminLessonEditor(
    original: LessonMetadata,
    api: AdminWorkspaceApi,
    write: AdminLessonMutationApi,
    dismiss: () -> Unit,
    rejected: () -> Unit,
    result: (String) -> Unit,
    guardedFeature: GuardedLessonFeatureClient? = null,
    guardedOrder: GuardedLessonOrderClient? = null,
) {
    var orderOpen by remember(original,guardedOrder) { mutableStateOf(false) }
    val order = guardedOrder?.takeIf { it.available() }
    if (orderOpen && order != null) {
        AdminLessonOrderDialog(original,order,{ orderOpen = false },rejected,result)
        return
    }
    val scope = rememberCoroutineScope()
    var mode by remember(original) { mutableStateOf(LessonEditMode.PICK) }
    var title by remember(original) { mutableStateOf(original.title.take(MaxLessonTitle)) }
    var targets by remember(original) { mutableStateOf<List<LiveLessonDestination>?>(null) }
    var target by remember(original) { mutableStateOf<LiveLessonDestination?>(null) }
    var failed by remember(original) { mutableStateOf(false) }
    var busy by remember(original) { mutableStateOf(false) }
    var confirm by remember(original) { mutableStateOf<LessonMetadataEdit?>(null) }
    var featuredFrame by remember(original) { mutableStateOf<GuardedLessonFeatureClient.Review?>(null) }
    var featuredError by remember(original) { mutableStateOf(false) }
    var featureChoice by remember(original, guardedFeature) { mutableStateOf<FeatureChoice?>(null) }
    var selectedDeadline by remember(original, guardedFeature) { mutableStateOf<Long?>(null) }
    DisposableEffect(original,guardedFeature) { onDispose { guardedFeature?.discard() } }
    LaunchedEffect(original, mode, guardedFeature) {
        val client = guardedFeature ?: return@LaunchedEffect
        if (mode != LessonEditMode.FEATURE) return@LaunchedEffect
        featuredFrame = null
        featuredError = false
        featureChoice = null
        selectedDeadline = null
        try {
            val review = client.review(original.id)
            featureChoice = if (review.featured) FeatureChoice.Remove else FeatureChoice.Permanent
            featuredFrame = review
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: AdminBridgeFailure) {
            if (e.statusCode == 401 || e.statusCode == 403) rejected() else featuredError = true
        } catch (_: Exception) {
            featuredError = true
        }
    }

    fun submitFeature(item: GuardedLessonFeatureClient.Review) {
        val client = guardedFeature ?: return
        if (busy || mode != LessonEditMode.FEATURE || featuredFrame !== item || !client.available()) return
        val choice = featureChoice ?: return
        val deadline = when (choice) {
            FeatureChoice.Day, FeatureChoice.Week -> selectedDeadline ?: return
            FeatureChoice.Permanent -> if (item.featured && item.featuredUntilMs > 0L) 0L else null
            FeatureChoice.Remove -> null
        }
        if (deadline != null && !client.canSetDeadline()) return
        if (deadline != null && deadline > 0L && deadline <= System.currentTimeMillis()) {
            selectedDeadline = null
            featureChoice = null
            return
        }
        busy = true
        featuredFrame = null
        scope.launch {
            try {
                val nowFeatured = if (deadline != null) client.confirmDeadline(item, deadline) else client.confirm(item)
                result(when {
                    !nowFeatured -> "أُزيل الدرس من مختارات المنبر."
                    deadline != null && deadline > 0L -> "حُفظ التمييز المؤقّت حتى ${describeDeadline(deadline)}."
                    deadline == 0L -> "حُفظ التمييز دون موعد انتهاء."
                    else -> "أُضيف الدرس إلى مختارات المنبر."
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: AdminBridgeFailure) {
                when (e.statusCode) {
                    401, 403 -> rejected()
                    404, 409 -> result("تغيّر الدرس أو حالته. لم يُحفظ التعديل؛ افتح بياناته من جديد.")
                    else -> result("لم تُؤكّد النتيجة. تحقّق من القائمة قبل البدء بمحاولة جديدة.")
                }
            } catch (_: Exception) {
                result("لم تُؤكّد النتيجة. تحقّق من القائمة قبل البدء بمحاولة جديدة.")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(original, mode, failed) {
        if (mode != LessonEditMode.MOVE || failed || targets != null) return@LaunchedEffect
        try {
            targets = withContext(Dispatchers.IO) { fetchLessonDestinations(api) }
                .filterNot { it.categoryId == original.categoryId && it.subcategoryId == original.subcategoryId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AdminAccessRejected) {
            rejected()
        } catch (_: Exception) {
            failed = true
        }
    }

    fun submit(edit: LessonMetadataEdit) {
        if (busy || confirm != edit) return
        busy = true
        scope.launch {
            var sent = false
            try {
                val command = withContext(Dispatchers.IO) { prepareLessonMetadataCommand(original, edit, api) }
                sent = true
                write.submit(command)
                result("قَبِل الخادم التعديل. تُحدّث القائمة الآن.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AdminAccessRejected) {
                rejected()
            } catch (_: LessonMetadataStale) {
                result("تغيّر الدرس أو القسم منذ فتحه. لم يُرسل تعديل؛ افتح النسخة المحدّثة.")
            } catch (_: Exception) {
                result(if (sent) "لم تُؤكّد النتيجة. تحقّق من القائمة المحدّثة قبل إعادة المحاولة."
                       else "تعذّر التحقّق من الدرس؛ لم يُرسل تعديل.")
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(if (mode == LessonEditMode.FEATURE) "مختارات المنبر" else if (confirm == null) "تعديل بيانات الدرس" else "تأكيد التعديل") },
        text = {
            Column(
                modifier = if (mode == LessonEditMode.FEATURE) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text((if (mode == LessonEditMode.FEATURE) featuredFrame?.title ?: original.title else original.title)
                    .take(MaxLessonTitle), style = MaterialTheme.typography.titleMedium)
                when (val edit = confirm) {
                    is LessonMetadataEdit.Rename -> Text("العنوان الجديد: ${edit.title}")
                    is LessonMetadataEdit.Move -> Text("سينقل الدرس إلى ${edit.to.categoryName} ← ${edit.to.subcategoryName}. لن يُعاد ترتيب دروس الوجهة.")
                    null -> when (mode) {
                        LessonEditMode.PICK -> {
                            TextButton(onClick = { mode = LessonEditMode.TITLE }, modifier = Modifier.heightIn(min = 48.dp)) { Text("تعديل العنوان") }
                            TextButton(onClick = { mode = LessonEditMode.MOVE }, modifier = Modifier.heightIn(min = 48.dp)) { Text("نقل إلى قسم آخر") }
                            if (order != null) {
                                TextButton(onClick = { orderOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("ترتيب الدرس في هذا الفرع")
                                }
                            }
                            if (guardedFeature?.available() == true) {
                                TextButton(onClick = { mode = LessonEditMode.FEATURE }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("إدارة ظهور الدرس في المختارات")
                                }
                            }
                        }
                        LessonEditMode.TITLE -> OutlinedTextField(
                            value = title,
                            onValueChange = { if (it.length <= MaxLessonTitle) title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("العنوان الجديد") },
                        )
                        LessonEditMode.FEATURE -> when {
                            featuredError -> Text("تعذّر التحقّق من حالة الدرس؛ أعد فتحه من القائمة.")
                            busy -> Unit
                            featuredFrame == null -> Text("جارٍ التحقّق من حالة الدرس…")
                            guardedFeature?.canSetDeadline() == true -> {
                                val frame = checkNotNull(featuredFrame)
                                FeatureChoices(frame, featureChoice, selectedDeadline) { choice ->
                                    selectedDeadline = choice.durationMs?.let { System.currentTimeMillis() + it }
                                    featureChoice = choice
                                }
                            }
                            featuredFrame?.featured == true -> Text(
                                "سيُزال تمييز هذا الدرس فقط. يبقى الدرس منشورًا في قسمه.")
                            else -> Text("سيظهر تمييز هذا الدرس في مختارات المنبر حتى تُزيله. لن تتغيّر بياناته أو ملفه.")
                        }
                        LessonEditMode.MOVE -> {
                            val available = targets
                            when {
                                failed -> {
                                    Text("تعذّر تحميل الأقسام.")
                                    TextButton(onClick = { failed = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("إعادة المحاولة") }
                                }
                                available == null -> Text("جارٍ جلب الأقسام…")
                                available.isEmpty() -> Text("لا توجد أقسام وجهة متاحة.")
                                else -> LazyColumn(Modifier.heightIn(max = 230.dp)) {
                                    items(available) { choice ->
                                        TextButton(
                                            onClick = { target = choice },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        ) {
                                            Text((if (target == choice) "اختير: " else "") + "${choice.categoryName} ← ${choice.subcategoryName}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) Text("جارٍ التحقّق من آخر نسخة وإرسال التعديل…")
            }
        },
        confirmButton = {
            val editing = when (mode) {
                LessonEditMode.TITLE -> title.trim()
                    .takeIf { it.isNotEmpty() && it != original.title && it.none(Char::isISOControl) }
                    ?.let { LessonMetadataEdit.Rename(it) }
                LessonEditMode.MOVE -> target?.let { LessonMetadataEdit.Move(it) }
                LessonEditMode.PICK, LessonEditMode.FEATURE -> null
            }
            val confirmed = confirm
            if (mode == LessonEditMode.FEATURE) {
                val frame = featuredFrame
                Button(onClick = { frame?.let(::submitFeature) },
                    enabled = !busy && frame != null && featureChoice != null && guardedFeature?.available() == true,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (featureChoice == FeatureChoice.Remove) "أؤكّد إزالة التمييز" else "أؤكّد التمييز")
                }
            } else if (confirmed != null) {
                Button(onClick = { submit(confirmed) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("أؤكد التعديل")
                }
            } else {
                Button(onClick = { confirm = editing }, enabled = editing != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("مراجعة التعديل")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirm != null) confirm = null else dismiss() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (confirm != null) "رجوع" else "إلغاء") }
        },
    )
}


@Composable
private fun FeatureChoices(
    frame: GuardedLessonFeatureClient.Review,
    selected: FeatureChoice?,
    selectedDeadline: Long?,
    choose: (FeatureChoice) -> Unit,
) {
    Text(when {
        !frame.featured -> "الدرس غير مميّز حاليًا."
        frame.featuredUntilMs == 0L -> "الدرس مميّز دون موعد انتهاء."
        else -> "موعد انتهاء التمييز المسجّل: ${describeDeadline(frame.featuredUntilMs)}."
    })
    val choices = buildList {
        if (!frame.featured || frame.featuredUntilMs > 0L) add(FeatureChoice.Permanent)
        add(FeatureChoice.Day)
        add(FeatureChoice.Week)
        if (frame.featured) add(FeatureChoice.Remove)
    }
    Column(Modifier.selectableGroup()) {
        for (choice in choices) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = choice == selected, onClick = { choose(choice) }, role = Role.RadioButton,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RadioButton(selected = choice == selected, onClick = null)
                Text(choice.label)
            }
        }
    }
    if (selectedDeadline != null) Text("موعد الانتهاء: ${describeDeadline(selectedDeadline)} (بتوقيت جهازك).")
    Text("تغيير التمييز لا يغيّر الدرس أو مكانه في قسمه.")
}
