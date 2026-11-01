package com.zyke.message.data.backup

/** Why a backup did not happen, as a class (never the details), with the sentence the person reads. */
enum class BackupFailure(val message: String, val retrySoon: Boolean) {
    NOT_SIGNED_IN("Not signed in to Google. Sign in to back up to Google Drive.", false),
    AUTH_EXPIRED("Google sign-in expired. Sign in again.", false),
    NO_NETWORK("No network connection. It will try again.", true),
    NO_KEY("Backups need your PIN set up again. Choose a PIN to continue.", false),
    DRIVE_FULL("Your Google Drive is full.", false),
    DRIVE_ERROR("Google Drive did not accept the backup. It will try again later.", true),
    STORAGE("The backup could not be saved on this phone.", true),
    FOLDER_LOST("The backup folder is not available any more (it was removed, or the permission was taken back). Pick the folder again.", false),
    FOLDER_FULL("There is no space left to save the backup in the folder.", false),
    UNKNOWN("The backup did not work. It will try again later.", true)
}

class BackupFailureException(val failure: BackupFailure) : Exception(failure.name)

enum class BackupMechanism(val label: String) {
    LOCAL("This phone"),
    DRIVE("Google Drive"),
    FOLDER("Folder")
}

enum class BackupTrigger { SCHEDULE, MANUAL, FOREGROUND, SETUP }
