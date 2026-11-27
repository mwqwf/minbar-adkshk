package com.ali.menbaradkshk.ui.adminbridge.upload

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.UUID

/** UI-thread-only picker. Does not read documents or keep persistent grants. */
internal class AdminOriginalPicker(private val registry: ActivityResultRegistry?) {
    enum class Launch { STARTED, BUSY, FAILED }
    private val claims = AdminOriginalPickerClaims<Uri>()
    private class Pending(val ticket: AdminOriginalPickerClaims.Ticket) {
        var launcher: ActivityResultLauncher<Intent>? = null
    }
    private var pending: Pending? = null
    private var disposed = false

    fun revokeRoute() = claims.revokeRoute()

    fun dispose() {
        disposed = true
        revokeRoute()
        pending?.let { finish(it) }
    }

    fun launch(
        account: String,
        currentRoute: () -> String?,
        onStart: () -> Unit,
        onResult: (String, Uri) -> Unit,
    ): Launch {
        if (disposed || registry == null) return Launch.FAILED
        val ticket = claims.begin(account, currentRoute()) ?: return Launch.BUSY
        val request = Pending(ticket).also { pending = it }
        return try {
            // A key belongs to one launch, not the account or Activity instance. A late duplicate
            // routed through an older key cannot drain the next pending selection.
            val key = "minbar-admin-original:" + UUID.randomUUID()
            val target = registry.register(key, ActivityResultContracts.StartActivityForResult()) { result ->
                if (pending !== request || disposed) return@register
                val accepted = receive(currentRoute(), result.resultCode, result.data)
                finish(request, insideDispatch = true)
                accepted?.let { onResult(it.account, it.document) }
            }
            request.launcher = target
            onStart()
            target.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").putExtra(Intent.EXTRA_LOCAL_ONLY, true))
            Launch.STARTED
        } catch (_: Exception) {
            finish(request)
            Launch.FAILED
        }
    }

    private fun finish(request: Pending, insideDispatch: Boolean = false) {
        if (pending === request) pending = null
        claims.launchFailed(request.ticket)
        val target = request.launcher ?: return
        request.launcher = null
        // ActivityResultRegistry removes its launched-key marker after the callback returns.
        // Unregistering inside its callback can leave the request-code mapping alive.
        if (insideDispatch) Handler(Looper.getMainLooper()).post { target.unregister() }
        else target.unregister()
    }

    private fun receive(
        currentRoute: String?, resultCode: Int, returned: Intent?,
    ): AdminOriginalPickerClaims.Result<Uri>? {
        val uri = returned?.data
        val clips = returned?.clipData
        val unexpectedClips = clips != null && (clips.itemCount != 1 || clips.getItemAt(0).uri != uri ||
            clips.getItemAt(0).intent != null)
        return claims.accept(currentRoute, resultCode == Activity.RESULT_OK,
            uri?.scheme == "content", (returned?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            unexpectedClips, uri)
    }
}

@Composable
internal fun rememberAdminOriginalPicker(): AdminOriginalPicker {
    val registry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
    val picker = remember(registry) { AdminOriginalPicker(registry) }
    DisposableEffect(picker) {
        onDispose { picker.dispose() }
    }
    return picker
}
