package com.ali.menbaradkshk.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LessonTranscript
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.data.TranscriptDraft
import com.ali.menbaradkshk.data.TranscriptRepository
import kotlinx.coroutines.launch

/**
 * 📖 «النص المشروح» في شاشة التشغيل: يعرض المتن/المقطع الأصلي الذي تشرحه
 * الصوتية (نصاً وصور صفحات) بعد اعتماده من المشرفين، ويتيح للمستمع
 * المساهمة بالنص أو اقتراح تصحيحه — تمر المساهمة بمراجعة المشرفين قبل
 * الظهور (نفس دورة «شارك درساً»).
 */
@Composable
fun TranscriptSection(lesson: Lesson) {
    val context = LocalContext.current
    val repo = remember { TranscriptRepository.get(context) }

    var loading by remember(lesson.id) { mutableStateOf(true) }
    var transcript by remember(lesson.id) { mutableStateOf<LessonTranscript?>(null) }
    var contributeSheet by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf("") }
    var expanded by remember(lesson.id) { mutableStateOf(false) }

    LaunchedEffect(lesson.id) {
        loading = true
        transcript = runCatching { repo.fetch(lesson.id) }.getOrNull()
        loading = false
    }

    if (viewingImage.isNotEmpty()) {
        Dialog(onDismissRequest = { viewingImage = "" }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .clickable { viewingImage = "" },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = viewingImage,
                    contentDescription = "صورة صفحة الكتاب",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (contributeSheet) {
        TranscriptContributeSheet(
            lesson = lesson,
            existing = transcript,
            onDismiss = { contributeSheet = false },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = GreenBrand,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("النص المشروح", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        if (transcript != null) {
            TextButton(onClick = { contributeSheet = true }) {
                Text("اقتراح تصحيح", fontSize = 13.sp)
            }
        }
    }

    when {
        loading -> Box(
            Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = GreenBrand,
            )
        }

        transcript != null -> {
            val t = transcript!!
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (t.bookTitle.isNotBlank()) {
                        Text(
                            t.bookTitle,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = GreenBrand,
                        )
                    }
                    if (t.sourceRef.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t.sourceRef,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (t.text.isNotBlank()) {
                        if (t.bookTitle.isNotBlank() || t.sourceRef.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                        }
                        // نص قابل للتحديد والنسخ، بخط قراءة مريح وسطر متباعد.
                        SelectionContainer {
                            Text(
                                t.text,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = 30.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (t.text.length > 320) {
                            TextButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Text(if (expanded) "طيّ النص" else "قراءة المزيد")
                            }
                        }
                    }
                    if (t.imageUrls.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(t.imageUrls.size) { i ->
                                AsyncImage(
                                    model = t.imageUrls[i],
                                    contentDescription = "صفحة الكتاب ${i + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable { viewingImage = t.imageUrls[i] },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = null,
                            tint = GreenBrand,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            buildString {
                                append("روجع واعتُمد من المشرفين")
                                if (t.contributorName.isNotBlank()) {
                                    append(" • بمساهمة ${t.contributorName}")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        else -> Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "لم يُوثَّق النص الذي تشرحه هذه الصوتية بعد.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { contributeSheet = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(" ساهم بالنص أو صورة الصفحة")
                }
            }
        }
    }
}

/**
 * نموذج المساهمة: نص المقطع من الكتاب و/أو صور صفحاته (حتى 4)، مع اسم
 * الكتاب ونطاق المقطع وملاحظة للمشرفين. يُرسل للمراجعة ولا يظهر قبل
 * الاعتماد. عند «اقتراح تصحيح» تُملأ الحقول بالنص المعتمد الحالي.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptContributeSheet(
    lesson: Lesson,
    existing: LessonTranscript?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { TranscriptRepository.get(context) }
    val store = remember { LocalStore.get(context) }

    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var bookTitle by remember { mutableStateOf(existing?.bookTitle.orEmpty()) }
    var sourceRef by remember { mutableStateOf(existing?.sourceRef.orEmpty()) }
    var note by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(store.submitterName()) }
    val images = remember { mutableStateListOf<Uri>() }
    var sending by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        uris.forEach { uri ->
            if (images.size < TranscriptRepository.MAX_IMAGES) images.add(uri)
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!sending) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (existing == null) "المساهمة بالنص المشروح" else "اقتراح تصحيح النص",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "«${lesson.displayTitle}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "انقل النص الأصلي من الكتاب الذي تشرحه هذه الصوتية (لا تفريغ " +
                    "كلام الشيخ)، أو أرفق صورة واضحة للصفحة — يراجعها المشرفون " +
                    "قبل النشر.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(14.dp))

            if (done) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = null,
                        tint = GreenBrand,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "وصلت مساهمتك وستُراجع قريباً.\nتابع حالتها من «مساهماتي».",
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDismiss) { Text("حسناً") }
                }
                return@Column
            }

            OutlinedTextField(
                value = bookTitle,
                onValueChange = { if (it.length <= 200) bookTitle = it },
                label = { Text("اسم الكتاب/المتن (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sourceRef,
                onValueChange = { if (it.length <= 300) sourceRef = it },
                label = { Text("المقطع: من … إلى … (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= TranscriptRepository.MAX_TEXT_CHARS) text = it },
                label = { Text("النص الأصلي المشروح") },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "صور صفحات الكتاب (${images.size}/${TranscriptRepository.MAX_IMAGES})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !sending && images.size < TranscriptRepository.MAX_IMAGES,
                ) {
                    Icon(
                        Icons.Filled.AddPhotoAlternate,
                        contentDescription = "إرفاق صورة من الكتاب",
                        tint = GreenBrand,
                    )
                }
            }
            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images.size) { i ->
                        Box(Modifier.size(88.dp)) {
                            AsyncImage(
                                model = images[i],
                                contentDescription = "صورة مرفقة ${i + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(10.dp),
                                    ),
                            )
                            IconButton(
                                onClick = { images.removeAt(i) },
                                enabled = !sending,
                                modifier = Modifier
                                    .size(22.dp)
                                    .align(Alignment.TopEnd)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "إزالة",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 50) name = it },
                label = { Text("اسمك (يظهر مع النص عند الاعتماد)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 300) note = it },
                label = { Text("ملاحظة للمشرفين (اختياري)") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    sending = true
                    message = ""
                    scope.launch {
                        runCatching {
                            repo.submit(
                                TranscriptDraft(
                                    lessonId = lesson.id,
                                    text = text,
                                    bookTitle = bookTitle,
                                    sourceRef = sourceRef,
                                    note = note,
                                    submitterName = name,
                                    images = images.toList(),
                                ),
                            ) { progress = it }
                        }.onSuccess {
                            done = true
                        }.onFailure {
                            message = it.message
                                ?: "تعذّر الإرسال. تأكد من الاتصال وحاول مجدداً."
                        }
                        sending = false
                    }
                },
                enabled = !sending && (text.trim().length >= 10 || images.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (images.isNotEmpty() && progress in 1..99) {
                            "جارٍ رفع الصور… $progress%"
                        } else {
                            "جارٍ الإرسال…"
                        },
                    )
                } else {
                    Text("إرسال للمراجعة")
                }
            }
        }
    }
}
