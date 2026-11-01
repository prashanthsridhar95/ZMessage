package com.zyke.message.data.backup

import android.app.PendingIntent
import android.content.Intent

/** The outcome of asking Google for permission to use the Drive app folder. */
sealed interface DriveAuthResult {
    /** Allowed; [value] is a short-lived access token. Never stored, never logged. */
    class Token(val value: String) : DriveAuthResult

    /** Google needs the person to sign in or agree on screen first. */
    class NeedsUser(val request: PendingIntent) : DriveAuthResult

    class Failed(val failure: BackupFailure) : DriveAuthResult
}

/** The only place the rest of the app meets Google sign-in, so everything else stays testable. */
interface DriveAuth {
    /** Asks without showing anything; succeeds only if the person already agreed and it has not expired. */
    suspend fun authorize(): DriveAuthResult

    /** Reads the token from the result of the on-screen sign-in. */
    fun tokenFromResult(data: Intent?): String?
}
