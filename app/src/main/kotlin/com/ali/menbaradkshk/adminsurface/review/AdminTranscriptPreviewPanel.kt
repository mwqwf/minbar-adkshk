package com.ali.menbaradkshk.adminsurface.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Preview only. The host must dispose this panel on suspension, account change or lost authority. */
@Composable
internal fun AdminTranscriptPreviewPanel(
    reader: AdminTranscriptPreviewReader,
    rejection: AdminTranscriptRejection? = null,
    acceptance: AdminTranscriptAcceptance? = null,
    imagePreview: AdminTranscriptImagePreview? = null,
    onBack: () -> Unit,
) {
    if (!reader.isAvailable()) return
    key(reader) {
        val scope = rememberCoroutineScope()
        DisposableEffect(rejection, acceptance) {
            onDispose {
                rejection?.discard()
                acceptance?.discard()
            }
        }
        var queue by remember { mutableStateOf<TranscriptPreviewQueue?>(null) }
        var text by remember { mutableStateOf<TranscriptPreview?>(null) }
        var imageOpen by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<TranscriptPreviewItem?>(null) }
        var confirmation by remember { mutableStateOf<PreparedTranscriptRejection?>(null) }
        var acceptanceConfirmation by remember { mutableStateOf<PreparedTranscriptAcceptance?>(null) }
        var accepted by remember { mutableStateOf(false) }
        var acceptanceUncertain by remember { mutableStateOf(false) }
        var reason by remember { mutableStateOf(TranscriptRejectionReason.Unspecified) }
        var rejected by remember { mutableStateOf(false) }
        var uncertain by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf(false) }

        fun clearConfirmation() {
            confirmation = null
            acceptanceConfirmation = null
            rejection?.discard()
            acceptance?.discard()
        }
        fun back() {
            if (busy) return
            clearConfirmation()
            imageOpen = false
            if (text != null) {
                selected = null
                text = null
                failure = false
            } else {
                onBack()
            }
        }
        BackHandler { back() }
        val imageItem = selected
        if (imageOpen && text != null && imageItem?.kind == TranscriptPreviewKind.TextAndPages &&
            imagePreview?.available() == true) {
            AdminTranscriptImageDialog(imagePreview, imageItem) { imageOpen = false }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("مراجعة النصوص المعلّقة", style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = !busy, onClick = { back() }) {
                    Text(if (text == null) "رجوع" else "العودة إلى القائمة")
                }
                if (busy) CircularProgressIndicator()
                if (failure) Text("تعذّرت المعاينة الآن. تحقّق من الجلسة أو أعد المحاولة.")
                if (rejected) Text("رُفض النصّ.")
                if (accepted) Text("اعتُمد النصّ. لم نتحقق من ظهوره للمستمعين.")
                if (acceptanceUncertain) Text("لم يتأكد الاعتماد. اعرض الطلبات من جديد قبل أي قرار آخر.")
                if (uncertain) Text("لم يتأكد الرفض. اعرض الطلبات من جديد قبل أي قرار آخر.")
            }
            val open = text
            if (open != null) {
                item { Text("نصّ المساهمة", style = MaterialTheme.typography.titleMedium) }
                // Standard text: no URI, rich text, rendering web content, logging or on-disk persistence.
                item { Text(open.text) }
                val reviewItem = selected
                if (reviewItem?.kind == TranscriptPreviewKind.TextAndPages && imagePreview?.available() == true) {
                    item {
                        TextButton(enabled = !busy, onClick = {
                            if (!busy && selected === reviewItem && text === open && imagePreview.available()) imageOpen = true
                        }) { Text("معاينة الصفحة الأولى") }
                    }
                }
                if (reviewItem?.kind == TranscriptPreviewKind.Text && acceptance?.isAvailable() == true) {
                    item {
                        TextButton(enabled = !busy, onClick = {
                            if (busy || !acceptance.isAvailable() || selected !== reviewItem || text !== open)
                                return@TextButton
                            clearConfirmation()
                            busy = true
                            failure = false
                            accepted = false
                            acceptanceUncertain = false
                            rejected = false
                            uncertain = false
                            scope.launch {
                                try {
                                    acceptanceConfirmation = acceptance.prepare(reviewItem, open)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    clearConfirmation()
                                    selected = null
                                    text = null
                                    queue = null
                                    acceptanceUncertain = true
                                } finally { busy = false }
                            }
                        }) { Text("راجع اعتماد النصّ") }
                    }
                }
                if (reviewItem?.kind == TranscriptPreviewKind.Text && rejection?.isAvailable() == true) {
                    item {
                        TextButton(enabled = !busy, onClick = {
                            if (busy || !rejection.isAvailable() || selected !== reviewItem || text !== open)
                                return@TextButton
                            busy = true
                            failure = false
                            rejected = false
                            uncertain = false
                            accepted = false
                            acceptanceUncertain = false
                            reason = TranscriptRejectionReason.Unspecified
                            scope.launch {
                                try {
                                    confirmation = rejection.prepare(reviewItem, open)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    clearConfirmation()
                                    selected = null
                                    text = null
                                    queue = null
                                    uncertain = true
                                } finally { busy = false }
                            }
                        }) { Text("راجع رفض النصّ", color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else {
                item {
                    Text("معاينة النصّ فقط. لا تُعرض الصور أو بيانات المساهم ولا يُتخذ قرار من هنا.")
                    Button(enabled = !busy, onClick = {
                        if (busy || !reader.isAvailable()) return@Button
                        busy = true
                        failure = false
                        rejected = false
                        uncertain = false
                        accepted = false
                        acceptanceUncertain = false
                        queue = null
                        scope.launch {
                            try {
                                queue = reader.queue()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                failure = true
                            } finally { busy = false }
                        }
                    }) { Text("اعرض أحدث الطلبات") }
                }
                queue?.let { loaded ->
                    if (loaded.items.isEmpty()) item { Text("لا توجد طلبات متاحة للمعاينة.") }
                    if (loaded.truncated) item { Text("هذه أحدث الطلبات المتاحة فقط.") }
                    itemsIndexed(loaded.items, key = { _, entry -> entry.id }) { index, entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("طلب ${index + 1} · ${entry.kind.label}", style = MaterialTheme.typography.titleMedium)
                                TextButton(enabled = !busy, onClick = {
                                    if (busy || !reader.isAvailable()) return@TextButton
                                    busy = true
                                    failure = false
                                    scope.launch {
                                        try {
                                            text = reader.preview(entry)
                                            selected = entry
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            failure = true
                                        } finally { busy = false }
                                    }
                                }) { Text("معاينة النصّ") }
                            }
                        }
                    }
                }
            }
        }
        val reviewedAcceptance = acceptanceConfirmation
        if (reviewedAcceptance != null && acceptance?.isAvailable() == true) {
            AlertDialog(
                onDismissRequest = { if (!busy) clearConfirmation() },
                title = { Text("تأكيد اعتماد النصّ") },
                text = { Text("سيُعتمد النصّ المعروض وحده بعد التحقق مجددًا من أنه لم يتغيّر.") },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        if (busy || acceptanceConfirmation !== reviewedAcceptance || !acceptance.isAvailable())
                            return@TextButton
                        busy = true
                        acceptanceConfirmation = null
                        scope.launch {
                            try {
                                acceptance.accept(reviewedAcceptance)
                                accepted = true
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                acceptanceUncertain = true
                            } finally {
                                clearConfirmation()
                                selected = null
                                text = null
                                queue = null
                                busy = false
                            }
                        }
                    }) { Text("أكّد اعتماد النصّ") }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { clearConfirmation() }) { Text("إلغاء") } },
            )
        }
        val prepared = confirmation
        if (prepared != null && rejection?.isAvailable() == true) {
            AlertDialog(
                onDismissRequest = { if (!busy) clearConfirmation() },
                title = { Text("تأكيد رفض المساهمة النصية") },
                text = {
                    Column {
                        Text("سيُرفض هذا النصّ وحده بعد التحقق مجددًا من أنه لم يتغيّر.")
                        for (option in TranscriptRejectionReason.entries) {
                            TextButton(enabled = !busy, onClick = { reason = option }) {
                                Text(if (reason == option) "✓ ${option.label}" else option.label)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        if (busy || confirmation !== prepared || !rejection.isAvailable()) return@TextButton
                        val reviewedReason = reason
                        busy = true
                        confirmation = null
                        scope.launch {
                            try {
                                rejection.reject(prepared, reviewedReason)
                                rejected = true
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                uncertain = true
                            } finally {
                                clearConfirmation()
                                selected = null
                                text = null
                                queue = null
                                busy = false
                            }
                        }
                    }) { Text("أكّد الرفض", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { clearConfirmation() }) { Text("إلغاء") } },
            )
        }
    }
}
