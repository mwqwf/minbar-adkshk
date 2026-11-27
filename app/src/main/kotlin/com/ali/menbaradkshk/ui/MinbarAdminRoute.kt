package com.ali.menbaradkshk.ui

import com.ali.menbaradkshk.ui.adminbridge.invites.GuardedInvitesPanel
import com.ali.menbaradkshk.ui.adminbridge.publication.GuardedNewLessonDiscoveryPanel
import com.ali.menbaradkshk.adminsurface.announcements.GeneralAnnouncementPanel
import com.ali.menbaradkshk.adminsurface.announcements.GeneralNoticePending
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.DraftImagePickerOffer
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.SelectedDraftImage
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftImagePanel
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftPending
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftPanel
import com.ali.menbaradkshk.adminsurface.sectioncreate.GuardedSectionCreatePanel
import com.ali.menbaradkshk.adminsurface.support.GuardedSupportInboxPanel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptPublicationPanel
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptPreviewPanel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.adminsurface.AdminAccessRejected
import com.ali.menbaradkshk.adminsurface.ModeratorRosterPanel
import com.ali.menbaradkshk.adminsurface.AdminLessonMutationApi
import com.ali.menbaradkshk.adminsurface.AdminTrashRestoreApi
import com.ali.menbaradkshk.adminsurface.ArchiveChanged
import com.ali.menbaradkshk.adminsurface.ArchiveParentsUnavailable
import com.ali.menbaradkshk.adminsurface.LessonMetadataStale
import com.ali.menbaradkshk.adminsurface.AdminWorkspace
import com.ali.menbaradkshk.adminsurface.AdminSubmissionMutationApi
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceRole
import com.ali.menbaradkshk.ui.adminbridge.AdminAccountDeletionLink
import com.ali.menbaradkshk.ui.adminbridge.AdminAccessTools
import com.ali.menbaradkshk.ui.adminbridge.AdminIssuedAccessCode
import com.ali.menbaradkshk.ui.adminbridge.AdminBridgeFailure
import com.ali.menbaradkshk.ui.adminbridge.AdminEntryScreen
import com.ali.menbaradkshk.ui.adminbridge.AdminRole
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminOriginalPicker
import com.ali.menbaradkshk.ui.adminbridge.upload.rememberAdminOriginalPicker
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraftScreen
import com.ali.menbaradkshk.ui.adminbridge.upload.LocalOrderedOpusReviewHost
import org.json.JSONObject

