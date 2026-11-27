package com.ali.menbaradkshk.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LessonTranscript
import com.ali.menbaradkshk.data.TranscriptRepository

/**
 * 📖 «النص المشروح» في شاشة التشغيل: يعرض المتن/المقطع الأصلي الذي تشرحه
 * الصوتية (نصاً وصور صفحات) بعد اعتماده من المشرفين.
 */
@Composable
fun TranscriptSection(vm: AppViewModel, lesson: Lesson) {
    var loading by remember(lesson.id) { mutableStateOf(true) }
    var transcript by remember(lesson.id) { mutableStateOf<LessonTranscript?>(null) }
    // تعذّر الجلب ولا نسخة محفوظة: «لم يُجلب بعد» لا «لا نص لهذا الدرس» —
    // الخلط بينهما كان يعرض دعوة المساهمة لدرسٍ نصُّه موجود ولم يصل فقط.
    var unreachable by remember(lesson.id) { mutableStateOf(false) }
    var retry by remember(lesson.id) { mutableIntStateOf(0) }
    // محفوظة لا مُتذكَّرة فقط: النشاط بلا `configChanges`، فتدوير الشاشة كان
    // يُغلق الصفحة المكبَّرة على من يدقّق فيها — نفس علاج `zoomed` في الأذكار.
    var viewingImage by rememberSaveable(lesson.id) { mutableStateOf("") }

    LaunchedEffect(lesson.id, retry) {
        loading = true
        runCatching { vm.transcripts.fetch(lesson.id) }
            .onSuccess {
                transcript = it
                unreachable = false
            }
            .onFailure {
                transcript = null
                unreachable = true
            }
        loading = false
    }

    if (viewingImage.isNotEmpty()) {
        // ⚠️ كان الحوار بعرض النافذة الافتراضي وبلا تمرير، فتُصغَّر الصفحة
        // الطويلة لتدخل الشاشة كاملةً — أي أن «التكبير» يعرضها أصغر ممّا في
        // البطاقة. الآن بعرض الشاشة كاملاً مع تمرير رأسي (كما في حوار الأذكار).
        Dialog(
            onDismissRequest = { viewingImage = "" },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(rememberScrollState())
                    .clickable { viewingImage = "" },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = remember(viewingImage) { vm.transcripts.imageModel(viewingImage) },
                    contentDescription = "صورة صفحة الكتاب",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
                    // 🔠 زرّا الخطّ في زاوية النصّ — ظاهران دائماً.
                    //
                    // **لماذا؟** القرص بالإصبعين معرفةٌ تقنيّة: من لا يعرف أنّ
                    // الشاشة تُقرَص لن يكتشف ذلك أبداً، وهو غالبُ من يحتاج
                    // التكبير (كبار السنّ). والزرّ يُرى بلا أن يُعلَّم أحد شيئاً.
                    TranscriptFontButtons(vm)
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
                        // النص كاملاً بلا نقر ولا «قراءة المزيد» — تمرير الصفحة
                        // الطبيعي يتكفّل بالطول (قرار المستخدم الصريح).
                        SelectionContainer {
                            Text(
                                t.text,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = 30.sp,
                                // التحديد اليدوي يبقى متاحاً لمن أراد جزءاً
                                // بعينه، والضغطة المطوّلة تنسخ النصّ كلّه
                                // بخطوة واحدة — وهي ما يريده أغلب الناس.
                                modifier = Modifier.copyable(t.text),
                            )
                        }
                    }
                    // صفحات الكتاب بعرض البطاقة كاملاً وبنسبة أبعادها الطبيعية
                    // (لا قصّ ولا تشويه) — تُقرأ مباشرة دون نقر، والنقر يفتحها
                    // مكبَّرة أكثر للتدقيق.
                    if (t.imageUrls.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        t.imageUrls.forEachIndexed { i, url ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            // الصفحة المنزَّلة مع الدرس تُقرأ من القرص بلا إنترنت،
                            // وإلا فالرابط عبر كاش الصور المعتاد.
                            val model = remember(url) { vm.transcripts.imageModel(url) }
                            AsyncImage(
                                model = model,
                                contentDescription = "صفحة الكتاب ${i + 1}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewingImage = url },
                            )
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

        // بلا اتصال ولم يُجلب النص بعد: لا ندعو للمساهمة كأن الدرس بلا نص —
        // فقد يكون له نصٌّ معتمد لم يصل الجهاز أصلاً.
        unreachable -> Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "النص المشروح غير متاح الآن",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "تعذّر الاتصال — إن كان لهذا الدرس نص فسيظهر عند عودة الاتصال.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { retry++ }) {
                    Text("إعادة", fontSize = 13.sp)
                }
            }
        }

        else -> Text(
            "لا يوجد نص مشروح لهذا الدرس بعد.",
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 🔠 «أ+» و«أ−» فوق النصّ المشروح — تكبير الخطّ بزرٍّ يُرى لا بإيماءة تُتعلَّم.
 *
 * الحجم المضبوط هنا هو حجم خطّ التطبيق العام نفسه (`fontScale`) لا مقياساً
 * ثالثاً يخصّ هذا القسم: ثلاثة مقاييس لشيء واحد تُربك المستخدم، ومن كبّر
 * النصّ هنا فالغالب أنّه يريد التطبيق كلّه أكبر. والضبط نفسه في الإعدادات
 * باقٍ كما هو لمن يصل إليه.
 *
 * والزرّ يعطّل نفسه عند الطرف — الحدّان هما حدّا `fontScale` نفسها في المخزن،
 * فلا يضغط المستخدم على زرٍّ لا يفعل شيئاً.
 */
@Composable
private fun TranscriptFontButtons(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val scale = remember(revision) { vm.store.fontScale() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "حجم الخطّ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        FontStepButton(
            label = "أ−",
            enabled = scale > FONT_SCALE_MIN,
            onStep = { vm.store.setFontScale(vm.store.fontScale() - FONT_SCALE_STEP) },
            size = 48,
            fontSize = 22,
        )
        Spacer(Modifier.width(6.dp))
        FontStepButton(
            label = "أ+",
            enabled = scale < FONT_SCALE_MAX,
            onStep = { vm.store.setFontScale(vm.store.fontScale() + FONT_SCALE_STEP) },
            size = 48,
            fontSize = 22,
        )
    }
}

/// حدّا حجم الخطّ العام وخطوته — مطابقة لما يحصره `LocalStore.setFontScale`
/// ولمنزلق الإعدادات، كي يتوقّف الزرّ عند الطرف نفسه الذي يتوقّف عنده المنزلق.
private const val FONT_SCALE_MIN = 0.8f
private const val FONT_SCALE_MAX = 1.4f
private const val FONT_SCALE_STEP = 0.05f
