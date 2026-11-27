package com.ali.menbaradkshk.ui.adminbridge

import com.ali.menbaradkshk.adminsurface.support.GuardedSupportReplyClient
import com.ali.menbaradkshk.ui.adminbridge.invites.GuardedInviteInventory
import com.ali.menbaradkshk.ui.adminbridge.publication.GuardedNewLessonDiscoveryClient
import com.ali.menbaradkshk.ui.adminbridge.publication.GuardedNewLessonLocalIntent
import com.ali.menbaradkshk.ui.adminbridge.publication.GuardedNewLessonPublicationClient
import com.ali.menbaradkshk.ui.adminbridge.publication.guardedNewLessonOwnerScope
import com.ali.menbaradkshk.adminsurface.sectioncreate.GuardedSectionCreateAccess
import com.ali.menbaradkshk.adminsurface.sectioncreate.SECTION_CREATE_PARENT_PATH
import com.ali.menbaradkshk.adminsurface.sectioncreate.SECTION_CREATE_PATH
import com.ali.menbaradkshk.adminsurface.sectioncreate.SectionCreateConflict
import com.ali.menbaradkshk.adminsurface.support.GuardedSupportInboxAccess
import com.ali.menbaradkshk.adminsurface.support.isGuardedSupportPath
import com.ali.menbaradkshk.adminsurface.announcements.GENERAL_NOTICE_CONFIRM
import com.ali.menbaradkshk.adminsurface.announcements.GENERAL_NOTICE_REVIEW
import com.ali.menbaradkshk.adminsurface.announcements.GENERAL_NOTICE_WIRE_MAX
import com.ali.menbaradkshk.adminsurface.announcements.GeneralNoticeClient
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.GuardedTranscriptDraftClient
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftConflict
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftNotFound
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TRANSCRIPT_DRAFT_MAX_BYTES
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TRANSCRIPT_DRAFT_MAX_RESPONSE_BYTES
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.isGuardedTranscriptDraftMutationPath
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.DraftImageFetch
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.MAX_DRAFT_JPEG_BYTES
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.TranscriptDraftImageClient
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.isDraftImagePath
import com.ali.menbaradkshk.ui.adminbridge.transcriptdraft.readDraftImageBytes
import android.graphics.Bitmap
import android.os.Build
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptPublication
import com.ali.menbaradkshk.adminsurface.review.isTranscriptPublicationPath
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptImagePreview
import com.ali.menbaradkshk.adminsurface.review.decodePendingImage
import com.ali.menbaradkshk.adminsurface.review.isPendingImagePath
import com.ali.menbaradkshk.adminsurface.review.pendingImageAccept
import com.ali.menbaradkshk.adminsurface.review.previewResponseLength
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptPreviewReader
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptRejection
import com.ali.menbaradkshk.adminsurface.review.AdminTranscriptAcceptance
import com.ali.menbaradkshk.adminsurface.review.canAcceptPendingTranscriptReview
import com.ali.menbaradkshk.adminsurface.review.isTranscriptAcceptancePath
import com.ali.menbaradkshk.adminsurface.review.canRejectPendingTranscriptReview
import com.ali.menbaradkshk.adminsurface.review.isTranscriptRejectionPath
import com.ali.menbaradkshk.adminsurface.MODERATOR_ROSTER_PATH
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceRole
import com.ali.menbaradkshk.adminsurface.review.GUARDED_ALERT_FEED
import com.ali.menbaradkshk.adminsurface.review.GUARDED_ALERT_MARK
import com.ali.menbaradkshk.adminsurface.review.GuardedAlertReadAccess
import com.ali.menbaradkshk.adminsurface.MODERATOR_SUSPEND_VERIFY
import com.ali.menbaradkshk.adminsurface.MODERATOR_SUSPEND_WRITE
import com.ali.menbaradkshk.adminsurface.ModeratorSuspensionAccess
import com.ali.menbaradkshk.adminsurface.ModeratorRosterAccess
import com.ali.menbaradkshk.adminsurface.SectionRenameClient
import com.ali.menbaradkshk.adminsurface.SectionRenameTransport
import com.ali.menbaradkshk.adminsurface.isSectionRenamePath
import com.ali.menbaradkshk.adminsurface.review.canReadPendingTranscriptReview
import com.ali.menbaradkshk.adminsurface.review.isTranscriptPreviewPath
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminLongUploadHttpWire
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminLongUploadLease
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminLongUploadOrchestration
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadTransport
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraft
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraftStore
import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadStageClient
import com.ali.menbaradkshk.ui.adminbridge.upload.MAX_STAGE_BYTES
import com.ali.menbaradkshk.ui.adminbridge.upload.PrivateLessonPreparation
import com.ali.menbaradkshk.ui.adminbridge.upload.UploadStageStep
import com.ali.menbaradkshk.ui.adminbridge.upload.UploadStageWire
import com.ali.menbaradkshk.ui.adminbridge.upload.prepareVerifiedDraft
import com.ali.menbaradkshk.ui.adminbridge.upload.proveCommittedPrivateDraft
import com.ali.menbaradkshk.ui.adminbridge.upload.UploadStageIntent
import com.ali.menbaradkshk.ui.adminbridge.upload.UploadStageReceipt
import com.ali.menbaradkshk.ui.adminbridge.upload.detectVerifiedAudioMime
import com.ali.menbaradkshk.ui.adminbridge.upload.exactStageNumber
import com.ali.menbaradkshk.ui.adminbridge.upload.uploadStagePath
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ali.menbaradkshk.ui.adminbridge.feature.GUARDED_LESSON_FEATURE_ANDROID_ON
import com.ali.menbaradkshk.ui.adminbridge.feature.GuardedLessonFeatureClient
import com.ali.menbaradkshk.ui.adminbridge.order.GUARDED_LESSON_ORDER_ANDROID_ON
import com.ali.menbaradkshk.ui.adminbridge.order.GuardedLessonOrderClient

