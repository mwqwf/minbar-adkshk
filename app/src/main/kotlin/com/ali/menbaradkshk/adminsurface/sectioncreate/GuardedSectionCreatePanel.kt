package com.ali.menbaradkshk.adminsurface.sectioncreate

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuardedSectionCreatePanel(access: GuardedSectionCreateAccess, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(access) { mutableStateOf("") }
    var nested by remember(access) { mutableStateOf(false) }
    var parent by remember(access) { mutableStateOf<SectionCreateParent?>(null) }
    var parents by remember(access) { mutableStateOf<SectionCreateParents?>(null) }
    var parentMenu by remember(access) { mutableStateOf(false) }
    var tryParents by remember(access) { mutableStateOf(0) }
    var parentFailure by remember(access) { mutableStateOf(false) }
    var reviewed by remember(access) { mutableStateOf<SectionCreateIntent?>(null) }
    var uncertain by remember(access) { mutableStateOf<SectionCreateIntent?>(null) }
    var busy by remember(access) { mutableStateOf(false) }
    var notice by remember(access) { mutableStateOf<String?>(null) }
    BackHandler { if (!busy) onBack() }
    LaunchedEffect(access, nested, tryParents) {
        if (!nested || !access.available()) return@LaunchedEffect
        parents = null
        parent = null
        parentFailure = false
        try { parents = access.parents() }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { if (access.available()) parentFailure = true }
    }
    if (!access.available()) return
    val frozen = uncertain
    val valid = normalizedSectionCreationName(name)
    val mayReview = !busy && (frozen != null || valid != null && (!nested || parent != null))
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("إنشاء قسم", style = MaterialTheme.typography.headlineSmall)
        Text("يُضاف قسم فارغ إلى دليل الدروس، من دون تعديل أي قسم أو درس قائم.")
        if (frozen == null) {
            TextButton(enabled = !busy, onClick = {
                nested = !nested
                parent = null
                notice = null
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (nested) "إنشاء قسم رئيسي بدلًا من قسم فرعي" else "إنشاء قسم فرعي بدلًا من قسم رئيسي")
            }
            OutlinedTextField(name, { name = it.take(160) }, Modifier.fillMaxWidth(), enabled = !busy,
                singleLine = true, label = { Text(if (nested) "اسم القسم الفرعي" else "اسم القسم الرئيسي") })
            if (nested) {
                val picks = parents
                if (picks == null && !parentFailure) Text("جارٍ تحميل الأقسام الرئيسية…")
                if (picks != null) {
                    TextButton(enabled = !busy && picks.items.isNotEmpty(), onClick = { parentMenu = true },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(parent?.name ?: "اختر القسم الرئيسي") }
                    DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }) {
                        picks.items.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = {
                                parent = item
                                parentMenu = false
                            }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    if (picks.items.isEmpty()) Text("لا توجد أقسام رئيسية نشطة.")
                    if (picks.truncated) Text("تظهر أول مئة نتيجة وفق ترتيب الدليل.")
                }
                if (parentFailure) Text("تعذّر تحميل الأقسام الرئيسية.")
                TextButton(enabled = !busy, onClick = { tryParents++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("حدّث الأقسام الرئيسية")
                }
            }
        } else {
            Text("لم تصل نتيجة مؤكدة. يمكنك المحاولة مرة أخرى بالتفاصيل نفسها فقط.")
            Text(frozen.name)
            frozen.parent?.let { Text("ضمن: ${it.name}") }
        }
        Button(enabled = mayReview, modifier = Modifier.heightIn(min = 48.dp), onClick = {
            if (!mayReview || !access.available()) return@Button
            reviewed = frozen ?: valid?.let { SectionCreateIntent(UUID.randomUUID().toString(), it, parent.takeIf { nested }) }
        }) { Text("راجع قبل الإنشاء") }
        notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("رجوع") }
    }
    val confirmed = reviewed
    if (confirmed != null) AlertDialog(
        onDismissRequest = { if (!busy) reviewed = null },
        title = { Text("تأكيد إنشاء القسم") },
        text = { Text("سيُضاف «${confirmed.name}»" +
            (confirmed.parent?.let { " ضمن «${it.name}»." } ?: " كقسم رئيسي.")) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (busy || !access.available() || reviewed != confirmed) return@TextButton
                reviewed = null
                uncertain = confirmed
                busy = true
                scope.launch {
                    try {
                        val receipt = access.create(confirmed)
                        uncertain = null
                        name = ""
                        notice = "أُنشئ القسم «${receipt.name}»."
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: SectionCreateConflict) {
                        uncertain = null
                        notice = "الاسم موجود أو تغيّر القسم الرئيسي. راجع الأقسام ثم حاول مجددًا."
                        if (nested) tryParents++
                    } catch (_: Exception) {
                        notice = "لم يمكن تأكيد النتيجة. راجع الطلب نفسه قبل إعادة المحاولة."
                    } finally { busy = false }
                }
            }) { Text("أكّد الإنشاء") }
        },
        dismissButton = { TextButton(onClick = { reviewed = null }) { Text("إلغاء") } },
    )
}
