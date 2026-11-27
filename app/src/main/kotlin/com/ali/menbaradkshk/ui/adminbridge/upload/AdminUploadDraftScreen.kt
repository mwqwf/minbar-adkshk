package com.ali.menbaradkshk.ui.adminbridge.upload

import android.content.ContentResolver
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import com.ali.menbaradkshk.adminsurface.fetchLessonDestinations
import com.ali.menbaradkshk.ui.adminbridge.VerifiedAdminSession
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class OriginalPreview(val name: String, val reportedBytes: Long?)
private class UnreadableAudio : IOException()

/**
 * Host owns the OpenDocument launcher above the authorization gate and exposes this URI
 * only after the same account has re-verified. Do not put grants or URIs in saved state.
 */
@Composable
internal fun AdminUploadDraftScreen(
    accountKey: String,
    session: VerifiedAdminSession,
    selectedUri: Uri?,
    onRequestPicker: () -> Unit,
    onDismissSelection: (Uri) -> Unit,
    onBack: () -> Unit,
    localReview: ((AdminUploadDraftStore) -> LocalOrderedOpusReviewBackend)? = null,
) {
    val context = LocalContext.current.applicationContext
    val resolver = context.contentResolver
    val document = selectedUri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    val store = remember(context, accountKey) { AdminUploadDraftStore(context.noBackupFilesDir, accountKey) }
    val scope = rememberCoroutineScope()
    val reviewBackend = remember(session, store, localReview) {
        localReview?.takeIf { session.accountKey == accountKey }?.invoke(store)
    }
    var drafts by remember(accountKey) { mutableStateOf<List<AdminUploadDraft>>(emptyList()) }
    var preview by remember(accountKey, document) { mutableStateOf<OriginalPreview?>(null) }
    var message by remember(accountKey) { mutableStateOf<String?>(null) }
    var preparing by remember(accountKey) { mutableStateOf(false) }
    var stageDraft by remember(session) { mutableStateOf<AdminUploadDraft?>(null) }
    var longDraft by remember(session) { mutableStateOf<AdminUploadDraft?>(null) }
    val longUpload = remember(session, store) { session.longPrivateUpload(store) }
    val newLesson = remember(session, store) { session.guardedNewLessonPublication(store) }
    val maxPrivateBytes = session.privateUploadMaxBytes?.takeIf { session.accountKey == accountKey }
    var job by remember(accountKey) { mutableStateOf<Job?>(null) }

    LaunchedEffect(store) {
        try {
            drafts = withContext(Dispatchers.IO) { store.recover(); store.list() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "تعذّر فتح المسودات المحفوظة."
        }
    }

    LaunchedEffect(resolver, document, accountKey) {
        if (document != null) {
            preview = withContext(Dispatchers.IO) { getPreview(resolver, document) }
        }
    }

    val selectedLong = longDraft
    if (selectedLong != null && longUpload != null) {
        AdminUploadLongPanel(longUpload, selectedLong,
            loadDestinations = { fetchLessonDestinations(AdminWorkspaceApi { path -> session.request("GET", path) }) },
            onBack = { longDraft = null }, newLesson = newLesson)
        return
    }

    val selectedDraft = stageDraft
    if (selectedDraft != null && maxPrivateBytes != null && selectedDraft.byteCount <= maxPrivateBytes.toLong()) {
        AdminUploadDraftStagePanel(session, store, selectedDraft, onBack = { stageDraft = null })
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("مسودات رفع الدروس", style = MaterialTheme.typography.headlineSmall)
        Text("اختر أصل التسجيل. التحضير يحفظ نسخة على هذا الجهاز فقط ولا يرفعها.")
        Button(onClick = onRequestPicker, enabled = !preparing) { Text("اختر ملفًا صوتيًا") }
        val picked = preview
        if (document != null && picked != null) {
            Text(picked.name, style = MaterialTheme.typography.titleMedium)
            Text(picked.reportedBytes?.let { "$it بايت" } ?: "سيُفحص حجم الملف عند التحضير.")
            val tooLarge = picked.reportedBytes?.let { it > MAX_ORIGINAL_BYTES } == true
            if (tooLarge) Text("الملف يتجاوز الحد (٩٥ ميبيبايت).", color = MaterialTheme.colorScheme.error)
            if (!preparing) {
                Button(
                    enabled = !tooLarge,
                    onClick = {
                        if (preparing) return@Button
                        preparing = true
                        message = null
                        job = scope.launch {
                            try {
                                val draft = withContext(Dispatchers.IO) {
                                    store.prepare(
                                        picked.name, picked.reportedBytes,
                                        checkActive = { coroutineContext.ensureActive() },
                                        validateAudio = ::verifyLocalAudio,
                                    ) { resolver.openInputStream(document) ?: throw IOException("No input stream") }
                                }
                                drafts = withContext(Dispatchers.IO) { store.list() }
                                message = "النسخة محفوظة هنا: ${draft.fileName}. لم تُرفع."
                                onDismissSelection(document)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: DraftSizeException) {
                                message = "التسجيل فارغ أو أكبر من الحد (٩٥ ميبيبايت)."
                            } catch (_: DraftSpaceException) {
                                message = "بلغ هذا الحساب الحد المخصّص للمسودات على الجهاز."
                            } catch (_: UnreadableAudio) {
                                message = "لم يتعرّف الجهاز على مسار صوتي في الملف المختار."
                            } catch (_: Exception) {
                                message = "تعذّر تجهيز التسجيل. تحقّق من الملف ومن المساحة المتاحة."
                            } finally {
                                preparing = false
                            }
                        }
                    },
                ) { Text("احفظ نسخة للتحضير") }
                TextButton(onClick = { onDismissSelection(document) }) { Text("ألغِ الاختيار") }
            }
        }
        if (preparing) {
            CircularProgressIndicator()
            TextButton(onClick = {
                val current = job
                current?.cancel()
                scope.launch {
                    current?.join()
                    try {
                        drafts = withContext(Dispatchers.IO) { store.list() }
                        message = "انتهت محاولة التحضير. ما اكتمل حفظه يظهر أدناه."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "تعذرت قراءة المسودات بعد إيقاف التحضير."
                    }
                }
            }) { Text("أوقف التحضير") }
        }
        message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (reviewBackend != null && !preparing && document == null) {
            AdminLocalOrderedOpusReviewPanel(drafts, reviewBackend, onSaved = {
                scope.launch {
                    try {
                        drafts = withContext(Dispatchers.IO) { store.list() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "حُفظت المسودة، لكن تعذّر تحديث القائمة."
                    }
                }
            })
        }
        if (drafts.isEmpty()) Text("لا توجد مسودات محفوظة لهذا الحساب.")
        for (draft in drafts) {
            Card { Column(Modifier.padding(12.dp)) {
                Text(draft.fileName, style = MaterialTheme.typography.titleMedium)
                Text("${draft.byteCount} بايت · محفوظ على الجهاز")
                when {
                    maxPrivateBytes != null && draft.byteCount <= maxPrivateBytes.toLong() ->
                        TextButton(enabled = !preparing, onClick = { stageDraft = draft }) {
                            Text("إعداد الرفع الخاص")
                        }
                    longUpload != null && draft.byteCount > MAX_STAGE_BYTES ->
                        TextButton(enabled = !preparing, onClick = { longDraft = draft }) {
                            Text("مراجعة الرفع الخاص الطويل")
                        }
                    maxPrivateBytes != null -> Text(
                        "الملف أكبر من حد الرفع الخاص الحالي ($maxPrivateBytes بايت). " +
                            "الأصل محفوظ على هذا الجهاز دون تغيير.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } }
        }
        TextButton(onClick = onBack, enabled = !preparing) { Text("رجوع") }
    }
}

private fun getPreview(resolver: ContentResolver, uri: Uri): OriginalPreview = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        OriginalPreview(
            safeDraftFileName(if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""),
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it > 0 } else null,
        )
    } ?: OriginalPreview("تسجيل صوتي", null)
} catch (_: Exception) {
    OriginalPreview("تسجيل صوتي", null)
}

private fun verifyLocalAudio(file: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        if ((0 until extractor.trackCount).none {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }) throw UnreadableAudio()
    } catch (_: Exception) {
        throw UnreadableAudio()
    } finally {
        extractor.release()
    }
}