private const val TRANSCRIPT_PUBLICATION_ANDROID_ON = false
private const val TRANSCRIPT_IMAGES_ANDROID_ON = false
private const val SECTION_RENAME_ANDROID_ON = false
private const val MODERATOR_ROSTER_ANDROID_ON = false
private const val SUPPORT_INBOX_ANDROID_ON = false
private const val SECTION_CREATE_ANDROID_ON = false
private const val GENERAL_NOTICE_ANDROID_ON = false
private const val GUARDED_ALERT_MARK_ANDROID_ON = false
private const val GUARDED_TRANSCRIPT_DRAFT_ANDROID_ON = false
private const val GUARDED_TRANSCRIPT_DRAFT_IMAGE_ANDROID_ON = false
private const val MODERATOR_SUSPEND_ANDROID_ON = false

internal enum class AdminRole { Owner, Supervisor }
internal enum class AdminServerCapability(val wireName: String) {
    Invite("canIssueInvites"),
    ManageInvites("canManageGuardedInvites"),
    EditLesson("canEditLessonMetadata"),
    FeatureLesson("canManageGuardedLessonFeature"),
    OrderLesson("canManageGuardedLessonOrder"),
    RestoreLesson("canRestoreArchivedLesson"),
    StageSmall("canStageLessonUploads"),
    AdoptSmall("canAdoptStagedLesson"),
    StageLong("canStageLongLessonUploads"),
    AssembleLong("canAssembleLongLessonUploads"),
    PrepareLong("canPrepareLongLessonUploads"),
    ReadTranscript("canReadPendingTranscriptReview"),
    SaveTranscriptDraft("canSaveGuardedTranscriptDraft"),
    SaveTranscriptDraftImage("canSaveGuardedTranscriptDraftImage"),
    PreviewTranscriptImages("canPreviewPendingTranscriptImages"),
    RejectTranscript("canRejectPendingTranscriptReview"),
    PublishTranscript("canPublishGuardedTranscript"),
    PublishNewLesson("canPublishGuardedNewLessons"),
    DiscoverNewLessons("canDiscoverGuardedNewLessons"),
    AcceptTranscript("canAcceptPendingTranscriptReview"),
    RenameSection("canRenameSections"),
    ReadRoster("canReadModeratorRoster"),
    ReadSupportInbox("canReadGuardedSupportInbox"),
    ReplyGuardedSupport("canReplyGuardedSupport"),
    CreateSection("canCreateGuardedSections"),
    PublishGeneralNotice("canPublishGuardedGeneralNotice"),
    MarkGuardedAlert("canMarkGuardedAlertRead"),
    SuspendModerator("canSuspendModerators"),
}

internal data class AdminGrant(
    val role: AdminRole,
    val canIssueInvites: Boolean,
    val canEditLessonMetadata: Boolean = false,
    val canRestoreArchivedLesson: Boolean = false,
    val accountKey: String = "",
    val stageUploadMaxBytes: Int? = null,
    val canAdoptStagedLesson: Boolean = false,
    val canStageLongLessonUploads: Boolean = false,
    val canAssembleLongLessonUploads: Boolean = false,
    val canPrepareLongLessonUploads: Boolean = false,
    val canReadPendingTranscriptReview: Boolean = false,
    val canPreviewPendingTranscriptImages: Boolean = false,
    val canRejectPendingTranscriptReview: Boolean = false,
    val canPublishGuardedTranscript: Boolean = false,
    val canAcceptPendingTranscriptReview: Boolean = false,
    val canRenameSections: Boolean = false,
    val canReadModeratorRoster: Boolean = false,
    val canReadGuardedSupportInbox: Boolean = false,
    val canReplyGuardedSupport: Boolean = false,
    val canCreateGuardedSections: Boolean = false,
    val canMarkGuardedAlertRead: Boolean = false,
    val canSuspendModerators: Boolean = false,
    val newLessonOwnerScope: String? = null,
    val canDiscoverGuardedNewLessons: Boolean = false,
    val canManageGuardedInvites: Boolean = false,
    val canManageGuardedLessonFeature: Boolean = false,
    val canManageGuardedLessonOrder: Boolean = false,
    val canPublishGuardedGeneralNotice: Boolean = false,
    val canSaveGuardedTranscriptDraft: Boolean = false,
    val canSaveGuardedTranscriptDraftImage: Boolean = false,
)

