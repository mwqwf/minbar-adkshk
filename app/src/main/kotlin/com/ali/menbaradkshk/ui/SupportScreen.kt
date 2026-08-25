@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ali.menbaradkshk.data.SupportKind
import com.ali.menbaradkshk.data.SupportRepository
import com.ali.menbaradkshk.data.SupportStore
import com.ali.menbaradkshk.data.SupportThread
import java.io.File

/**
 * 📮 «راسِل المطوّر» — قناة بين المستخدم ومالك المشروع وحده.
 *
 * **لماذا سؤال وبطاقات لا صندوق فارغ؟** الصندوق الفارغ يشلّ من لا يكتب
 * العربية بطلاقة: لا يدري بماذا يبدأ ولا ما المطلوب منه. والسؤال الواحد
 * «بمَ نساعدك؟» مع خمس إجابات مكتوبة ومصوّرة يجعل أوّل خطوة **نقرةً** لا
 * كتابة، وتُصنَّف الرسالة عند المطوّر من نفسها.
 *
 * الشاشة كلّها داخل `Dialog` ملء الشاشة لا مساراً في التنقّل: الميزة مستقلّة
 * تماماً ولا تشارك أحداً حالةً، فبقاؤها في ملفّاتها يُبقي التنقّل العام كما هو.
 */
@Composable
fun SupportDialog(
    onClose: () -> Unit,
    onOpenContribute: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SupportFlow(onClose = onClose, onOpenContribute = onOpenContribute)
    }
}

private sealed interface SupportStep {
    data object Entry : SupportStep
    data class Form(val kind: String) : SupportStep
    data class Conversation(val thread: SupportThread) : SupportStep
    data object Sent : SupportStep
}

@Composable
private fun SupportFlow(onClose: () -> Unit, onOpenContribute: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SupportRepository.get(context) }
    var step by remember { mutableStateOf<SupportStep>(SupportStep.Entry) }
    // ⚠️ `remember` على الدفق نفسه: بلاه يُنشأ دفقٌ جديد مع كل إعادة تركيب
    // فيُلغى مستمع Firestore ويُسجَّل غيره في كل نبضة — قراءات زائدة على
    // إنترنت ضعيف بلا فائدة.
    val threadsFlow = remember { repository.myThreads() }
    val threads by threadsFlow.collectAsState(initial = emptyList())

    when (val current = step) {
        SupportStep.Entry -> SupportEntry(
            threads = threads,
            onPick = { kind ->
                // ⚠️ «عندي درس» لا يفتح استمارة: مسار «شارك درساً» موجود
                // ومكتمل، وبناء مسار رفع ثانٍ يشتّت المحتوى ويضاعف العمل.
                // ونرفع علم «بلا قسم» قبل فتحه: من دخل من هنا قال أصلاً إنّه
                // لا يعرف القسم، فلا يُردّ إلى منتقي أقسام يحيّره من جديد.
                if (kind == SupportKind.LESSON_HELP) {
                    ContributePrefill.requestWithoutCategory()
                    onOpenContribute()
                }
                else step = SupportStep.Form(kind)
            },
            onOpenThread = { step = SupportStep.Conversation(it) },
            onClose = onClose,
        )
        is SupportStep.Form -> SupportForm(
            kind = current.kind,
            onBack = { step = SupportStep.Entry },
            onSent = { step = SupportStep.Sent },
        )
        is SupportStep.Conversation -> {
            // نأخذ أحدث نسخة من المحادثة كي يظهر ردّ المطوّر فور وصوله.
            val live = threads.firstOrNull { it.id == current.thread.id } ?: current.thread
            SupportThreadScreen(thread = live, onBack = { step = SupportStep.Entry })
        }
        SupportStep.Sent -> SupportSent(onDone = { step = SupportStep.Entry })
    }
}

// ─── شاشة الدخول ────────────────────────────────────────────────

private data class SupportOption(val kind: String, val emoji: String, val label: String)

private val supportOptions = listOf(
    SupportOption(SupportKind.SUGGESTION, "💡", "عندي اقتراح لتحسين التطبيق"),
    SupportOption(SupportKind.BUG, "⚠️", "شيءٌ لا يعمل عندي"),
    SupportOption(SupportKind.LESSON_HELP, "🎙️", "عندي درس ولا أعرف قسمه"),
    SupportOption(SupportKind.IDEA, "✍️", "ملاحظة أو فكرة"),
    SupportOption(SupportKind.SUPERVISION, "🛡️", "أريد أن أكون مشرفاً"),
)

