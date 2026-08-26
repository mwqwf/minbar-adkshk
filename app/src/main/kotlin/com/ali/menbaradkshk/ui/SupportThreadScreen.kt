@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ali.menbaradkshk.data.SupportMessage
import com.ali.menbaradkshk.data.SupportRepository
import com.ali.menbaradkshk.data.SupportThread
import com.ali.menbaradkshk.util.VoiceRecorder
import com.ali.menbaradkshk.util.formatDuration
import kotlinx.coroutines.launch
import java.io.File

/**
 * 💬 شاشة المحادثة مع المطوّر.
 *
 * **لماذا لا حقل كتابة قبل ردّ المطوّر؟** الخادم يرفض الرسالة الثانية حتى
 * يردّ المالك. وعرض حقلٍ معطّل يجعل المستخدم يظنّ أنّ التطبيق تعطّل عنده،
 * فنضع مكانه سطراً مطمئناً يقول إنّ رسالته وصلت — وهو ما يريد سماعه أصلاً.
 */
@Composable
fun SupportThreadScreen(
    thread: SupportThread,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { SupportRepository.get(context) }
    val scope = rememberCoroutineScope()
    // مثل قائمة المحادثات: الدفق يُذكر بمعرّف المحادثة فلا يُعاد تسجيل مستمع
    // Firestore مع كل إعادة تركيب.
    val messagesFlow = remember(thread.id) { repository.messages(thread.id) }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    // فتحُ المحادثة = قراءتُها: تختفي النقطة فوراً بلا انتظار الخادم.
    LaunchedEffect(thread.id, thread.lastMessageAtMs) { repository.markSeen(thread) }

    // ⭐ حقل الخادم لا استنتاج من الرسائل: هو مصدر الحقيقة في قبول رسالة
    // ثانية، ورسائل المطوّر قد تتأخّر في الوصول إلى الجهاز.
    val ownerReplied = thread.ownerReplied || messages.any { it.fromOwner }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(supportKindTitle(thread.kind)) },
                navigationIcon = { SupportBackButton(onBack) },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "احذف محادثتي")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> SupportBubble(message) }
            }
            if (ownerReplied && !thread.closed && !thread.blocked) {
                SupportComposer(
                    onSend = { text, audio ->
                        repository.enqueue(
                            kind = thread.kind,
                            threadId = thread.id,
                            isNew = false,
                            text = text,
                            audioFile = audio,
                        )
                    },
                )
            } else {
                // السطر المطمئن بدل حقل معطّل لا يعرف المستخدم لماذا لا يكتب فيه.
                Text(
                    if (thread.closed) {
                        "انتهت هذه المحادثة. يمكنك أن ترسل رسالة جديدة متى شئت."
                    } else {
                        "وصلَت رسالتك. سنردّ إن احتجنا توضيحاً."
                    },
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف المحادثة") },
            text = { Text("ستُحذف الرسائل كلّها ولن تعود. أتريد الحذف؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            runCatching { repository.deleteThread(thread.id) }
                            onBack()
                        }
                    },
                ) { Text("احذف") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("تراجع") }
            },
        )
    }
}

