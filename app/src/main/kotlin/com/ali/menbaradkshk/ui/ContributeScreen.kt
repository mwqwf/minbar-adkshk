@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Category
import com.ali.menbaradkshk.data.Subcategory
import com.ali.menbaradkshk.data.SubmissionDraft
import com.ali.menbaradkshk.data.SubmissionRepository
import com.ali.menbaradkshk.util.AudioMerger
import com.ali.menbaradkshk.util.Mp3FormatException
import com.ali.menbaradkshk.util.smartTitleFromFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PickedFile(val uri: Uri, val name: String)

/// 📤 «شارك درساً» — النقل الأمين لـ contribute_screen.dart:
/// عدّة ملفات MP3 تُدمج محلياً بالترتيب المختار في درس واحد قبل الرفع.
@Composable
fun ContributeScreen(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val content by vm.content.state.collectAsState()

    val files = remember { mutableStateListOf<PickedFile>() }
    var title by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(vm.store.submitterName()) }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Category?>(null) }
    var subcategory by remember { mutableStateOf<Subcategory?>(null) }
    var rightsConfirmed by remember { mutableStateOf(false) }
    var policyAccepted by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var policyDialog by remember { mutableStateOf(false) }
    var successDialog by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            PickedFile(uri, displayNameOf(context, uri))
        }
        val existing = files.map { it.uri }.toSet()
        val combined = files + picked.filter { it.uri !in existing }

        // الدمج المباشر لا يصح إلا لملفات MP3.
        if (combined.size > 1) {
            val bad = combined.firstOrNull { !AudioMerger.isMp3(it.name) }
            if (bad != null) {
                error = "لدمج عدة ملفات يجب أن تكون جميعها MP3 — «${bad.name}» ليس كذلك."
                return@rememberLauncherForActivityResult
            }
        }
        error = if (combined.size > AudioMerger.maxFiles) {
            "الحد الأقصى ${AudioMerger.maxFiles} ملفات للدرس الواحد — أُبقي أولها."
        } else {
            ""
        }
        files.clear()
        files.addAll(combined.take(AudioMerger.maxFiles))
        if (title.trim().isEmpty() && files.isNotEmpty()) {
            title = smartTitleFromFileName(files.first().name)
        }
    }

    val categories = content.categories
    val subsForCategory = category?.let { chosen ->
        content.subcategories.filter { it.categoryId == chosen.id }
    }.orEmpty()

    val canSubmit = !submitting &&
        title.trim().length >= 3 &&
        category != null &&
        subcategory != null &&
        files.isNotEmpty() &&
        name.trim().isNotEmpty() &&
        rightsConfirmed &&
        policyAccepted

    fun submit() {
        if (!canSubmit) {
            error = "أكمل الحقول المطلوبة، ثم أكّد حقك في النشر والموافقة على ضوابط المحتوى."
            return
        }
        val cat = category ?: return
        val sub = subcategory ?: return
        scope.launch {
            submitting = true
            merging = false
            progress = 0
            error = ""
            var mergedTemp: File? = null
            try {
                // ملف واحد يُرفع كما هو؛ أكثر يُدمج محلياً أولاً ثم يُرفع الناتج.
                val (uploadUri, uploadName) = if (files.size == 1) {
                    files.single().uri to files.single().name
                } else {
                    var total = 0L
                    for (file in files) {
                        total += context.contentResolver.openAssetFileDescriptor(file.uri, "r")
                            ?.use { it.length } ?: 0L
                    }
                    if (total > SubmissionRepository.MAX_FILE_BYTES) error("file_too_large")
                    merging = true
                    val timestamp = System.currentTimeMillis()
                    mergedTemp = withContext(Dispatchers.IO) {
                        val cache = File(context.cacheDir, "merge_$timestamp").apply { mkdirs() }
                        val locals = files.mapIndexed { index, picked ->
                            File(cache, "part_$index.mp3").also { target ->
                                context.contentResolver.openInputStream(picked.uri)!!.use { input ->
                                    target.outputStream().use(input::copyTo)
                                }
                            }
                        }
                        AudioMerger.mergeMp3(locals, File(cache, "merged_$timestamp.mp3").absolutePath)
                            .also { locals.forEach(File::delete) }
                    }
                    merging = false
                    Uri.fromFile(mergedTemp) to "merged_$timestamp.mp3"
                }
                vm.submissions.submit(
                    SubmissionDraft(
                        audioUri = uploadUri,
                        fileName = uploadName,
                        title = title,
                        category = cat,
                        subcategory = sub,
                        submitterName = name,
                        note = note,
                        rightsConfirmed = rightsConfirmed,
                        contentPolicyAccepted = policyAccepted,
                    ),
                ) { progress = it }
                successDialog = true
            } catch (failure: Throwable) {
                merging = false
                error = when {
                    failure is Mp3FormatException -> "تعذّر دمج الملفات — تأكد أنها ملفات MP3 سليمة."
                    failure.message?.contains("file_too_large") == true ->
                        "الحجم الكلي أكبر من الحدّ المسموح (100MB)."
                    else -> "تعذّر إرسال المساهمة. تحقق من اتصالك وحاول مجدداً."
                }
            } finally {
                mergedTemp?.delete()
                submitting = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                "مساهمتك تُعرض على المشرفين قبل النشر، ويصلك إشعار بالنتيجة. يُنشر الدرس ضمن أقسام التطبيق الحقيقية.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { picker.launch(arrayOf("audio/*")) },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (files.isEmpty()) Icons.Filled.Audiotrack else Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = null,
            )
            Text(
                if (files.isEmpty()) " اختر ملفاً صوتياً (أو عدّة ملفات لدمجها)"
                else " إضافة ملفات أخرى (${files.size}/${AudioMerger.maxFiles})",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (files.size > 1) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
            ) {
                Text(
                    "ستُدمج ${files.size} ملفات بالترتيب أدناه في درس واحد متصل — استخدم الأسهم لإعادة الترتيب.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        files.forEachIndexed { index, file ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(
                    enabled = !submitting,
                    onClick = { files.removeAt(index) },
                ) { Icon(Icons.Filled.Close, "إزالة", Modifier.size(18.dp)) }
                if (files.size > 1) {
                    IconButton(
                        enabled = index > 0 && !submitting,
                        onClick = {
                            val item = files.removeAt(index)
                            files.add(index - 1, item)
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowUp, "أعلى") }
                    IconButton(
                        enabled = index < files.lastIndex && !submitting,
                        onClick = {
                            val item = files.removeAt(index)
                            files.add(index + 1, item)
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "أسفل") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان الدرس") },
            enabled = !submitting,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(50) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسمك (يظهر للمشرفين)") },
            enabled = !submitting,
        )
        Spacer(Modifier.height(8.dp))
        var categoryMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
            OutlinedTextField(
                value = category?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text("القسم الرئيسي") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenu) },
                enabled = !submitting,
            )
            ExposedDropdownMenu(
                expanded = categoryMenu,
                onDismissRequest = { categoryMenu = false },
            ) {
                categories.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            category = item
                            subcategory = null
                            categoryMenu = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        var subcategoryMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = subcategoryMenu, onExpandedChange = { subcategoryMenu = it }) {
            OutlinedTextField(
                value = subcategory?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text("القسم الفرعي") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subcategoryMenu) },
                enabled = category != null && !submitting,
            )
            ExposedDropdownMenu(
                expanded = subcategoryMenu,
                onDismissRequest = { subcategoryMenu = false },
            ) {
                subsForCategory.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            subcategory = item
                            subcategoryMenu = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(300) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ملاحظة للمشرفين (اختياري)") },
            minLines = 2,
            enabled = !submitting,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rightsConfirmed, { rightsConfirmed = it }, enabled = !submitting)
            Column {
                Text("أملك حق مشاركة هذا التسجيل")
                Text(
                    "أؤكد أن التسجيل لي أو لدي إذن صريح بنشره في منبر.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(policyAccepted, { policyAccepted = it }, enabled = !submitting)
            Column {
                Text("أوافق على ضوابط المحتوى والمراجعة")
                Text(
                    "لا حقوق منتهكة، ولا كراهية أو تحريض أو محتوى غير قانوني، ويحق للمشرفين الرفض أو التعديل قبل النشر.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { policyDialog = true }) {
                Icon(Icons.Filled.Policy, null)
                Text(" قراءة الضوابط")
            }
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (submitting) {
            if (merging || progress <= 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (merging) "جارٍ دمج الملفات في مقطع واحد…" else "جارٍ الرفع… $progress%",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }
        FilledTonalButton(
            onClick = ::submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null)
            Text(" إرسال للمراجعة")
        }
    }

    if (policyDialog) {
        AlertDialog(
            onDismissRequest = { policyDialog = false },
            title = { Text("ضوابط مشاركة الدروس") },
            text = {
                Text(
                    "يُمنع إرسال تسجيل لا تملك حق نشره، أو يتضمن كراهية أو تحريضاً أو تهديداً أو انتهاك خصوصية أو نشاطاً غير قانوني. كل مساهمة تبقى معلّقة حتى يراجعها المشرفون، وقد تُعدّل أو تُرفض مع بيان السبب. عند اكتشاف مخالفة يمكن حذف المساهمة وملفها نهائياً.",
                )
            },
            confirmButton = {
                TextButton(onClick = { policyDialog = false }) { Text("فهمت") }
            },
        )
    }
    if (successDialog) {
        AlertDialog(
            onDismissRequest = { successDialog = false; vm.back() },
            icon = { Icon(Icons.Filled.MarkEmailRead, null, tint = GreenBrand, modifier = Modifier.size(40.dp)) },
            title = { Text("وصل طلبك للمشرفين") },
            text = {
                Text(
                    "سيراجع المشرفون مساهمتك، ويصلك إشعار بالنتيجة: نُشرت كما هي، أو نُشرت بعد تحسينها، أو اعتذار مع السبب.\nتابع حالتها من «مساهماتي».",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                TextButton(onClick = { successDialog = false; vm.back() }) { Text("حسناً") }
            },
        )
    }
}

private fun displayNameOf(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "lesson_audio.mp3"
}
