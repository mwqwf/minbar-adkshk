package com.ali.menbaradkshk.ui.adminbridge.share

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Process
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.ali.menbaradkshk.ui.MinbarTheme
import com.ali.menbaradkshk.ui.adminbridge.AdminEntryScreen
import com.ali.menbaradkshk.ui.adminbridge.VerifiedAdminSession
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraftStore
import com.ali.menbaradkshk.ui.adminbridge.upload.DraftSizeException
import com.ali.menbaradkshk.ui.adminbridge.upload.DraftSpaceException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ephemeral sharesheet selection; no forwarding to navigation and no saved restoration. */
class ModeratorAudioShareActivity : ComponentActivity() {
    @Volatile private var foreground = false
    private val cancellation = CancellationSignal()
    private val input = AtomicReference<InputStream?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val offer = if (savedInstanceState == null) submittedAudio(intent, packageName) else null
        setIntent(Intent()) // retain no launch URI in this Activity's intent/state
        setContent {
            MinbarTheme(themeMode = "system", fontScale = 1f) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    if (offer == null || !canRead(offer)) {
                        Status("لم نفتح الملف. أعد مشاركة تسجيل صوتي واحد بعد فتح لوحة الإشراف في منبر.")
                    } else {
                        AdminEntryScreen(onBack = ::finish, allowInteractiveSignIn = false) { session, _ ->
                            if (session.canImportSharedAudioDraft) {
                                SharePanel(session, offer)
                            } else {
                                Status("هذه الجلسة لا تتيح حفظ أصل درس. لم نفتح التسجيل.")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        // Signal cancellation and pipe closure may invoke a provider. Do not
        // block the activity; the synchronously closed foreground fence prevents commit.
        Thread {
            runCatching { cancellation.cancel() }
            runCatching { input.getAndSet(null)?.close() }
        }.start()
        super.onPause()
        finish()
    }

    private fun canRead(uri: Uri) =
        checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun localStream(uri: Uri): InputStream {
        val descriptor = contentResolver.openAssetFileDescriptor(uri, "r", cancellation) ?: throw IOException()
        val stream = try { descriptor.createInputStream() } catch (error: Exception) {
            descriptor.close()
            throw error
        }
        input.set(stream)
        if (cancellation.isCanceled) {
            input.getAndSet(null)?.close()
            throw CancellationException("Moderator share closed")
        }
        return stream
    }

    @Composable
    private fun Status(message: String) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("مسودة درس خاصة", style = MaterialTheme.typography.headlineSmall)
            Text(message)
            TextButton(onClick = ::finish) { Text("إغلاق") }
        }
    }

    @Composable
    private fun SharePanel(session: VerifiedAdminSession, uri: Uri) {
        val work = rememberCoroutineScope()
        val store = remember(session) { AdminUploadDraftStore(noBackupFilesDir, session.accountKey) }
        var saving by remember(session) { mutableStateOf(false) }
        var attempted by remember(session) { mutableStateOf(false) }
        var review by remember(session) { mutableStateOf(false) }
        var message by remember(session) { mutableStateOf<String?>(null) }
        BackHandler(enabled = saving) { /* wait for the bounded copy or let leaving close its lease */ }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("استيراد تسجيل لمسودة درس", style = MaterialTheme.typography.headlineSmall)
            Text("لن نفتح الملف قبل موافقتك. الحفظ لمسودات حسابك على هذا الجهاز فقط، ولا يرفع التسجيل ولا ينشره.")
            if (!attempted) {
                Button(
                    onClick = { review = true },
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("مراجعة الحفظ على الجهاز") }
            }
            if (saving) Text("يجري فحص التسجيل وحفظه على الجهاز…")
            message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            TextButton(onClick = ::finish, enabled = !saving) { Text("إغلاق") }
        }
        if (review) {
            AlertDialog(
                onDismissRequest = { review = false },
                title = { Text("هل تحفظ هذا التسجيل على الجهاز؟") },
                text = { Text("سيُفتح تسجيل واحد لحفظه مسودة خاصة بحسابك. لن يُرسل إلى الخادم.") },
                dismissButton = { TextButton(onClick = { review = false }) { Text("إلغاء") } },
                confirmButton = {
                    Button(onClick = {
                        if (attempted || !review || !foreground || !session.canImportSharedAudioDraft) return@Button
                        review = false
                        attempted = true // consume this selection; retry means reshare
                        saving = true
                        work.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    prepareModeratorSharedAudio(
                                        store = store,
                                        confirmed = true,
                                        leaseActive = { foreground && session.canImportSharedAudioDraft },
                                        hasReadGrant = { canRead(uri) },
                                        checkCancelled = { coroutineContext.ensureActive() },
                                        validateAudio = ::validateAudioOnly,
                                        open = { localStream(uri) },
                                    )
                                }
                                message = "حُفظ التسجيل على الجهاز فقط. ستجده في «مسودات رفع الدروس» في لوحة الإشراف."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: DraftSizeException) {
                                message = "التسجيل فارغ أو أكبر من ٩٥ ميبيبايت. لم يُحفظ؛ أعد المشاركة بملف مناسب."
                            } catch (_: DraftSpaceException) {
                                message = "لا تتّسع مسودات هذا الحساب لتسجيل آخر. لم يُحفظ."
                            } catch (_: Exception) {
                                message = "تعذّر فتح تسجيل مناسب أو حفظه. لم يُرفع شيء؛ أعد مشاركة الملف."
                            } finally {
                                input.getAndSet(null)
                                saving = false
                            }
                        }
                    }) { Text("احفظ مسودة محلية") }
                },
            )
        }
    }
}

internal fun submittedAudio(intent: Intent?, ownPackage: String): Uri? = runCatching {
    if (intent == null || intent.action != Intent.ACTION_SEND || intent.data != null ||
        !supportsModeratorAudioShareType(intent.type) ||
        intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
        intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0 ||
        intent.hasExtra(Intent.EXTRA_TEXT) || intent.hasExtra(Intent.EXTRA_HTML_TEXT)) return null
    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    if (stream == null && intent.hasExtra(Intent.EXTRA_STREAM)) return null
    val clip = intent.clipData?.let { data ->
        if (data.itemCount != 1) return null
        val item = data.getItemAt(0)
        if (item.text != null || item.htmlText != null || item.intent != null) return null
        item.uri ?: return null
    }
    if (stream != null && clip != null && stream != clip) return null
    val uri = stream ?: clip ?: return null
    uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && it.isHierarchical &&
        it.toString().length in 1..8192 && validModeratorShareAuthority(it.authority, ownPackage) }
}.getOrNull()

private fun validateAudioOnly(file: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        if (tracks.none { it?.startsWith("audio/") == true } ||
            tracks.any { it?.startsWith("video/") == true || it?.startsWith("image/") == true }) {
            throw IOException("Not an audio-only recording")
        }
    } finally {
        extractor.release()
    }
}