/// فقاعة رسالة — رسائل المطوّر على جهة، ورسائل المستخدم على الأخرى بلون بارز.
@Composable
private fun SupportBubble(message: SupportMessage) {
    val fromOwner = message.fromOwner
    val background = if (fromOwner) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (fromOwner) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromOwner) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .background(background, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (fromOwner) {
                Text(
                    "المطوّر",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (message.text.isNotBlank()) {
                Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
            }
            if (message.audioPath.isNotBlank()) {
                SupportAudioPlayer(message.audioPath, message.pending || message.failed)
            }
            if (message.failed) {
                // رفضٌ قاطع من الخادم: نقولها صراحةً مع مخرجٍ بنقرة واحدة.
                val context2 = LocalContext.current
                val repo = remember { SupportRepository.get(context2) }
                Text(
                    "تعذّر الإرسال",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { repo.retryFailed(message.id) }) {
                    Text("أعد المحاولة")
                }
            } else if (message.pending) {
                // لا نقول «فشل الإرسال»: الرسالة محفوظة وستُرسل وحدها.
                Text(
                    "ستُرسل عند عودة الإنترنت",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/// مشغّل بسيط لرسالة صوتيّة واحدة — زرّ واحد كبير لا أكثر.
@Composable
private fun SupportAudioPlayer(path: String, pending: Boolean) {
    val context = LocalContext.current
    val repository = remember { SupportRepository.get(context) }
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 🔉 بؤرة صوتيّة **عابرة**: طلبُها يوقف درساً جارياً مؤقّتاً بدل أن تعلو
    // الرسالة فوقه، وتُعاد للنظام فور التوقّف/الاكتمال فيستأنف المشغّل.
    val audioManager = remember {
        context.getSystemService(android.media.AudioManager::class.java)
    }
    val audioAttributes = remember {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    val focusListener = remember {
        android.media.AudioManager.OnAudioFocusChangeListener { change ->
            if (change != android.media.AudioManager.AUDIOFOCUS_GAIN) {
                runCatching { player.stop() }
                playing = false
            }
        }
    }
    // ⛔ minSdk 23 دون `AudioFocusRequest` (API 26)، فالنداء القديم — وهو
    // مهجور لا مكسور — يخدم الأجهزة كلّها بلا مكتبة إضافيّة.
    val focusRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }
    }
    fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            manager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
        return result != android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }
    fun abandonFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            manager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            abandonFocus()
            runCatching { player.release() }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (playing) {
                    runCatching { player.stop() }
                    playing = false
                    abandonFocus()
                    return@IconButton
                }
                scope.launch {
                    val uri = runCatching { repository.attachmentUri(path) }.getOrNull()
                        ?: return@launch
                    // ⚠️ `prepareAsync` لا `prepare`: التحضير المتزامن على رابط
                    // شبكي يجمّد خيط الواجهة (ANR على شبكة ضعيفة). التشغيل يبدأ
                    // في مستمع الجاهزيّة، والفشل يُطوى بهدوء بلا تجمّد.
                    runCatching {
                        player.reset()
                        player.setAudioAttributes(audioAttributes)
                        player.setDataSource(context, uri)
                        player.setOnCompletionListener {
                            playing = false
                            abandonFocus()
                        }
                        player.setOnErrorListener { _, _, _ ->
                            playing = false
                            abandonFocus()
                            true
                        }
                        player.setOnPreparedListener {
                            if (requestFocus()) {
                                player.start()
                                playing = true
                            }
                        }
                        player.prepareAsync()
                    }
                }
            },
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "أوقف" else "شغّل الرسالة الصوتية",
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            if (pending) "رسالة صوتية" else "رسالة صوتية — اضغط للاستماع",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/// حقل الردّ بعد أن يردّ المطوّر: زرّ الصوت أوّلاً وأكبر، والكتابة بعده.
@Composable
private fun SupportComposer(onSend: (String, File?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var recorded by remember { mutableStateOf<File?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VoiceRecordButton(
            file = recorded,
            onRecorded = { recorded = it },
            onCleared = { recorded = null },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= SupportRepository.MAX_TEXT) text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("أو اكتب هنا (اختياري)") },
            minLines = 1,
        )
        Button(
            onClick = {
                onSend(text, recorded)
                text = ""
                recorded = null
            },
            enabled = text.isNotBlank() || recorded != null,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Filled.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("أرسل")
        }
    }
}

/**
 * 🎙️ زرّ «اضغط وتكلّم» — أهمّ عنصر في الميزة كلّها.
 *
 * أكبر من زرّ الكتابة عمداً: جمهور المنبر يتكلّم أسهل ممّا يكتب، فالزرّ الذي
 * يراه أوّلاً وأوضحَ ما على الشاشة هو زرّ الصوت لا لوحة المفاتيح.
 *
 * الإذن يُطلب **عند أوّل ضغطة تسجيل** لا عند فتح الشاشة: طلبُه لمن جاء ليكتب
 * اقتراحاً يبدو تطفّلاً بلا سبب ظاهر، فيرفضه رفضاً دائماً ثم يعجز عن التسجيل
 * حين يحتاجه فعلاً.
 */
@Composable
fun VoiceRecordButton(
    file: File?,
    onRecorded: (File) -> Unit,
    onCleared: () -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val elapsed by recorder.elapsedMs.collectAsState()

    DisposableEffect(Unit) { onDispose { recorder.release() } }

    fun begin() {
        failure = runCatching { recorder.start(context) }
            .exceptionOrNull()?.let { "تعذّر التسجيل على هذا الجهاز." }
        recording = failure == null
    }

    fun end() {
        recording = false
        val out = recorder.stop()
        if (out == null) {
            failure = "لم نسمع شيئاً — أعد التسجيل واضغط أطول قليلاً."
        } else {
            failure = null
            onRecorded(out)
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) begin() else failure = "نحتاج إذن الميكروفون كي نسجّل صوتك."
    }

    // السقف يُفرض من المؤقّت نفسه: لا يعتمد على انتباه المستخدم للعدّاد.
    LaunchedEffect(recording, elapsed) {
        if (recording && elapsed >= VoiceRecorder.MAX_DURATION_MS) end()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (file == null) {
            Button(
                onClick = {
                    if (recording) {
                        end()
                    } else if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        begin()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(84.dp),
                shape = RoundedCornerShape(20.dp),
                colors = if (recording) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(
                    if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (recording) {
                        "أوقف التسجيل — ${formatDuration(elapsed)}"
                    } else {
                        "اضغط وتكلّم"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!recording) {
                Text(
                    "أطول تسجيل: دقيقتان",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("تسجيلك جاهز", Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            runCatching { file.delete() }
                            onCleared()
                        },
                    ) { Text("سجّل من جديد") }
                }
            }
        }
        failure?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

/// زرّ رجوع واحد تتقاسمه شاشتا الميزة — بسهم يناسب اتجاه اللغة تلقائياً
/// (`AutoMirrored`)، فلا يشير إلى الجهة الخطأ في واجهة عربيّة.
@Composable
fun SupportBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "رجوع",
        )
    }
}

/// عنوان عربيّ لكل نوع — يظهر في رأس المحادثة وفي قائمة المحادثات السابقة.
fun supportKindTitle(kind: String): String = when (kind) {
    com.ali.menbaradkshk.data.SupportKind.SUGGESTION -> "اقتراح لتحسين التطبيق"
    com.ali.menbaradkshk.data.SupportKind.BUG -> "شيء لا يعمل"
    com.ali.menbaradkshk.data.SupportKind.LESSON_HELP -> "درس بلا قسم"
    com.ali.menbaradkshk.data.SupportKind.IDEA -> "ملاحظة أو فكرة"
    com.ali.menbaradkshk.data.SupportKind.SUPERVISION -> "طلب إشراف"
    else -> "رسالة إلى المطوّر"
}
