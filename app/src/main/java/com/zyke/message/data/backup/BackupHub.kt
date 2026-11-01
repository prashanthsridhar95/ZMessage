package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.data.images.ImageStore

/** The backup parts wired together; one instance lives in the app container. */
class BackupHub(context: Context, images: ImageStore, val drive: DriveSync? = null, val driveAuth: DriveAuth? = null) {
    val keys = BackupKeyStore(context)
    val prefs = BackupPrefs(context) { keys.has() }
    val engine = BackupEngine(context, images, keys)
    val runner = BackupRunner(context, prefs, engine, drive)
    val scheduler = BackupScheduler(context, prefs)
    val restorer = BackupRestorer(context, images, keys, prefs)
}
