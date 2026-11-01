package com.zyke.message.data.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [DriveAuth] with Google's Authorization API (Google Play services). It asks for one scope,
 * drive.appdata: access to this app's own hidden folder in the person's Drive and to nothing else. No client secret
 * or config file is needed; Google recognises the app by its package name and signing key (see docs/BACKUP_SETUP.md).
 * Needs Google Play services; on a phone without it every call reports a failure and Drive backup stays unavailable.
 */
class GmsDriveAuth(context: Context) : DriveAuth {
    private val app = context.applicationContext
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
        .build()

    override suspend fun authorize(): DriveAuthResult = suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(app).authorize(request)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                val token = result.accessToken
                val answer = when {
                    result.hasResolution() && pending != null -> DriveAuthResult.NeedsUser(pending)
                    token != null -> DriveAuthResult.Token(token)
                    else -> DriveAuthResult.Failed(BackupFailure.AUTH_EXPIRED)
                }
                if (continuation.isActive) continuation.resume(answer)
            }
            .addOnFailureListener { error ->
                val offline = (error as? ApiException)?.statusCode == NETWORK_ERROR
                if (continuation.isActive) continuation.resume(DriveAuthResult.Failed(if (offline) BackupFailure.NO_NETWORK else BackupFailure.AUTH_EXPIRED))
            }
    }

    override fun tokenFromResult(data: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()

    private companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val NETWORK_ERROR = 7
    }
}
