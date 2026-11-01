package com.zyke.message

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * The privacy promise ("nothing leaves your phone") is enforced here, so a change that breaks it
 * fails the build instead of shipping.
 */
class ManifestPolicyTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement

    private fun NodeList.names(attribute: String) =
        (0 until length).map { (item(it) as Element).getAttributeNS(android, attribute) }

    @Test
    fun onlyTheNamedPermissionsAreRequested() {
        val permissions = manifest.getElementsByTagName("uses-permission").names("name")
        assertEquals(
            listOf(
                "android.permission.READ_SMS",
                "android.permission.INTERNET", // brand logos, and prices for the market strip (off until switched on)
                "android.permission.POST_NOTIFICATIONS", // morning digest
                "android.permission.RECEIVE_BOOT_COMPLETED", // putting the digest alarm back after a restart
                "android.permission.RECEIVE_SMS", // live code and bill buttons, off until switched on
                "android.permission.FOREGROUND_SERVICE", // reading a very large inbox month by month, with a visible notification
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
            ),
            permissions
        )
        assertFalse("ACCESS_NETWORK_STATE" in File("src/main/AndroidManifest.xml").readText())
    }

    /**
     * INTERNET exists for three features: the brand-logo fetch (on by default, can be turned off), the market strip's
     * prices (off until switched on) and the optional backup to the person's own Google Drive (off until switched on).
     * The promise "your messages never leave the phone" is kept by making sure the network is reachable from exactly
     * three files: one sending only a brand's website name, one only a ticker from their watch list (or the few letters typed into the add-stock box, to suggest names),
     * and one only the single PIN-encrypted backup file, to the private app folder of the person's own Drive.
     */
    @Test
    fun networkCodeLivesInOneFile_andOnlyAsksPublicIconServices() {
        val network = Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp|HttpClient|Socket\\(")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && network.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("DriveBackupClient.kt", "LogoFetcher.kt", "QuoteFetcher.kt"), offenders.sorted())

        val fetcher = File("src/main/java/com/zyke/message/data/logo/LogoFetcher.kt").readText()
        val hosts = Regex("https://([a-z.]+)/").findAll(fetcher).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("www.google.com", "icons.duckduckgo.com"), hosts)
        // The brand's own site is the third source, and the address is always built from the known-domain list.
        assertTrue("https://{domain}/apple-touch-icon.png" in fetcher)

        val quotes = File("src/main/java/com/zyke/message/data/markets/QuoteFetcher.kt").readText()
        val quoteHosts = Regex("https://([a-z0-9.]+)/").findAll(quotes).map { it.groupValues[1] }.toSet()
        // Prices come from the chart endpoint on query1 and name suggestions from the search endpoint on query2; no other host.
        assertEquals(setOf("query1.finance.yahoo.com", "query2.finance.yahoo.com"), quoteHosts)
        assertTrue("https://query1.finance.yahoo.com/v8/finance/chart/" in quotes)
        assertTrue("https://query2.finance.yahoo.com/v1/finance/search?q=" in quotes)

        // The Drive backup talks to Google's Drive API only, and only to the hidden app folder (scope drive.appdata).
        val drive = File("src/main/java/com/zyke/message/data/backup/DriveBackupClient.kt").readText()
        val driveHosts = Regex("https://([a-z0-9.]+)/").findAll(drive).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("www.googleapis.com"), driveHosts)
        assertTrue("appDataFolder" in drive)
        val auth = File("src/main/java/com/zyke/message/data/backup/GmsDriveAuth.kt").readText()
        val scopes = Regex("https://www\\.googleapis\\.com/auth/[a-z.]+").findAll(auth).map { it.value }.toList()
        assertEquals(listOf("https://www.googleapis.com/auth/drive.appdata"), scopes)
    }

    /**
     * Android's Auto Backup is switched on for exactly one purpose: carrying the one encrypted snapshot file the app
     * writes itself (see [backupRulesTakeOnlyTheEncryptedSnapshotFile]). Cleartext traffic stays off.
     */
    @Test
    fun autoBackupIsOn_andCleartextIsOff() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("true", application.getAttributeNS(android, "allowBackup"))
        assertEquals("false", application.getAttributeNS(android, "usesCleartextTraffic"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
    }

    @Test
    fun onlyTheLauncherActivityIsExported_andTheOnlyServiceIsTheSystemBoundRcsListener() {
        val activities = manifest.getElementsByTagName("activity")
        assertEquals(2, activities.length)
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".presentation.MainActivity" }
        assertEquals("true", main.getAttributeNS(android, "exported"))

        // The launcher icon's "Share logs" shortcut: a static shortcut whose target is a screen-less activity that is
        // not exported and has no intent filter, so no other app can start it. It must stay out of the recents list.
        val shareLogs = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".presentation.ShareLogsActivity" }
        assertEquals("false", shareLogs.getAttributeNS(android, "exported"))
        assertEquals("true", shareLogs.getAttributeNS(android, "excludeFromRecents"))
        assertEquals(0, shareLogs.getElementsByTagName("intent-filter").length)
        val shortcutsMeta = main.getElementsByTagName("meta-data").let { list -> (0 until list.length).map { list.item(it) as Element } }
            .single { it.getAttributeNS(android, "name") == "android.app.shortcuts" }
        assertEquals("@xml/shortcuts", shortcutsMeta.getAttributeNS(android, "resource"))
        val shortcuts = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/shortcuts.xml")).documentElement
        val shortcut = shortcuts.getElementsByTagName("shortcut").let { list -> (0 until list.length).map { list.item(it) as Element } }.single()
        assertEquals("share_logs", shortcut.getAttributeNS(android, "shortcutId"))
        val target = shortcut.getElementsByTagName("intent").item(0) as Element
        assertEquals("com.zyke.message", target.getAttributeNS(android, "targetPackage"))
        assertEquals("com.zyke.message.presentation.ShareLogsActivity", target.getAttributeNS(android, "targetClass"))
        assertTrue(File("src/main/res/drawable/ic_share_24.xml").exists())

        // That activity must keep working when the rest of the app cannot start: no container, stores, view models or Compose.
        val source = File("src/main/java/com/zyke/message/presentation/ShareLogsActivity.kt").readText()
        assertFalse("AppContainer" in source || "ViewModel" in source || "SharedPreferences" in source || "androidx.compose" in source)
        assertTrue(source.contains(": Activity()"))

        // Receivers exist for the widget, the morning alarm, the restart, the copy button and live alerts.
        val receivers = manifest.getElementsByTagName("receiver")
        assertEquals(
            setOf(
                ".notifications.DaysAheadWidget", ".notifications.DigestReceiver", ".notifications.BootReceiver",
                ".notifications.CopyCodeReceiver", ".notifications.SmsReceiver", ".notifications.ReminderReceiver",
                ".notifications.WeeklyReceiver", ".notifications.QuickAddWidget"
            ),
            receivers.names("name").toSet()
        )
        // Only the SMS receiver is exported (the phone's telephony service has to reach it); it is guarded by
        // BROADCAST_SMS so no other app can send to it, and it is disabled until the person opts in.
        val exported = (0 until receivers.length).map { receivers.item(it) as Element }
            .filter { it.getAttributeNS(android, "exported") == "true" }
        assertEquals(listOf(".notifications.SmsReceiver"), exported.map { it.getAttributeNS(android, "name") })
        assertEquals("android.permission.BROADCAST_SMS", exported.single().getAttributeNS(android, "permission"))
        assertEquals("false", exported.single().getAttributeNS(android, "enabled"))

        val services = manifest.getElementsByTagName("service")
        assertEquals(4, services.length)
        // The scheduled backup job: only the system's job scheduler can start it.
        val backupJob = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".data.backup.BackupJobService" }
        assertEquals("false", backupJob.getAttributeNS(android, "exported"))
        assertEquals("android.permission.BIND_JOB_SERVICE", backupJob.getAttributeNS(android, "permission"))
        // The quick-settings tile is the one exported service: the system's quick-settings host must bind it, and
        // BIND_QUICK_SETTINGS_TILE keeps every other app from doing so. It only opens the entry sheet.
        val tile = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".notifications.QuickAddTileService" }
        assertEquals("true", tile.getAttributeNS(android, "exported"))
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttributeNS(android, "permission"))
        // Neither of the other two is exported: the second only runs while a large inbox is read, and stops by itself.
        val backfill = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".notifications.BackfillService" }
        assertEquals("false", backfill.getAttributeNS(android, "exported"))
        assertEquals("dataSync", backfill.getAttributeNS(android, "foregroundServiceType"))
        val service = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".data.sms.RcsNotificationListener" }
        assertEquals("false", service.getAttributeNS(android, "exported"))
        // Only the system can bind it, and only once the user has granted notification access.
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", service.getAttributeNS(android, "permission"))
    }

    @Test
    fun theOnlyProviderIsAPrivateFileProvider() {
        val providers = manifest.getElementsByTagName("provider")
        assertEquals(1, providers.length)
        val provider = providers.item(0) as Element
        assertEquals("androidx.core.content.FileProvider", provider.getAttributeNS(android, "name"))
        assertEquals("false", provider.getAttributeNS(android, "exported"))
        assertEquals("true", provider.getAttributeNS(android, "grantUriPermissions"))

        // And it can only ever expose the reports and shared-logs folders of the private cache.
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue("""<cache-path name="reports" path="reports/" />""" in paths)
        assertTrue("""<cache-path name="shared_logs" path="shared_logs/" />""" in paths)
        assertEquals(2, Regex("<cache-path ").findAll(paths).count())
        assertFalse("<external" in paths || "<files-path" in paths || "<root-path" in paths)
    }

    /**
     * Auto Backup may take ONE file only: files/auto_backup/zyke-backup.bin, which is already encrypted with the
     * person's PIN. Preferences, databases, every other file (sender pictures, logs, the key store file, the
     * message-reading cache), external storage and the app root stay out, in the old and the new rules alike.
     */
    @Test
    fun backupRulesTakeOnlyTheEncryptedSnapshotFile() {
        val engine = File("src/main/java/com/zyke/message/data/backup/BackupEngine.kt").readText()
        assertTrue("""const val DIR = "auto_backup"""" in engine && """const val NAME = "zyke-backup.bin"""" in engine)
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/xml/$name")).documentElement
            val sections = if (name == "backup_rules.xml") listOf(root)
            else listOf("cloud-backup", "device-transfer").map { root.getElementsByTagName(it).item(0) as Element }
            assertEquals(name, if (name == "backup_rules.xml") 1 else 2, sections.size)
            for (section in sections) {
                val includes = section.getElementsByTagName("include")
                assertEquals(name, 1, includes.length)
                val include = includes.item(0) as Element
                assertEquals("file", include.getAttribute("domain"))
                assertEquals("auto_backup/zyke-backup.bin", include.getAttribute("path"))
                val excluded = section.getElementsByTagName("exclude").let { list ->
                    (0 until list.length).map { (list.item(it) as Element).getAttribute("domain") }
                }
                assertTrue(name, excluded.containsAll(listOf("sharedpref", "database", "external", "root")))
            }
        }
    }
}
