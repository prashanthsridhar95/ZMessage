package com.zyke.message

import com.zyke.message.domain.model.AppIconChoice
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
                "android.permission.SEND_SMS", // passing a login code on to chosen people, off until switched on; asked for only then
                "android.permission.FOREGROUND_SERVICE", // reading a very large inbox month by month, and the in-flight notification, each with a visible notification
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC", // the same two services (type dataSync); no exact-alarm permission is asked for
                "android.permission.POST_PROMOTED_NOTIFICATIONS" // Android 16 Live Updates for the in-flight notification; a normal permission, ignored before 16
            ),
            permissions
        )
        assertFalse("ACCESS_NETWORK_STATE" in File("src/main/AndroidManifest.xml").readText())
    }

    /**
     * INTERNET exists for five features: the brand-logo fetch (on by default, can be turned off), the market strip's
     * prices (off until switched on), live flight status (off until switched on; the airline code and flight number only), live train status (off until switched on; the train number and the run's start day only) and the optional backup to the person's own Google Drive (off until switched on).
     * The promise "your messages never leave the phone" is kept by making sure the network is reachable from exactly
     * five files: one sending only a brand's website name, one only an airline code and flight number (live flight status), one only a train number and start day (live train status), one only a ticker from their watch list (or the few letters typed into the add-stock box, to suggest names),
     * and one only the single PIN-encrypted backup file, to the private app folder of the person's own Drive.
     */
    @Test
    fun networkCodeLivesInOneFile_andOnlyAsksPublicIconServices() {
        val network = Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp|HttpClient|Socket\\(")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && network.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("DriveBackupClient.kt", "FlightFetcher.kt", "LogoFetcher.kt", "QuoteFetcher.kt", "TrainFetcher.kt", "TrainProbe.kt"), offenders.sorted())

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

        // Live flight status (off until switched on) asks one host, for one flight number: the airline code and number, nothing else.
        val flights = File("src/main/java/com/zyke/message/data/markets/FlightFetcher.kt").readText()
        val flightHosts = Regex("https://([a-z0-9.]+)/").findAll(flights).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("api.flightradar24.com"), flightHosts)
        assertTrue("https://api.flightradar24.com/common/v1/flight/list.json?query=" in flights)
        // No redirects to another host, no cookies, no identifiers of the person or the phone.
        assertTrue("instanceFollowRedirects = false" in flights)
        assertFalse(Regex("CookieHandler|Authorization|ANDROID_ID|Settings\\.Secure|advertising", RegexOption.IGNORE_CASE).containsMatchIn(flights))
        // Only the flight fetcher and the service that calls it know about flights; the fetcher is built in one place.
        assertEquals(1, File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.count { "FlightFetcher()" in it.readText() })
        assertTrue("FlightFetcher()" in File("src/main/java/com/zyke/message/data/flights/FlightService.kt").readText())
        // The in-flight tracking service reuses that one fetcher through FlightService.refresh; it has no network code or fetcher of its own.
        val tracking = File("src/main/java/com/zyke/message/notifications/FlightTrackingService.kt").readText()
        assertTrue("flights.refresh(" in tracking)
        assertFalse("FlightFetcher" in tracking || "FlightFetcher" in File("src/main/java/com/zyke/message/notifications/FlightTrackingControl.kt").readText())

        // Live train status (off until switched on) asks one host, for one train: the number and the run's start day, nothing else.
        val trainsFetcher = File("src/main/java/com/zyke/message/data/markets/TrainFetcher.kt").readText()
        val trainHosts = Regex("https://([a-z0-9.]+)/").findAll(trainsFetcher).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("api.confirmtkt.com"), trainHosts)
        assertTrue("https://api.confirmtkt.com/api/trains/runningstatus?trainno=" in trainsFetcher)
        assertTrue("instanceFollowRedirects = false" in trainsFetcher)
        assertFalse(Regex("CookieHandler|Authorization|ANDROID_ID|Settings\\.Secure|advertising", RegexOption.IGNORE_CASE).containsMatchIn(trainsFetcher))
        assertEquals(1, File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.count { "TrainFetcher()" in it.readText() })
        assertTrue("TrainFetcher()" in File("src/main/java/com/zyke/message/data/trains/TrainService.kt").readText())
        val trainTracking = File("src/main/java/com/zyke/message/notifications/TrainTrackingService.kt").readText()
        assertTrue("trains.refresh(" in trainTracking)
        assertFalse("TrainFetcher" in trainTracking || "TrainFetcher" in File("src/main/java/com/zyke/message/notifications/TrainTrackingControl.kt").readText())

        // The developer's train source probe (temporary, to find a working source) is the one other train network class: its own pinned list of
        // candidate hosts, the same rules, and nothing in the live feature, the services or the tracking code can reach it.
        val probe = File("src/main/java/com/zyke/message/data/markets/TrainProbe.kt").readText()
        val probeHosts = Regex("https://([a-z0-9.]+)/").findAll(probe).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("api.confirmtkt.com", "www.confirmtkt.com", "www.railyatri.in", "www.ixigo.com", "enquiry.indianrail.gov.in", "www.trainman.in", "runningstatus.in"),
            probeHosts
        )
        assertEquals(probeHosts - "api.confirmtkt.com", com.zyke.message.domain.legal.LegalText.probeHosts.toSet())
        assertTrue("instanceFollowRedirects = false" in probe)
        assertFalse("readNBytes(" in probe.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n"))
        assertFalse(Regex("CookieHandler|Authorization|ANDROID_ID|Settings\\.Secure|advertising", RegexOption.IGNORE_CASE).containsMatchIn(probe))
        val users = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && "TrainProbe" in it.readText() }.map { it.name }.toList().sorted()
        assertEquals(listOf("LiveStatusChecks.kt", "TrainProbe.kt", "TrainProbeMemo.kt"), users)
        assertFalse("TrainProbe" in File("src/main/java/com/zyke/message/data/trains/TrainService.kt").readText())
        assertFalse("TrainProbe" in trainsFetcher)

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
        assertEquals(4, activities.length)
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
        // The "Demo mode" shortcut's target is built the same way, and so is the helper that restarts the app in a fresh process
        // (its own ":restart" process, so the one being replaced can end at once): neither is exported or has a filter.
        for (name in listOf(".presentation.DemoActivity", ".presentation.RestartActivity")) {
            val helper = (0 until activities.length).map { activities.item(it) as Element }.single { it.getAttributeNS(android, "name") == name }
            assertEquals(name, "false", helper.getAttributeNS(android, "exported"))
            assertEquals(name, "true", helper.getAttributeNS(android, "excludeFromRecents"))
            assertEquals(name, 0, helper.getElementsByTagName("intent-filter").length)
        }
        assertEquals(":restart", (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".presentation.RestartActivity" }.getAttributeNS(android, "process"))
        // The launcher entry is an activity-alias per icon colour (see AppIconChoice); MainActivity itself has none.
        assertEquals(0, main.getElementsByTagName("intent-filter").length)
        val aliases = manifest.getElementsByTagName("activity-alias").let { list -> (0 until list.length).map { list.item(it) as Element } }
        assertEquals(AppIconChoice.entries.size, aliases.size)
        aliases.forEach { alias ->
            val name = alias.getAttributeNS(android, "name")
            assertEquals(name, ".presentation.MainActivity", alias.getAttributeNS(android, "targetActivity"))
            assertEquals(name, "true", alias.getAttributeNS(android, "exported"))
            // Only the default icon is enabled in the manifest; the others are switched on from Settings.
            assertEquals(name, if (name == ".IconDefault") "true" else "false", alias.getAttributeNS(android, "enabled"))
            val filter = alias.getElementsByTagName("intent-filter").item(0) as Element
            assertEquals(name, listOf("android.intent.action.MAIN"), filter.getElementsByTagName("action").names("name"))
            assertEquals(name, listOf("android.intent.category.LAUNCHER"), filter.getElementsByTagName("category").names("name"))
            assertTrue(name, alias.getAttributeNS(android, "icon").startsWith("@mipmap/"))
            // The long-press "Share logs" shortcut must work whichever alias is enabled.
            val meta = alias.getElementsByTagName("meta-data").let { list -> (0 until list.length).map { list.item(it) as Element } }
                .single { it.getAttributeNS(android, "name") == "android.app.shortcuts" }
            assertEquals(name, "@xml/shortcuts", meta.getAttributeNS(android, "resource"))
        }
        val shortcuts = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/shortcuts.xml")).documentElement
        val shortcutList = shortcuts.getElementsByTagName("shortcut").let { list -> (0 until list.length).map { list.item(it) as Element } }
        assertEquals(listOf("share_logs", "demo_mode"), shortcutList.map { it.getAttributeNS(android, "shortcutId") })
        val targets = mapOf(
            "share_logs" to ("com.zyke.message.presentation.ShareLogsActivity" to "ic_share_24"),
            "demo_mode" to ("com.zyke.message.presentation.DemoActivity" to "ic_shortcut_demo_24")
        )
        for (shortcut in shortcutList) {
            val id = shortcut.getAttributeNS(android, "shortcutId")
            val target = shortcut.getElementsByTagName("intent").item(0) as Element
            assertEquals("com.zyke.message", target.getAttributeNS(android, "targetPackage"))
            assertEquals(targets.getValue(id).first, target.getAttributeNS(android, "targetClass"))
            assertTrue(File("src/main/res/drawable/${targets.getValue(id).second}.xml").exists())
            assertTrue(id, shortcut.getAttributeNS(android, "shortcutShortLabel").startsWith("@string/"))
        }

        // That activity must keep working when the rest of the app cannot start: no container, stores, view models or Compose.
        val source = File("src/main/java/com/zyke/message/presentation/ShareLogsActivity.kt").readText()
        assertFalse("AppContainer" in source || "ViewModel" in source || "SharedPreferences" in source || "androidx.compose" in source)
        assertTrue(source.contains(": Activity()"))
        val demo = File("src/main/java/com/zyke/message/presentation/DemoActivity.kt").readText()
        assertFalse("AppContainer" in demo || "ViewModel" in demo || "androidx.compose" in demo || "container" in demo.substringAfter("class DemoActivity"))
        assertTrue(demo.contains(": Activity()"))

        // Receivers exist for the widget, the morning alarm, the restart, the copy button, live alerts and the sent report of a shared code.
        val receivers = manifest.getElementsByTagName("receiver")
        assertEquals(
            setOf(
                ".notifications.DaysAheadWidget", ".notifications.DigestReceiver", ".notifications.BootReceiver",
                ".notifications.CopyCodeReceiver", ".notifications.SmsReceiver", ".notifications.ReminderReceiver",
                ".notifications.WeeklyReceiver", ".notifications.QuickAddWidget", ".notifications.MarketCloseReceiver",
                ".notifications.CodeShareStatusReceiver", ".notifications.FlightAlarmReceiver", ".notifications.TrainAlarmReceiver"
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
        assertEquals(6, services.length)
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
        // The flight tracking service: not exported, a data-sync foreground service (declared type + FOREGROUND_SERVICE_DATA_SYNC above),
        // started by the app or by the app's own alarm; it asks the network only through FlightService's one fetcher.
        val flightService = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".notifications.FlightTrackingService" }
        assertEquals("false", flightService.getAttributeNS(android, "exported"))
        assertEquals("dataSync", flightService.getAttributeNS(android, "foregroundServiceType"))
        assertEquals(0, flightService.getElementsByTagName("intent-filter").length)
        assertEquals("", flightService.getAttributeNS(android, "permission"))
        val trainService = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".notifications.TrainTrackingService" }
        assertEquals("false", trainService.getAttributeNS(android, "exported"))
        assertEquals("dataSync", trainService.getAttributeNS(android, "foregroundServiceType"))
        assertEquals(0, trainService.getElementsByTagName("intent-filter").length)
        assertEquals("", trainService.getAttributeNS(android, "permission"))
        val trainReceiver = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".notifications.TrainAlarmReceiver" }
        assertEquals("false", trainReceiver.getAttributeNS(android, "exported"))
        assertEquals(0, trainReceiver.getElementsByTagName("intent-filter").length)
        val flightReceiver = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".notifications.FlightAlarmReceiver" }
        assertEquals("false", flightReceiver.getAttributeNS(android, "exported"))
        assertEquals(0, flightReceiver.getElementsByTagName("intent-filter").length)
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

    /** Every icon colour in the enum has its alias in the manifest and a launcher icon, and the manifest has no others. */
    @Test
    fun launcherAliasesMatchTheAppIconChoices() {
        val aliases = manifest.getElementsByTagName("activity-alias").names("name").map { "com.zyke.message$it" }
        assertEquals(AppIconChoice.entries.map { it.aliasName }.sorted(), aliases.sorted())
        assertEquals(1, AppIconChoice.entries.count { it.aliasName.endsWith(".IconDefault") })
        AppIconChoice.entries.forEach { choice ->
            val id = if (choice == AppIconChoice.DEFAULT) "ic_launcher" else "ic_launcher_${choice.id}"
            listOf(id, "${id}_round").forEach { icon ->
                val file = File("src/main/res/mipmap-anydpi-v26/$icon.xml")
                assertTrue("$icon missing", file.exists())
                assertTrue("$icon has no monochrome layer", "<monochrome" in file.readText())
            }
        }
    }
}
