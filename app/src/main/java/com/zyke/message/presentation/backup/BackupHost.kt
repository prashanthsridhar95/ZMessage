package com.zyke.message.presentation.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.backup.BackupHub

/**
 * What the backup screens need from the activity: showing Google's own sign-in / agree screen and getting the
 * short-lived access token back ([signIn] calls back with null when it was cancelled or failed).
 */
class BackupHost(val signIn: (onToken: (String?) -> Unit) -> Unit)

val LocalBackupHost = staticCompositionLocalOf<BackupHost?> { null }

@Composable
internal fun rememberBackupHub(): BackupHub {
    val context = LocalContext.current
    return androidx.compose.runtime.remember { (context.applicationContext as ZykeMessagesApplication).container.backup }
}
