package com.ali.menbaradkshk.ui.adminbridge

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal sealed interface AdminGoogleResult {
    data class Issued(val token: String) : AdminGoogleResult
    data object Cancelled : AdminGoogleResult
    data object Unavailable : AdminGoogleResult
    data object NoAccount : AdminGoogleResult
    data object NotAuthorized : AdminGoogleResult
    data object Retry : AdminGoogleResult
}

/** An identity token goes straight to the existing membership-gated session exchange. */
internal suspend fun signInExistingAdminWithGoogle(
    activity: Activity?,
    webClientId: String,
): AdminGoogleResult {
    if (activity == null || webClientId.isBlank()) return AdminGoogleResult.Unavailable
    return try {
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) return AdminGoogleResult.Unavailable
        val identityToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        // No Firebase fallback, automatic enrollment, access request, or client-side role grant.
        AdminGoogleResult.Issued(AdminBridgeApi.exchangeIdentity(identityToken))
    } catch (e: CancellationException) {
        throw e
    } catch (_: GetCredentialCancellationException) {
        AdminGoogleResult.Cancelled
    } catch (_: NoCredentialException) {
        AdminGoogleResult.NoAccount
    } catch (_: GetCredentialException) {
        AdminGoogleResult.Unavailable
    } catch (e: AdminBridgeFailure) {
        when (e.statusCode) {
            401, 403 -> AdminGoogleResult.NotAuthorized
            else -> AdminGoogleResult.Retry
        }
    } catch (_: IOException) {
        AdminGoogleResult.Retry
    } catch (_: Exception) {
        // Do not expose SDK exception strings or provider response bodies to the user or logs.
        AdminGoogleResult.Unavailable
    }
}
