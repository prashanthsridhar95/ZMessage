package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.data.images.ImageStore

/** The backup parts wired together; one instance lives in the app container. */
class BackupHub(context: Context, images: ImageStore, val driveAuth: DriveAuth? = null) {
    val keys = BackupKeyStore(context)
    val prefs = BackupPrefs(context) { keys.has() }
    val drive: DriveSync? = driveAuth?.let { GoogleDriveSync(it, DriveBackupClient(), { prefs.driveConsented }, { prefs.driveConsented = true }) }
    val engine = BackupEngine(context, images, keys)
    val folder = FolderBackupStore(context, prefs)
    val runner = BackupRunner(context, prefs, engine, drive, folder)
    val scheduler = BackupScheduler(context, prefs)
    val restorer = BackupRestorer(context, images, keys, prefs)
}