internal fun decodeAdminGrant(response: JSONObject): AdminGrant? {
    val who = response.optString("who").trim().lowercase(Locale.ROOT)
    if (who.length !in 3..254 || who.count { it == '@' } != 1 ||
        who.startsWith('@') || who.endsWith('@') || who.any { it.isWhitespace() || it.isISOControl() }) return null
    val role = verifiedAdminRole(response.optString("role")) ?: return null
    return AdminGrant(
        role = role,
        canIssueInvites = role == AdminRole.Owner && response.opt("canIssueInvites") == true,
        canEditLessonMetadata = response.opt("canEditLessonMetadata") == true,
        canRestoreArchivedLesson = response.opt("canRestoreArchivedLesson") == true,
        stageUploadMaxBytes = exactStageNumber(response.opt("guardedLessonUploadMaxBytes"))
            ?.takeIf { response.opt("canStageLessonUploads") == true && it in 1..MAX_STAGE_BYTES.toLong() }
            ?.toInt(),
        canAdoptStagedLesson = response.opt("canAdoptStagedLesson") == true,
        canStageLongLessonUploads = response.opt("canStageLongLessonUploads") == true,
        canAssembleLongLessonUploads = response.opt("canAssembleLongLessonUploads") == true,
        canPrepareLongLessonUploads = response.opt("canPrepareLongLessonUploads") == true,
        canReadPendingTranscriptReview = canReadPendingTranscriptReview(response),
        canPreviewPendingTranscriptImages = response.opt("canPreviewPendingTranscriptImages") == true,
        canRejectPendingTranscriptReview = canRejectPendingTranscriptReview(response),
        canPublishGuardedTranscript = role == AdminRole.Owner && response.opt("canPublishGuardedTranscript") == true,
        canAcceptPendingTranscriptReview = canAcceptPendingTranscriptReview(response),
        canRenameSections = role == AdminRole.Owner && response.opt("canRenameSections") == true,
        canReadModeratorRoster = role == AdminRole.Owner && response.opt("canReadModeratorRoster") == true,
        canReadGuardedSupportInbox = role == AdminRole.Owner && response.opt("canReadGuardedSupportInbox") == true,
        canReplyGuardedSupport = role == AdminRole.Owner && response.opt("canReplyGuardedSupport") == true,
        canCreateGuardedSections = role == AdminRole.Owner && response.opt("canCreateGuardedSections") == true,
        canSaveGuardedTranscriptDraft = role == AdminRole.Owner && response.opt("canSaveGuardedTranscriptDraft") == true,
        canSaveGuardedTranscriptDraftImage = role == AdminRole.Owner && response.opt("canSaveGuardedTranscriptDraftImage") == true,
        canPublishGuardedGeneralNotice = role == AdminRole.Owner && response.opt("canPublishGuardedGeneralNotice") == true,
        canMarkGuardedAlertRead = response.opt("canMarkGuardedAlertRead") == true,
        canSuspendModerators = role == AdminRole.Owner && response.opt("canSuspendModerators") == true,
        canManageGuardedInvites = role == AdminRole.Owner && response.opt("canManageGuardedInvites") == true,
        canManageGuardedLessonFeature = role == AdminRole.Owner && response.opt("canManageGuardedLessonFeature") == true,
        canManageGuardedLessonOrder = role == AdminRole.Owner && response.opt("canManageGuardedLessonOrder") == true,
        canDiscoverGuardedNewLessons = role == AdminRole.Owner && response.opt("canDiscoverGuardedNewLessons") == true,
        newLessonOwnerScope = if (role == AdminRole.Owner && response.opt("canPublishGuardedNewLessons") == true)
            guardedNewLessonOwnerScope(who) else null,
        accountKey = MessageDigest.getInstance("SHA-256")
            .digest("minbar-admin-upload:v1:$who".toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
    )
}

internal fun verifiedAdminRole(role: String): AdminRole? = when (role) {
    "owner" -> AdminRole.Owner
    "supervisor", "admin" -> AdminRole.Supervisor
    else -> null
}

internal fun normalizedAdminCode(code: String): String? =
    code.trim().uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-HJ-NP-Z2-9]{8}")) }

internal class AdminBridgeFailure(val statusCode: Int) : IOException("Admin request failed")

/** A session capability, not a source of credentials. The Worker checks roles on every operation. */
internal fun AdminGrant.openVerifiedSession(token: String, onRejected: () -> Unit): VerifiedAdminSession =
    VerifiedAdminSession(sessionGrant = this, token = token, onRejected = onRejected)

