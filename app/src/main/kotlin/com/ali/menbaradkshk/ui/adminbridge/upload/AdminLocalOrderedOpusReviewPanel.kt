package com.ali.menbaradkshk.ui.adminbridge.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Instantiated only by a verified local test host. No network, grant or production default. */
internal interface LocalOrderedOpusReviewBackend {
    fun prepare(orderedOriginals: List<AdminUploadDraft>, checkCaller: () -> Unit): OrderedOpusReviewSession.StagedReview
    fun pauseOrdinaryAudio(): Boolean
    fun checkLease()
    fun cancel() // Independent, nonblocking retirement signal.
}

@Composable
internal fun AdminLocalOrderedOpusReviewPanel(
    drafts: List<AdminUploadDraft>,
    backend: LocalOrderedOpusReviewBackend,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val preview = remember(backend, context) { AndroidPrivateOpusPreview(context) }
    val state = remember(backend) { LocalOrderedOpusReviewSelection() }
    val scope = rememberCoroutineScope()
    var revision by remember(backend) { mutableIntStateOf(0) }
    var candidate by remember(backend) { mutableStateOf<OrderedOpusReviewSession.StagedReview?>(null) }
    var request by remember(backend) { mutableStateOf<LocalOrderedOpusReviewSelection.Request?>(null) }
    var operation by remember(backend) { mutableStateOf<Job?>(null) }
    var message by remember(backend) { mutableStateOf<String?>(null) }

    fun cancel() {
        operation?.cancel()
        operation = null
        state.cancel()
        request = null
        val closedCandidate = candidate
        candidate = null
        revision++
        releaseLocalReview(preview::close, backend::cancel, { closedCandidate?.close() })
    }
    DisposableEffect(backend, preview) {
        onDispose {
            operation?.cancel()
            state.cancel()
            releaseLocalReview(preview::close, backend::cancel, { candidate?.close() })
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancel() }
    LaunchedEffect(drafts, backend) {
        val before = state.selected()
        if (state.updateAvailable(drafts) != null) cancel()
        else if (before != state.selected()) revision++
    }

    val selected = remember(state, revision) { state.selected() }
    val step = remember(state, revision) { state.step }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("دمج التسجيلات المحفوظة", style = MaterialTheme.typography.titleMedium)
            Text("اختر تسجيلين إلى أربعة بحسب ترتيب التشغيل. لا تُرفع أي ملفات هنا.")
            if (step == LocalOrderedOpusReviewSelection.Step.SELECTING) {
                drafts.forEach { draft ->
                    val index = selected.indexOf(draft)
                    TextButton(enabled = index >= 0 || selected.size < 4, onClick = {
                        if (state.toggle(draft)) revision++
                    }) { Text(if (index >= 0) "${index + 1}. ${draft.fileName} (إزالة)" else draft.fileName) }
                }
                Button(enabled = selected.size >= 2, onClick = {
                    val work = state.prepare() ?: return@Button
                    request = work
                    message = null
                    revision++
                    operation = scope.launch {
                        val provisional = AtomicReference<OrderedOpusReviewSession.StagedReview?>()
                        try {
                            val stage = withContext(Dispatchers.IO) {
                                backend.checkLease()
                                val running = coroutineContext
                                backend.prepare(work.originals) { running.ensureActive() }.also(provisional::set)
                            }
                            backend.checkLease()
                            if (state.candidateReady(work)) {
                                candidate = stage
                                provisional.set(null)
                                revision++
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (request === work) {
                                cancel()
                                message = "تعذّر تجهيز المعاينة المحلية."
                            }
                        } finally {
                            releaseLocalReview({ provisional.getAndSet(null)?.close() })
                        }
                    }
                }) { Text("جهّز المعاينة") }
            } else if (step == LocalOrderedOpusReviewSelection.Step.READY) {
                Button(onClick = play@{
                    val work = request ?: return@play
                    val stage = candidate ?: return@play
                    val attempt = state.explicitPlay(work) ?: return@play
                    revision++
                    val token = try {
                        backend.checkLease()
                        preview.begin(backend::pauseOrdinaryAudio)
                    } catch (_: Exception) { null }
                    if (token == null) {
                        state.interrupted(attempt)
                        revision++
                        message = "تعذّر بدء تشغيل المعاينة."
                        return@play
                    }
                    message = null
                    operation = scope.launch {
                        try {
                            val evidence = withContext(Dispatchers.IO) {
                                val running = coroutineContext
                                stage.withCandidate { file, check ->
                                    preview.renderExcerpt(token, file, stage.sha256, stage.frames48k, checkLease = {
                                        running.ensureActive()
                                        check()
                                        backend.checkLease()
                                    })
                                }
                            }
                            backend.checkLease()
                            if (evidence.sha256 == stage.sha256 && evidence.presentedFrames == minOf(stage.frames48k, 20 * 48_000L) &&
                                preview.consumeCompletion(token)) {
                                state.playEnded(attempt)
                            } else state.interrupted(attempt)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (state.interrupted(attempt)) message = "توقّفت المعاينة. يمكنك المحاولة مرة أخرى."
                        } finally {
                            releaseLocalReview({ preview.revoke(token) })
                            revision++
                        }
                    }
                }) { Text("استمع إلى أول ٢٠ ثانية") }
            } else if (step == LocalOrderedOpusReviewSelection.Step.REVIEWED) {
                Button(onClick = save@{
                    val work = request ?: return@save
                    val stage = candidate ?: return@save
                    if (!state.explicitKeepLocal(work)) return@save
                    revision++
                    operation = scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                backend.checkLease()
                                stage.saveForReview()
                            }
                            if (state.finishedSaving(work)) {
                                request = null
                                candidate = null
                                message = "حُفظت المسودة المدمجة على الجهاز. لم تُرفع."
                                onSaved()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (request === work) {
                                cancel()
                                message = "تعذّر حفظ المسودة المدمجة."
                            }
                        } finally { revision++ }
                    }
                }) { Text("احفظ الدمج هنا") }
            } else {
                Text(when (step) {
                    LocalOrderedOpusReviewSelection.Step.PREPARING -> "جارٍ تجهيز المعاينة."
                    LocalOrderedOpusReviewSelection.Step.PLAYING -> "جارٍ تشغيل العيّنة الصوتية."
                    else -> "جارٍ حفظ المسودة."
                })
            }
            if (step != LocalOrderedOpusReviewSelection.Step.SELECTING) {
                TextButton(onClick = { cancel(); message = null }) { Text("ألغِ المراجعة") }
            }
            message?.let { Text(it) }
        }
    }
}