/** Entry is discoverable in Settings; protected content does not mount before fresh server verification. */
@Composable
internal fun MinbarAdminRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val window = remember(context) { context.findAdminActivity()?.window }
    DisposableEffect(window) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val added = window != null && window.attributes.flags and flag == 0
        if (added) window?.addFlags(flag)
        onDispose { if (added) window?.clearFlags(flag) }
    }
    // Keep the platform picker outside the transient verified content. DocumentsUI pauses
    // the Activity and closes the role lease. Never serialize a Uri; a result is consumed
    // only after the same verified account has come back to this in-memory route.
    var draftsFor by remember { mutableStateOf<String?>(null) }
    var pickerErrorFor by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<AdminPickedOriginal?>(null) }
    val documentPicker = rememberAdminOriginalPicker()
    // The proof/URI is memory-only across the picker pause. A fresh verified
    // lease for the same account and exact draft is required before any read.
    var imageAccount by remember { mutableStateOf<String?>(null) }
    var imageOffer by remember { mutableStateOf<DraftImagePickerOffer?>(null) }
    var selectedImage by remember { mutableStateOf<SelectedDraftImage?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = imageOffer
        imageOffer = null
        val returned = result.data
        val uri = returned?.data
        if (result.resultCode == Activity.RESULT_OK && expected != null && imageAccount == expected.account &&
            uri?.scheme == "content" && (returned?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            selectedImage = SelectedDraftImage(expected, uri)
        }
    }
    fun closeImage() {
        imageAccount = null
        imageOffer = null
        selectedImage = null
    }
    fun clearPickerAndDraftRoute() {
        closeImage()
        draftsFor = null
        documentPicker.revokeRoute()
        pickerErrorFor = null
        picked = null
    }

    AdminEntryScreen(onBack = { clearPickerAndDraftRoute(); onBack() }, content = { session, signOut ->
        // Session identity is ephemeral, not a password/bearer saved in a Compose bundle.
        key(session) {
            val account = session.accountKey
            val draftImages = remember(session) { session.guardedTranscriptDraftImage() }
            val imageDrafts = remember(session, draftImages) {
                draftImages?.let { TranscriptDraftPending(context, account) }
            }
            LaunchedEffect(session, draftImages) {
                if (draftsFor != null && draftsFor != account) clearPickerAndDraftRoute()
                if (imageAccount != null && (imageAccount != account || draftImages?.available() != true)) closeImage()
            }
            val publication = remember(session) { session.transcriptPublication() }
            var publicationOpen by remember(session) { mutableStateOf(false) }
            val transcriptReader = remember(session) { session.transcriptPreviewReader() }
            val transcriptRejection = remember(session, transcriptReader) {
                transcriptReader?.let(session::transcriptRejection)
            }
            val transcriptAcceptance = remember(session, transcriptReader) {
                transcriptReader?.let(session::transcriptAcceptance)
            }
            val imagePreview = remember(session) { session.transcriptImagePreview() }
            var transcriptOpen by remember(session) { mutableStateOf(false) }
            val rosterAccess = remember(session) { session.moderatorRosterAccess() }
            val suspensionAccess = remember(session) { session.moderatorSuspensionAccess() }
            var rosterOpen by remember(session) { mutableStateOf(false) }
            val supportInbox = remember(session) { session.guardedSupportInboxAccess() }
            val supportReply = remember(session) { session.guardedSupportReplyClient() }
            var supportOpen by remember(session) { mutableStateOf(false) }
            val generalNotice = remember(session) { session.guardedGeneralNotice() }
            var generalNoticeOpen by remember(session) { mutableStateOf(false) }
            val sectionCreate = remember(session) { session.guardedSectionCreateAccess() }
            var sectionCreateOpen by remember(session) { mutableStateOf(false) }
            val lessonDiscovery = remember(session) { session.guardedNewLessonDiscovery() }
            var lessonDiscoveryOpen by remember(session) { mutableStateOf(false) }
            val guardedInvites = remember(session) { session.guardedInviteInventory() }
            var guardedInvitesOpen by remember(session) { mutableStateOf(false) }
            if (imageAccount == account && draftImages?.available() == true && imageDrafts != null) {
                TranscriptDraftImagePanel(
                    account, draftImages, imageDrafts, selectedImage,
                    choose = { offer ->
                        if (imageOffer == null && offer.account == account && draftImages.available()) {
                            imageOffer = offer
                            selectedImage = null
                            try {
                                imagePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true))
                            } catch (_: Exception) {
                                imageOffer = null
                            }
                        }
                    },
                    consumed = { selectedImage = null },
                    onBack = ::closeImage,
                )
            } else if (publicationOpen && publication?.available() == true) {
                AdminTranscriptPublicationPanel(publication) { publicationOpen = false }
            } else if (rosterOpen && rosterAccess?.available() == true) {
                ModeratorRosterPanel(rosterAccess, suspensionAccess) { rosterOpen = false }
            } else if (guardedInvitesOpen && guardedInvites?.available() == true) {
                GuardedInvitesPanel(guardedInvites) { guardedInvitesOpen = false }
            } else if (lessonDiscoveryOpen && lessonDiscovery?.available() == true) {
                GuardedNewLessonDiscoveryPanel(lessonDiscovery) { lessonDiscoveryOpen = false }
            } else if (generalNoticeOpen && generalNotice?.available() == true) {
                val pendingNotice = remember(context, account) { GeneralNoticePending(context, account) }
                GeneralAnnouncementPanel(generalNotice, pendingNotice) { generalNoticeOpen = false }
            } else if (sectionCreateOpen && sectionCreate?.available() == true) {
                GuardedSectionCreatePanel(sectionCreate) { sectionCreateOpen = false }
            } else if (supportOpen && supportInbox?.available() == true) {
                GuardedSupportInboxPanel(supportInbox,supportReply) { supportOpen = false }
            } else if (transcriptOpen && transcriptReader?.isAvailable() == true) {
                AdminTranscriptPreviewPanel(transcriptReader, transcriptRejection, transcriptAcceptance, imagePreview) { transcriptOpen = false }
            } else if (draftsFor == account) {
                BackHandler { clearPickerAndDraftRoute() }
                val localReviewHost = remember(session, account) {
                    if (session.accountKey == account && session.localOrderedMergeAllowed())
                        LocalOrderedOpusReviewHost(context, account, allowed = {
                            session.accountKey == account && session.localOrderedMergeAllowed()
                        })
                    else null
                }
                DisposableEffect(localReviewHost) { onDispose { localReviewHost?.close() } }
                Column(Modifier.fillMaxSize()) {
                    if (pickerErrorFor == account) {
                        Text("تعذّر فتح منتقي الملفات.", modifier = Modifier.padding(12.dp))
                    }
                    AdminUploadDraftScreen(
                        accountKey = account,
                        session = session,
                        selectedUri = picked?.takeIf { it.accountKey == account }?.uri,
                        onRequestPicker = {
                            val launched = documentPicker.launch(
                                account, currentRoute = { draftsFor },
                                onStart = { pickerErrorFor = null; picked = null },
                                onResult = { owner, uri -> picked = AdminPickedOriginal(owner, uri) },
                            )
                            if (launched == AdminOriginalPicker.Launch.FAILED) pickerErrorFor = account
                        },
                        onDismissSelection = { uri ->
                            if (picked?.let { it.accountKey == account && it.uri == uri } == true) picked = null
                        },
                        onBack = ::clearPickerAndDraftRoute,
                        localReview = localReviewHost?.let { host -> { store -> host.backend(store) } },
                    )
                }
            } else {
            val api = remember(session) {
                AdminWorkspaceApi { path ->
                    try {
                        session.request("GET", path)
                    } catch (e: AdminBridgeFailure) {
                        if (e.statusCode == 401 || e.statusCode == 403) throw AdminAccessRejected()
                        throw e
                    }
                }
            }
            val sectionNames = remember(session) { session.sectionRenameClient() }
            val guardedAlertRead = remember(session) { session.guardedAlertReadAccess() }
            val guardedFeature = remember(session) { session.guardedLessonFeature() }
            val guardedOrder = remember(session) { session.guardedLessonOrder() }
            val transcriptDraft = remember(session) { session.guardedTranscriptDraft() }
            val transcriptPending = remember(context, session, transcriptDraft) {
                transcriptDraft?.let { TranscriptDraftPending(context, account) }
            }
            var transcriptRecoveryOpen by remember(session) { mutableStateOf(false) }
            if (transcriptRecoveryOpen && transcriptDraft?.available() == true && transcriptPending != null) {
                TranscriptDraftPanel(null, transcriptDraft, transcriptPending) { transcriptRecoveryOpen = false }
            }
            AdminWorkspace(
                role = if (session.role == AdminRole.Owner) AdminWorkspaceRole.OWNER else AdminWorkspaceRole.SUPERVISOR,
                api = api,
                sectionNames = sectionNames,
                guardedAlertRead = guardedAlertRead,
                guardedFeature = guardedFeature,
                guardedOrder = guardedOrder,
                transcriptDraft = transcriptDraft,
                transcriptPending = transcriptPending,
                onBack = onBack,
                onSessionRejected = {
                    clearPickerAndDraftRoute()
                    session.invalidate()
                },
                submissionMutation = AdminSubmissionMutationApi { command ->
                    try {
                        session.request("POST", command.path, command.body)
                    } catch (e: AdminBridgeFailure) {
                        if (e.statusCode == 401 || e.statusCode == 403) throw AdminAccessRejected()
                        throw e
                    }
                },
                lessonMutation = if (session.canEditLessonMetadata) AdminLessonMutationApi { command ->
                    try {
                        val result = session.request("PATCH", command.path, command.body)
                        check(result.opt("ok") == true && result.optString("id") == command.lessonId) {
                            "No matching lesson receipt"
                        }
                    } catch (e: AdminBridgeFailure) {
                        when (e.statusCode) {
                            401, 403 -> throw AdminAccessRejected()
                            404, 409 -> throw LessonMetadataStale()
                            else -> throw e
                        }
                    }
                } else null,
                trashRestore = if (session.canRestoreArchivedLesson) AdminTrashRestoreApi { command ->
                    try {
                        val result = session.request("POST", command.path, command.body)
                        check(result.opt("ok") == true && result.optString("id") == command.lessonId) {
                            "No matching archive restore receipt"
                        }
                    } catch (e: AdminBridgeFailure) {
                        when (e.statusCode) {
                            401, 403 -> throw AdminAccessRejected()
                            404, 409 -> throw ArchiveChanged()
                            400 -> throw ArchiveParentsUnavailable()
                            else -> throw e
                        }
                    }
                } else null,
                sessionTools = {
                    if (draftImages?.available() == true) {
                        TextButton(onClick = { imageAccount = account }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("صورة مسودة النص الخاصة")
                        }
                    }
                    if (transcriptDraft?.available() == true && transcriptPending != null) {
                        TextButton(onClick = { transcriptRecoveryOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("متابعة مسودة النص المعلّقة")
                        }
                    }
                    AdminAccessTools(
                        owner = session.canIssueInvites,
                        issueInvite = { email, name ->
                            check(session.canIssueInvites)
                            session.request("POST", "/admin/invites",
                                JSONObject().put("email", email).put("name", name).put("role", "supervisor"))
                                .issuedCode()
                        },
                        issueLink = {
                            session.request("POST", "/admin/link-codes").issuedCode()
                        },
                    )
                    if (rosterAccess?.available() == true) {
                        TextButton(onClick = { rosterOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("حسابات الإشراف")
                        }
                    }
                    if (guardedInvites?.available() == true) {
                        TextButton(onClick = { guardedInvitesOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("الدعوات غير المستخدمة")
                        }
                    }
                    if (lessonDiscovery?.available() == true) {
                        TextButton(onClick = { lessonDiscoveryOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("تسجيلات بانتظار مراجعة النشر")
                        }
                    }
                    if (generalNotice?.available() == true) {
                        TextButton(onClick = { generalNoticeOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("إنشاء إعلان عام")
                        }
                    }
                    if (sectionCreate?.available() == true) {
                        TextButton(onClick = { sectionCreateOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("إنشاء قسم")
                        }
                    }
                    if (supportInbox?.available() == true) {
                        TextButton(onClick = { supportOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("صندوق دعم منبر")
                        }
                    }
                    if (publication?.available() == true) {
                        TextButton(onClick = { publicationOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("نشر النصوص المعتمدة")
                        }
                    }
                    if (transcriptReader?.isAvailable() == true) {
                        TextButton(onClick = { transcriptOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("مراجعة النصوص المعلّقة")
                        }
                    }
                    TextButton(onClick = {
                        clearPickerAndDraftRoute()
                        draftsFor = account
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("مسودّات رفع الدروس")
                    }
                    AdminAccountDeletionLink()
                    TextButton(onClick = {
                        clearPickerAndDraftRoute()
                        signOut()
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("تسجيل الخروج من الإدارة")
                    }
                },
            )
            }
        }
    })
}

private class AdminPickedOriginal(val accountKey: String, val uri: Uri)

private tailrec fun Context.findAdminActivity(): Activity? = when {
    this is Activity -> this
    this is ContextWrapper && baseContext !== this -> baseContext.findAdminActivity()
    else -> null
}

private fun JSONObject.issuedCode() = AdminIssuedAccessCode(
    code = optString("code"), expiresAtMs = optLong("expiresAtMs"),
)