@Composable
private fun SupportEntry(
    threads: List<SupportThread>,
    onPick: (String) -> Unit,
    onOpenThread: (SupportThread) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SupportRepository.get(context) }
    val blocking = repository.blockingThread(threads)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("راسِل المطوّر") },
                navigationIcon = { SupportBackButton(onClose) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "بمَ نساعدك؟",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
            // خيط واحد كل ٢٤ ساعة وحتى يردّ المالك: بدل رفضٍ بعد كتابة رسالة
            // كاملة، نقول ذلك سلفاً ونفتح له محادثته القائمة بنقرة.
            blocking?.let { thread ->
                Card(
                    onClick = { onOpenThread(thread) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("لك رسالة عند المطوّر", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "وصلَت رسالتك السابقة. انتظر الردّ عليها قبل أن ترسل غيرها — " +
                                "اضغط هنا لفتحها.",
                        )
                    }
                }
            }
            supportOptions.forEach { option ->
                // بطاقة كبيرة (≥ 72dp) بأيقونة واسم مكتوب: هدف لمس واسع لا
                // يخطئه إصبعٌ في الشمس، ونصٌّ يُقرأ بلا تدقيق.
                ElevatedCard(
                    // «عندي درس» يبقى مفتوحاً دائماً: مساره غير هذه المحادثات
                    // ولا يخضع لحدّها.
                    onClick = {
                        if (blocking != null && option.kind != SupportKind.LESSON_HELP) {
                            onOpenThread(blocking)
                        } else {
                            onPick(option.kind)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.emoji, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(14.dp))
                        Text(option.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (threads.isNotEmpty()) {
                Text(
                    "رسائلي السابقة",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                threads.forEach { thread ->
                    Card(
                        onClick = { onOpenThread(thread) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        colors = CardDefaults.cardColors(),
                    ) {
                        ListItem(
                            headlineContent = { Text(supportKindTitle(thread.kind)) },
                            supportingContent = {
                                Text(
                                    thread.lastMessagePreview.ifBlank { "بانتظار ردّ المطوّر" },
                                    maxLines = 1,
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Chat, contentDescription = null) },
                            trailingContent = {
                                if (repository.isUnread(thread)) {
                                    // نقطة صغيرة لا رقم: المطلوب أن يعرف أنّ
                                    // هناك ردّاً، لا أن يعدّ الرسائل.
                                    Text("🔴")
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─── الاستمارة ──────────────────────────────────────────────────

@Composable
private fun SupportForm(kind: String, onBack: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SupportRepository.get(context) }
    val store = remember { SupportStore.get(context) }

    var text by remember { mutableStateOf("") }
    // أسئلة «الإشراف» الثلاثة: ثلاثة أسطر قصيرة أسهل على من لا يكتب كثيراً من
    // صندوقٍ واحد يُطلب منه أن يملأه بنفسه.
    var who by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    var work by remember { mutableStateOf("") }
    var recorded by remember { mutableStateOf<File?>(null) }
    var shareDeviceInfo by remember { mutableStateOf(true) }
    var askIdentity by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun composedText(): String = if (kind == SupportKind.SUPERVISION) {
        listOf(
            "من أنا: ${who.trim()}",
            "صلتي بالمنبر: ${relation.trim()}",
            "ما أريد أن أعمل: ${work.trim()}",
        ).filterNot { it.endsWith(": ") }.joinToString("\n")
    } else {
        text.trim()
    }

    fun send() {
        repository.enqueue(
            kind = kind,
            text = composedText(),
            audioFile = recorded,
            includeDeviceInfo = kind == SupportKind.BUG && shareDeviceInfo,
        )
        onSent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(supportKindTitle(kind)) },
                navigationIcon = { SupportBackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                supportKindHint(kind),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 🎙️ الصوت أوّلاً وأكبر: هو الطريق الطبيعي لجمهور المنبر.
            VoiceRecordButton(
                file = recorded,
                onRecorded = { recorded = it },
                onCleared = { recorded = null },
            )
            if (kind == SupportKind.SUPERVISION) {
                SupportField(who, { who = it }, "من أنت؟")
                SupportField(relation, { relation = it }, "ما صلتك بالمنبر؟")
                SupportField(work, { work = it }, "ماذا تريد أن تعمل؟")
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= SupportRepository.MAX_TEXT) text = it
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("أو اكتب هنا (اختياري)") },
                )
            }
            // ⛔ لا مرفقات صور هنا (قرار 2026-08-25): الصوت والكتابة يكفيان
            // لكل ما تحتاجه هذه القناة، وحذفُ الصور أسقط إذن READ_MEDIA_IMAGES
            // من التطبيق كلّه — وهو الإذن الذي يُلزم Play بنموذج «الوظيفة
            // الأساسيّة للصور والفيديوهات».
            if (kind == SupportKind.BUG) {
                // 🔍 مكشوفاً لا خفيةً: ما يُرسَل عن جهازه يقرؤه بعينه، وله أن
                // يمنعه بمفتاح واحد — وحين يمنعه لا يُرسَل شيء منه إطلاقاً.
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("سنرسل معه:", style = MaterialTheme.typography.titleSmall)
                            Text(
                                repository.deviceInfo(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = shareDeviceInfo,
                            onCheckedChange = { shareDeviceInfo = it },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    val ready = composedText().isNotBlank() || recorded != null
                    if (!ready) {
                        notice = "سجّل صوتك أو اكتب سطراً واحداً على الأقل."
                        return@Button
                    }
                    // الإقرار والاسم يُسألان مرّة واحدة، وقبل أوّل إرسال فقط.
                    if (!store.consented() || !store.hasChosenName()) askIdentity = true else send()
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Icon(Icons.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("أرسل", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (askIdentity) {
        SupportIdentityDialog(
            onCancel = { askIdentity = false },
            onAccept = { name ->
                store.setDisplayName(name)
                store.setConsented()
                askIdentity = false
                send()
            },
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("حسناً") } },
        )
    }
}

@Composable
private fun SupportField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 300) onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = false,
    )
}

/// سطر يشرح للمستخدم ما المطلوب في هذا النوع بلغة يفهمها بلا مصطلحات.
private fun supportKindHint(kind: String): String = when (kind) {
    SupportKind.SUGGESTION -> "ما الذي تحبّ أن نضيفه أو نغيّره؟ تكلّم بحرّيّة."
    SupportKind.BUG -> "أخبرنا ما الذي حاولت أن تفعله وما الذي حدث."
    SupportKind.IDEA -> "اكتب أو سجّل ما في نفسك — كل فكرة تصلنا تُقرأ."
    SupportKind.SUPERVISION -> "عرّفنا بنفسك في ثلاثة أسطر قصيرة."
    else -> "تكلّم أو اكتب."
}

// ─── الهويّة والإقرار ───────────────────────────────────────────

/**
 * ⚠️ يُسأل **مرّة واحدة** قبل أوّل إرسال. النصّ صريح لا ملتبس: لا يظنّ أحدٌ
 * أنّه يكتب مجهولاً ثم يفاجَأ بأنّ المطوّر يعرف اسمه ويردّ عليه.
 */
@Composable
private fun SupportIdentityDialog(onCancel: () -> Unit, onAccept: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("بماذا نناديك؟") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("اسمك (اختياري)") },
                    singleLine = true,
                )
                Text(
                    "إن تركته فارغاً سنناديك «${SupportStore.DEFAULT_NAME}».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "هذه الرسالة ليست مجهولة — سيرى المطوّر الاسم الذي اخترته " +
                        "ورسائلك، ليردّ عليك.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAccept(name) }) { Text("موافق، أرسل") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("تراجع") } },
    )
}

// ─── بعد الإرسال ────────────────────────────────────────────────

/**
 * ✅ لا «جارٍ الإرسال» ولا شريط تقدّم: الرسالة محفوظة على الجهاز فعلاً،
 * وسترحل وحدها متى عاد الإنترنت. ما يحتاجه المستخدم أن يطمئنّ لا أن يراقب.
 */
@Composable
private fun SupportSent(onDone: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "حُفظت رسالتك",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "ستُرسل عند عودة الإنترنت، ولو أغلقت التطبيق. " +
                    "سنردّ إن احتجنا توضيحاً.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("تمام") }
        }
    }
}

// ─── شارة الردّ غير المقروء (لبند الإعدادات) ────────────────────

/// `true` إن كان للمستخدم ردٌّ من المطوّر لم يفتحه بعد — تعرضه الإعدادات نقطةً
/// على البند، فيعرف أنّ هناك جواباً بلا أن يفتح الميزة ليتفقّدها.
@Composable
fun rememberSupportUnread(): Boolean {
    val context = LocalContext.current
    val repository = remember { SupportRepository.get(context) }
    val threadsFlow = remember { repository.myThreads() }
    val threads by threadsFlow.collectAsState(initial = emptyList())
    return threads.any(repository::isUnread)
}
