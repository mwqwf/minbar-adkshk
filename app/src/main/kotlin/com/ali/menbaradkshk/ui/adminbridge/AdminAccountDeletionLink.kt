package com.ali.menbaradkshk.ui.adminbridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val ADMIN_ACCOUNT_DELETION_URL = "https://minbar-adkassahk.vercel.app/admin-delete#admin-account"

/** The public request page works without an app session; do not append account or session details. */
@Composable
internal fun AdminAccountDeletionLink() {
    val handler = LocalUriHandler.current
    var openFailed by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = {
                openFailed = false
                try {
                    handler.openUri(ADMIN_ACCOUNT_DELETION_URL)
                } catch (_: IllegalArgumentException) {
                    openFailed = true
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("طلب حذف حساب المشرف وبياناته")
        }
        if (openFailed) {
            Text(
                "تعذّر فتح صفحة الطلب. افتح هذا العنوان في المتصفّح: $ADMIN_ACCOUNT_DELETION_URL",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