/** The same decoded immutable grant is used by the whole verified session, not a positional Boolean list. */
internal class VerifiedAdminSession internal constructor(
    private val sessionGrant: AdminGrant,
    private val token: String,
    private val onRejected: () -> Unit,
) {
    val role: AdminRole get() = sessionGrant.role
    val accountKey: String get() = sessionGrant.accountKey
    private val stageLock = Mutex()
    private val isOpen = AtomicBoolean(true)
    private val open: Boolean get() = isOpen.get()

    internal fun hasServerCapability(cap: AdminServerCapability): Boolean = open && when (cap) {
        AdminServerCapability.Invite -> sessionGrant.canIssueInvites
        AdminServerCapability.ManageInvites -> sessionGrant.canManageGuardedInvites
        AdminServerCapability.EditLesson -> sessionGrant.canEditLessonMetadata
        AdminServerCapability.FeatureLesson -> sessionGrant.canManageGuardedLessonFeature
        AdminServerCapability.OrderLesson -> sessionGrant.canManageGuardedLessonOrder
        AdminServerCapability.RestoreLesson -> sessionGrant.canRestoreArchivedLesson
        AdminServerCapability.StageSmall -> sessionGrant.stageUploadMaxBytes != null
        AdminServerCapability.AdoptSmall -> sessionGrant.canAdoptStagedLesson
        AdminServerCapability.StageLong -> sessionGrant.canStageLongLessonUploads
        AdminServerCapability.AssembleLong -> sessionGrant.canAssembleLongLessonUploads
        AdminServerCapability.PrepareLong -> sessionGrant.canPrepareLongLessonUploads
        AdminServerCapability.ReadTranscript -> sessionGrant.canReadPendingTranscriptReview
        AdminServerCapability.SaveTranscriptDraft -> sessionGrant.canSaveGuardedTranscriptDraft
        AdminServerCapability.SaveTranscriptDraftImage -> sessionGrant.canSaveGuardedTranscriptDraftImage
        AdminServerCapability.PreviewTranscriptImages -> sessionGrant.canPreviewPendingTranscriptImages
        AdminServerCapability.RejectTranscript -> sessionGrant.canRejectPendingTranscriptReview
        AdminServerCapability.PublishTranscript -> sessionGrant.canPublishGuardedTranscript
        AdminServerCapability.PublishNewLesson -> sessionGrant.newLessonOwnerScope != null
        AdminServerCapability.DiscoverNewLessons -> sessionGrant.canDiscoverGuardedNewLessons
        AdminServerCapability.AcceptTranscript -> sessionGrant.canAcceptPendingTranscriptReview
        AdminServerCapability.RenameSection -> sessionGrant.canRenameSections
        AdminServerCapability.ReadRoster -> sessionGrant.canReadModeratorRoster
        AdminServerCapability.ReadSupportInbox -> sessionGrant.canReadGuardedSupportInbox
        AdminServerCapability.ReplyGuardedSupport -> sessionGrant.canReplyGuardedSupport
        AdminServerCapability.CreateSection -> sessionGrant.canCreateGuardedSections
        AdminServerCapability.PublishGeneralNotice -> sessionGrant.canPublishGuardedGeneralNotice
        AdminServerCapability.MarkGuardedAlert -> sessionGrant.canMarkGuardedAlertRead
        AdminServerCapability.SuspendModerator -> sessionGrant.canSuspendModerators
    }

    val canIssueInvites: Boolean get() = hasServerCapability(AdminServerCapability.Invite)
    val canEditLessonMetadata: Boolean get() = hasServerCapability(AdminServerCapability.EditLesson)
    val canRestoreArchivedLesson: Boolean get() = hasServerCapability(AdminServerCapability.RestoreLesson)

    private val longCalls = AdminLongNetworkCalls()
    private val longLease = if (privateLongUploadAllowed(
            PRIVATE_LONG_ANDROID_ON, hasServerCapability(AdminServerCapability.StageLong), token, accountKey,
        )) AdminLongUploadLease(accountKey) else null

    private fun closeOnce(): Boolean {
        if (!isOpen.compareAndSet(true, false)) return false
        longCalls.close()
        return true
    }

    internal fun close() { closeOnce() }
    /**
     * Local ordered merge/preview (device-only, never uploads) is offered to any open,
     * server-verified admin session (owner or supervisor via /admin/whoami). The production
     * Worker's whoami returns only {who, role}, so staging capabilities cannot be required here.
     */
    internal fun localOrderedMergeAllowed(): Boolean = open &&
        (role == AdminRole.Owner || role == AdminRole.Supervisor) &&
        accountKey.matches(Regex("[0-9a-f]{64}"))
    internal fun invalidate() {
        if (closeOnce()) onRejected()
    }

    fun guardedLessonOrder(): GuardedLessonOrderClient? {
        fun allowed() = GUARDED_LESSON_ORDER_ANDROID_ON && open && role == AdminRole.Owner &&
            hasServerCapability(AdminServerCapability.OrderLesson) && token.matches(Regex("ms_[0-9a-f]{64}"))
        if (!allowed()) return null
        return GuardedLessonOrderClient(::allowed, LongUploadTransport { request, guard ->
            try {
                AdminLongNetwork.exchangeGuardedLessonOrder(request, token, longCalls, guard)
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        })
    }

    fun guardedLessonFeature(): GuardedLessonFeatureClient? {
        fun allowed() = GUARDED_LESSON_FEATURE_ANDROID_ON && open && role == AdminRole.Owner &&
            hasServerCapability(AdminServerCapability.FeatureLesson) && token.matches(Regex("ms_[0-9a-f]{64}"))
        if (!allowed()) return null
        return GuardedLessonFeatureClient(::allowed, LongUploadTransport { request, guard ->
            try {
                AdminLongNetwork.exchangeGuardedLessonFeature(request, token, longCalls, guard)
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        })
    }

    fun guardedNewLessonPublication(store: AdminUploadDraftStore): GuardedNewLessonPublicationClient? {
        val actor = sessionGrant.newLessonOwnerScope ?: return null
        fun allowed() = GUARDED_NEW_LESSON_ANDROID_ON && open && role == AdminRole.Owner &&
            hasServerCapability(AdminServerCapability.PublishNewLesson) && store.isForAccount(accountKey) &&
            token.matches(Regex("ms_[0-9a-f]{64}"))
        if (!allowed()) return null
        return GuardedNewLessonPublicationClient(
            ownerScope = actor,
            active = ::allowed,
            exactLocalPreparation = { draft ->
                withContext(Dispatchers.IO) {
                    check(allowed())
                    store.confirmedLongPreparation(draft)?.let {
                        GuardedNewLessonLocalIntent(it.title,it.speaker,it.description,
                            it.expectedCategoryName,it.expectedSubcategoryName)
                    }
                }
            },
            transport = LongUploadTransport { request, guard ->
                try { AdminLongNetwork.exchangeGuardedNewLesson(request, token, longCalls, guard) }
                catch (e: AdminBridgeFailure) {
                    // 403 also means an unready or stale sealed result, not necessarily invalid credentials.
                    if (e.statusCode == 401) invalidate()
                    throw e
                }
            },
        )
    }

    fun guardedInviteInventory(): GuardedInviteInventory? {
        fun allowed() = GUARDED_INVITES_ANDROID_ON && open && role == AdminRole.Owner &&
            hasServerCapability(AdminServerCapability.ManageInvites) && token.matches(Regex("ms_[0-9a-f]{64}"))
        if (!allowed()) return null
        return GuardedInviteInventory(::allowed, LongUploadTransport { request, guard ->
            try { AdminLongNetwork.exchangeGuardedInvites(request, token, longCalls, guard) }
            catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        })
    }

    fun guardedNewLessonDiscovery(): GuardedNewLessonDiscoveryClient? {
        fun allowed() = GUARDED_NEW_LESSON_ANDROID_ON && GUARDED_LESSON_DISCOVERY_ANDROID_ON &&
            open && role == AdminRole.Owner && hasServerCapability(AdminServerCapability.PublishNewLesson) &&
            hasServerCapability(AdminServerCapability.DiscoverNewLessons) && token.matches(Regex("ms_[0-9a-f]{64}"))
        if (!allowed()) return null
        return GuardedNewLessonDiscoveryClient(::allowed, LongUploadTransport { request, guard ->
            try { AdminLongNetwork.exchangeGuardedNewLessonDiscovery(request, token, longCalls, guard) }
            catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401) invalidate()
                throw e
            }
        })
    }

    fun longPrivateUpload(store: AdminUploadDraftStore): AdminLongUploadOrchestration? {
        val lease = longLease ?: return null
        if (!open || !store.isForAccount(accountKey)) return null
        val wire = AdminLongUploadHttpWire(LongUploadTransport { request, guard ->
            try {
                AdminLongNetwork.exchange(request, token, longCalls, guard)
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        })
        val assembly = wire.takeIf { PRIVATE_LONG_ASSEMBLY_ANDROID_ON && hasServerCapability(AdminServerCapability.AssembleLong) }
        val preparation = assembly?.takeIf { PRIVATE_LONG_PREPARE_ANDROID_ON && hasServerCapability(AdminServerCapability.PrepareLong) }
        return AdminLongUploadOrchestration(
            store, { lease.takeIf { open } }, wire, stageLock,
            assemblyWire = assembly,
            currentAssemblyLease = { lease.takeIf { open && assembly != null } },
            prepareWire = preparation,
            currentPrepareLease = { lease.takeIf { open && preparation != null } },
        )
    }

    val canImportSharedAudioDraft: Boolean
        get() = open && accountKey.matches(Regex("[a-f0-9]{64}")) &&
            token.matches(Regex("ms_[a-f0-9]{64}")) &&
            (privateUploadMaxBytes != null || longLease != null)

    val privateUploadMaxBytes: Int?
        get() = sessionGrant.stageUploadMaxBytes?.takeIf { open && token.startsWith("ms_") && token.length >= 40 }

    val canAdoptStagedLesson: Boolean
        get() = hasServerCapability(AdminServerCapability.AdoptSmall) && privateUploadMaxBytes != null

    private fun checkPreparationScope(store: AdminUploadDraftStore, draft: AdminUploadDraft) {
        check(canAdoptStagedLesson && store.isForAccount(accountKey) &&
            privateUploadMaxBytes?.let { draft.byteCount in 1..it.toLong() } == true)
    }

    private suspend fun preparationCall(
        store: AdminUploadDraftStore, draft: AdminUploadDraft, payload: JSONObject? = null,
    ): JSONObject {
        val base = uploadStagePath(draft.id, UploadStageStep.Verify)
        val suffix = if (payload == null) "" else "/adopt"
        return try {
            AdminBridgeApi.send(if (payload == null) "GET" else "POST", base + suffix, payload, token,
                responseLimit = 8192, beforeOpen = { checkPreparationScope(store, draft) })
                .also { checkPreparationScope(store, draft) }
        } catch (e: AdminBridgeFailure) {
            if (e.statusCode == 401 || e.statusCode == 403) invalidate()
            throw e
        }
    }

    suspend fun verifyCommittedForPreparation(store: AdminUploadDraftStore, draft: AdminUploadDraft): UploadStageReceipt =
        withContext(Dispatchers.IO) {
            checkPreparationScope(store, draft)
            check(store.confirmedIntent(draft) != null)
            proveCommittedPrivateDraft(draft, preparationCall(store, draft))
        }

    /** Invoke only on a separate operator confirmation after proving the existing private draft. */
    suspend fun preparePrivateLesson(
        store: AdminUploadDraftStore, draft: AdminUploadDraft, receipt: UploadStageReceipt,
    ): PrivateLessonPreparation = withContext(Dispatchers.IO) {
        check(stageLock.tryLock()) { "A private draft attempt is already running" }
        try {
            checkPreparationScope(store, draft)
            check(store.confirmedIntent(draft) != null)
            prepareVerifiedDraft(draft, receipt,
                getCommitted = { preparationCall(store, draft) },
                postAdopt = { preparationCall(store, draft, it) })
        } finally {
            stageLock.unlock()
        }
    }

    suspend fun inspectPrivateUpload(store: AdminUploadDraftStore, id: String): Pair<AdminUploadDraft, String> =
        withContext(Dispatchers.IO) {
            val limit = privateUploadMaxBytes ?: throw IOException("Private draft staging unavailable")
            require(store.isForAccount(accountKey))
            val (draft, payload) = store.stageSnapshot(id, limit)
            try {
                val mime = detectVerifiedAudioMime(payload) ?: throw IOException("Original audio container not verified")
                check(privateUploadMaxBytes == limit) { "Admin session is not active" }
                draft to mime
            } finally {
                payload.fill(0)
            }
        }

    /** Call only for a currently displayed intent explicitly confirmed by the current operator. */
    suspend fun stagePrivateUpload(
        store: AdminUploadDraftStore, id: String, intent: UploadStageIntent,
        offlineTestWire: UploadStageWire? = null,
    ): UploadStageReceipt =
        withContext(Dispatchers.IO) {
            check(stageLock.tryLock()) { "A private draft attempt is already running" }
            try {
                val limit = privateUploadMaxBytes ?: throw IOException("Private draft staging unavailable")
                require(store.isForAccount(accountKey))
                val (draft, payload) = store.stageSnapshot(id, limit)
                try {
                    if (detectVerifiedAudioMime(payload) != intent.mimeType)
                        throw IOException("Confirmed audio MIME does not match the original")
                    currentCoroutineContext().ensureActive()
                    check(privateUploadMaxBytes == limit) { "Admin session is not active" }
                    store.freezeConfirmedIntent(draft, intent)
                    val wire = offlineTestWire ?: UploadStageWire { step, draftId, bytes, mime ->
                        try {
                            AdminBridgeApi.send(step.method, uploadStagePath(draftId, step), token = token,
                                rawBody = bytes, rawMime = mime, responseLimit = 8192,
                                beforeOpen = { check(privateUploadMaxBytes == limit) { "Admin session is not active" } })
                                .also { check(privateUploadMaxBytes == limit) { "Admin session is not active" } }
                        } catch (e: AdminBridgeFailure) {
                            if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                            throw e
                        }
                    }
                    AdminUploadStageClient(wire).submit(intent, draft, payload)
                } finally {
                    payload.fill(0)
                }
            } finally {
                stageLock.unlock()
            }
        }

    private fun transcriptPreviewAllowed(): Boolean =
        open && hasServerCapability(AdminServerCapability.ReadTranscript) && token.matches(Regex("ms_[0-9a-f]{64}"))

    fun transcriptPreviewReader(): AdminTranscriptPreviewReader? {
        if (!transcriptPreviewAllowed()) return null
        return AdminTranscriptPreviewReader(::transcriptPreviewAllowed) { path ->
            require(isTranscriptPreviewPath(path))
            try {
                AdminBridgeApi.send("GET", path, token = token,
                    responseLimit = 125_000, requirePrivateJson = true,
                    beforeOpen = { check(transcriptPreviewAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun transcriptPublicationAllowed(): Boolean = TRANSCRIPT_PUBLICATION_ANDROID_ON &&
        open && role == AdminRole.Owner && token.matches(Regex("ms_[0-9a-f]{64}")) &&
        hasServerCapability(AdminServerCapability.PublishTranscript)

    fun transcriptPublication(): AdminTranscriptPublication? {
        if (!transcriptPublicationAllowed()) return null
        return AdminTranscriptPublication(::transcriptPublicationAllowed) { method, path, body ->
            require(isTranscriptPublicationPath(method, path))
            try {
                AdminBridgeApi.send(method, path, body = body, token = token, responseLimit = 125_000,
                    requirePrivateJson = true, beforeOpen = { check(transcriptPublicationAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun transcriptImagesAllowed(): Boolean = TRANSCRIPT_IMAGES_ANDROID_ON &&
        transcriptPreviewAllowed() && hasServerCapability(AdminServerCapability.PreviewTranscriptImages)

    fun transcriptImagePreview(): AdminTranscriptImagePreview? {
        if (!transcriptImagesAllowed()) return null
        return AdminTranscriptImagePreview(::transcriptImagesAllowed) { path ->
            try {
                AdminBridgeApi.fetchPrivateImage(path, token, ::transcriptImagesAllowed)
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun sectionRenameAllowed(): Boolean = SECTION_RENAME_ANDROID_ON && open &&
        role == AdminRole.Owner && hasServerCapability(AdminServerCapability.RenameSection) && token.matches(Regex("ms_[0-9a-f]{64}"))

    fun sectionRenameClient(): SectionRenameClient? {
        if (!sectionRenameAllowed()) return null
        return SectionRenameClient(SectionRenameTransport { method, path, body ->
            require(isSectionRenamePath(path) && (method == "GET" && body == null || method == "PATCH" && body != null))
            try {
                AdminBridgeApi.send(method, path, body, token, responseLimit = 8_192, requirePrivateJson = true,
                    beforeOpen = { check(sectionRenameAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }, ::sectionRenameAllowed)
    }

    private fun transcriptRejectionAllowed(): Boolean =
        transcriptPreviewAllowed() && hasServerCapability(AdminServerCapability.RejectTranscript)

    fun transcriptRejection(reader: AdminTranscriptPreviewReader): AdminTranscriptRejection? {
        if (!transcriptRejectionAllowed()) return null
        return AdminTranscriptRejection(reader, ::transcriptRejectionAllowed) { method, path, body ->
            require(isTranscriptRejectionPath(method, path) && (body != null) == (method == "POST"))
            try {
                AdminBridgeApi.send(method, path, body, token = token,
                    responseLimit = 4096, requirePrivateJson = true,
                    beforeOpen = { check(transcriptRejectionAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun transcriptAcceptanceAllowed(): Boolean =
        transcriptPreviewAllowed() && hasServerCapability(AdminServerCapability.AcceptTranscript)

    fun transcriptAcceptance(reader: AdminTranscriptPreviewReader): AdminTranscriptAcceptance? {
        if (!transcriptAcceptanceAllowed()) return null
        return AdminTranscriptAcceptance(reader, ::transcriptAcceptanceAllowed) { method, path, body ->
            require(isTranscriptAcceptancePath(method, path) && (body != null) == (method == "POST"))
            try {
                AdminBridgeApi.send(method, path, body, token = token,
                    responseLimit = 4096, requirePrivateJson = true,
                    beforeOpen = { check(transcriptAcceptanceAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun transcriptDraftAllowed(): Boolean = GUARDED_TRANSCRIPT_DRAFT_ANDROID_ON && open &&
        role == AdminRole.Owner && hasServerCapability(AdminServerCapability.SaveTranscriptDraft) &&
        token.matches(Regex("ms_[0-9a-f]{64}"))

    fun guardedTranscriptDraft(): GuardedTranscriptDraftClient? {
        if (!transcriptDraftAllowed()) return null
        return GuardedTranscriptDraftClient(::transcriptDraftAllowed) { method, path, raw ->
            require(isGuardedTranscriptDraftMutationPath(path))
            require((method == "GET" && raw == null) ||
                (method == "POST" && raw != null && raw.size in 1..TRANSCRIPT_DRAFT_MAX_BYTES))
            try {
                val response = AdminBridgeApi.send(
                    method, path, token = token, rawBody = raw, rawMime = raw?.let { "application/json" },
                    responseLimit = TRANSCRIPT_DRAFT_MAX_RESPONSE_BYTES, requirePrivateJson = true,
                    acceptedStatuses = if (method == "POST") setOf(200, 201) else setOf(200),
                    beforeOpen = { check(transcriptDraftAllowed()) },
                )
                response.keys().asSequence().associateWith { response.opt(it) }
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                if (e.statusCode == 409 && method == "POST") throw TranscriptDraftConflict()
                if (e.statusCode == 404 && method == "GET") throw TranscriptDraftNotFound()
                throw e
            }
        }
    }

    private fun transcriptDraftImageAllowed(): Boolean = GUARDED_TRANSCRIPT_DRAFT_IMAGE_ANDROID_ON &&
        transcriptDraftAllowed() && hasServerCapability(AdminServerCapability.SaveTranscriptDraftImage)

    fun guardedTranscriptDraftImage(): TranscriptDraftImageClient? {
        if (!transcriptDraftImageAllowed()) return null
        return TranscriptDraftImageClient(
            active = ::transcriptDraftImageAllowed,
            put = { path, bytes ->
                require(isDraftImagePath(path) && bytes.size in 1..MAX_DRAFT_JPEG_BYTES)
                try {
                    val response = AdminBridgeApi.send(
                        "PUT", path, token = token, rawBody = bytes, rawMime = "image/jpeg",
                        responseLimit = 2048, requirePrivateJson = true, acceptedStatuses = setOf(200, 201),
                        beforeOpen = { check(transcriptDraftImageAllowed()) },
                    )
                    response.keys().asSequence().associateWith { response.opt(it) }
                } catch (e: AdminBridgeFailure) {
                    if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                    throw e
                }
            },
            get = { path ->
                try {
                    AdminBridgeApi.draftImage(path, token, ::transcriptDraftImageAllowed)
                } catch (e: AdminBridgeFailure) {
                    if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                    throw e
                }
            },
        )
    }

    private fun generalNoticeAllowed(): Boolean = GENERAL_NOTICE_ANDROID_ON && open &&
        role == AdminRole.Owner && hasServerCapability(AdminServerCapability.PublishGeneralNotice) &&
        token.matches(Regex("ms_[a-f0-9]{64}"))

    fun guardedGeneralNotice(): GeneralNoticeClient? {
        if (!generalNoticeAllowed()) return null
        return GeneralNoticeClient(::generalNoticeAllowed, { path, canonicalBody ->
            require(path == GENERAL_NOTICE_REVIEW || path == GENERAL_NOTICE_CONFIRM)
            require(canonicalBody.size in 1..GENERAL_NOTICE_WIRE_MAX)
            try {
                val response = AdminBridgeApi.send("POST", path, token = token, rawBody = canonicalBody, rawMime = "application/json",
                    responseLimit = GENERAL_NOTICE_WIRE_MAX, requirePrivateJson = true,
                    beforeOpen = { check(generalNoticeAllowed()) })
                response.keys().asSequence().associateWith { response.opt(it) }
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        })
    }

    private fun guardedSectionCreateAllowed(): Boolean = SECTION_CREATE_ANDROID_ON && open &&
        role == AdminRole.Owner && hasServerCapability(AdminServerCapability.CreateSection) &&
        token.matches(Regex("ms_[0-9a-f]{64}"))

    fun guardedSectionCreateAccess(): GuardedSectionCreateAccess? {
        if (!guardedSectionCreateAllowed()) return null
        return GuardedSectionCreateAccess(::guardedSectionCreateAllowed) { method, path, body ->
            require((method == "GET" && path == SECTION_CREATE_PARENT_PATH && body == null) ||
                (method == "POST" && path == SECTION_CREATE_PATH && body != null))
            try {
                AdminBridgeApi.send(method, path, body, token, responseLimit = if (body == null) 350_000 else 4096,
                    requirePrivateJson = true, beforeOpen = { check(guardedSectionCreateAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                if (e.statusCode == 409) throw SectionCreateConflict()
                throw e
            }
        }
    }

    private fun guardedSupportAllowed(): Boolean = SUPPORT_INBOX_ANDROID_ON && open &&
        role == AdminRole.Owner && hasServerCapability(AdminServerCapability.ReadSupportInbox) &&
        token.matches(Regex("ms_[0-9a-f]{64}"))

    fun guardedSupportReplyClient(): GuardedSupportReplyClient? {
        fun allowed() = GUARDED_SUPPORT_REPLY_ANDROID_ON && guardedSupportAllowed() &&
            hasServerCapability(AdminServerCapability.ReplyGuardedSupport)
        if (!allowed()) return null
        return GuardedSupportReplyClient(::allowed,LongUploadTransport { request, guard ->
            try { AdminLongNetwork.exchangeGuardedSupportReply(request,token,longCalls,guard) }
            catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        })
    }

    fun guardedSupportInboxAccess(): GuardedSupportInboxAccess? {
        if (!guardedSupportAllowed()) return null
        return GuardedSupportInboxAccess(::guardedSupportAllowed) { path ->
            require(isGuardedSupportPath(path))
            try {
                AdminBridgeApi.send("GET", path, token = token, responseLimit = 1_300_000,
                    requirePrivateJson = true, beforeOpen = { check(guardedSupportAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun moderatorSuspendAllowed(): Boolean = MODERATOR_SUSPEND_ANDROID_ON && moderatorRosterAllowed() &&
        hasServerCapability(AdminServerCapability.SuspendModerator)

    fun moderatorSuspensionAccess(): ModeratorSuspensionAccess? {
        if (!moderatorSuspendAllowed()) return null
        return ModeratorSuspensionAccess(::moderatorSuspendAllowed) { path, body ->
            require(path == MODERATOR_SUSPEND_VERIFY || path == MODERATOR_SUSPEND_WRITE)
            try {
                AdminBridgeApi.send("POST", path, body, token, responseLimit = 4096,
                    requirePrivateJson = true, beforeOpen = { check(moderatorSuspendAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun moderatorRosterAllowed(): Boolean = MODERATOR_ROSTER_ANDROID_ON && open &&
        role == AdminRole.Owner && hasServerCapability(AdminServerCapability.ReadRoster) && token.matches(Regex("ms_[0-9a-f]{64}"))

    fun moderatorRosterAccess(): ModeratorRosterAccess? {
        if (!moderatorRosterAllowed()) return null
        return ModeratorRosterAccess(::moderatorRosterAllowed) {
            try {
                AdminBridgeApi.send("GET", MODERATOR_ROSTER_PATH, token = token, responseLimit = 24_576,
                    requirePrivateJson = true, beforeOpen = { check(moderatorRosterAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    private fun guardedAlertMarkAllowed(): Boolean = GUARDED_ALERT_MARK_ANDROID_ON && open &&
        hasServerCapability(AdminServerCapability.MarkGuardedAlert) && token.matches(Regex("ms_[0-9a-f]{64}"))

    fun guardedAlertReadAccess(): GuardedAlertReadAccess? {
        if (!guardedAlertMarkAllowed()) return null
        val audienceRole = if (role == AdminRole.Owner) AdminWorkspaceRole.OWNER else AdminWorkspaceRole.SUPERVISOR
        return GuardedAlertReadAccess(audienceRole, ::guardedAlertMarkAllowed) { path, body ->
            require(path == GUARDED_ALERT_FEED && body == null || path == GUARDED_ALERT_MARK && body != null)
            try {
                AdminBridgeApi.send(if (body == null) "GET" else "POST", path, body, token, responseLimit = 32_768,
                    requirePrivateJson = true, beforeOpen = { check(guardedAlertMarkAllowed()) })
            } catch (e: AdminBridgeFailure) {
                if (e.statusCode == 401 || e.statusCode == 403) invalidate()
                throw e
            }
        }
    }

    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        check(open) { "Admin session is not active" }
        require(!isGuardedTranscriptDraftMutationPath(path) &&
            path.startsWith("/admin/") && path.substringBefore('?').split('/').none {
            it == "." || it == ".." || it.contains('%') || it.contains('\\')
        })
        return try {
            val result = AdminBridgeApi.send(method, path, body, token)
            check(open) { "Admin session is not active" }
            result
        } catch (e: AdminBridgeFailure) {
            if (e.statusCode == 401 || e.statusCode == 403) {
                invalidate()
            }
            throw e
        }
    }
}

internal object AdminBridgeApi {
    internal const val ORIGIN = "https://minbar-api.mushafak.workers.dev"
    private const val LIMIT = 4 * 1024 * 1024
    private fun validBearer(token: String): Boolean =
        token.length in 16..8192 && token.all { it in '!'..'~' }
    private fun validToken(token: String): Boolean = token.length <= 2048 && validBearer(token)

    suspend fun redeem(code: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", requireNotNull(normalizedAdminCode(code)))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80))
        send("POST", "/access/redeem", payload).optString("token").takeIf(::validToken)
            ?: throw IOException("Invalid session response")
    }

    /** Integration seam only. Pass a verified provider token from approved OAuth configuration. */
    suspend fun exchangeIdentity(identityBearer: String): String =
        send("POST", "/admin/session/exchange",
            JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)),
            identityBearer).optString("token").takeIf(::validToken)
            ?: throw IOException("Invalid session response")

    suspend fun verify(token: String): AdminGrant? =
        decodeAdminGrant(send("GET", "/admin/whoami", token = token))

    suspend fun revoke(token: String) {
        send("DELETE", "/admin/session", token = token)
    }

    internal suspend fun fetchPrivateImage(path: String, token: String, permitted: () -> Boolean): Bitmap =
        withContext(Dispatchers.IO) {
            require(isPendingImagePath(path) && token.matches(Regex("ms_[0-9a-f]{64}")))
            currentCoroutineContext().ensureActive()
            check(permitted())
            val connection = URL(ORIGIN + path).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", pendingImageAccept)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-store")
                connection.setRequestProperty("Authorization", "Bearer $token")
                check(permitted())
                val status = connection.responseCode
                if (status != 200) throw AdminBridgeFailure(status)
                check(permitted())
                val mime = connection.getHeaderField("Content-Type")
                val size = previewResponseLength(mime, connection.getHeaderField("Content-Length"),
                    connection.getHeaderField("Cache-Control"), connection.getHeaderField("Content-Encoding"))
                connection.inputStream.use { decodePendingImage(it, size, requireNotNull(mime), permitted) }
            } finally {
                connection.disconnect()
            }
        }

    internal suspend fun draftImage(path: String, token: String, active: () -> Boolean): DraftImageFetch =
        withContext(Dispatchers.IO) {
            require(isDraftImagePath(path) && token.matches(Regex("ms_[0-9a-f]{64}")))
            val job = currentCoroutineContext()
            fun checkOpen() { job.ensureActive(); check(active()) }
            checkOpen()
            val conn = URL(ORIGIN + path).openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.useCaches = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "image/jpeg")
                conn.setRequestProperty("Accept-Encoding", "identity")
                val code = conn.responseCode
                if (code != 200) throw AdminBridgeFailure(code)
                checkOpen()
                val length = conn.getHeaderField("Content-Length")
                val count = length?.toIntOrNull() ?: error("No exact image length")
                require(count in 1..MAX_DRAFT_JPEG_BYTES && count.toString() == length)
                require(conn.getHeaderField("Content-Encoding") == null &&
                    conn.getHeaderField("Content-Type") == "image/jpeg" &&
                    conn.getHeaderField("X-Content-Type-Options") == "nosniff")
                val bytes = conn.inputStream.use { readDraftImageBytes(it, count, ::checkOpen) }
                checkOpen()
                DraftImageFetch(bytes, conn.getHeaderField("Content-Type"), length,
                    conn.getHeaderField("Cache-Control"), conn.getHeaderField("X-Content-Type-Options"),
                    conn.getHeaderField("Content-Disposition"))
            } finally { conn.disconnect() }
        }

    internal suspend fun send(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        rawBody: ByteArray? = null,
        rawMime: String? = null,
        responseLimit: Int = LIMIT,
        requirePrivateJson: Boolean = false,
        acceptedStatuses: Set<Int>? = null,
        beforeOpen: () -> Unit = {},
    ): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.startsWith("//") &&
            path.none { it.isISOControl() || it == '#' || it == '\\' })
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE"))
        if (token != null) require(validBearer(token))
        require(rawBody == null || (body == null && !rawMime.isNullOrBlank() && rawBody.size <= MAX_STAGE_BYTES))
        require(responseLimit in 1..LIMIT)
        beforeOpen()
        val connection = URL(ORIGIN + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null || rawBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", rawMime ?: "application/json; charset=utf-8")
                val bytes = rawBody ?: body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299 || acceptedStatuses?.contains(status) == false) throw AdminBridgeFailure(status)
            if (requirePrivateJson) {
                check(connection.contentType?.substringBefore(';')?.trim()
                    ?.equals("application/json", ignoreCase = true) == true)
                val cache = connection.getHeaderField("Cache-Control").orEmpty().split(',')
                check(cache.any { it.trim().equals("no-store", ignoreCase = true) })
            }
            if (status == 204) return@withContext JSONObject()
            val bytes = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val part = ByteArray(8192)
                while (true) {
                    val n = stream.read(part)
                    if (n < 0) break
                    if (out.size() + n > responseLimit) throw IOException("Admin response too large")
                    out.write(part, 0, n)
                }
                out.toByteArray()
            }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
