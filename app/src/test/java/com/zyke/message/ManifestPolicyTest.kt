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
     * INTERNET exists for two features: the brand-logo fetch (on by default, can be turned off) and the market strip's
     * prices (off until switched on). The promise "your messages never leave the phone" is kept by making sure the
     * network is reachable from exactly two files, one sending only a brand's website name and the other only a ticker
     * the person typed into their watch list.
     */
    @Test
    fun networkCodeLivesInOneFile_andOnlyAsksPublicIconServices() {
        val network = Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp|HttpClient|Socket\\(")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && network.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("LogoFetcher.kt", "QuoteFetcher.kt"), offenders.sorted())

        val fetcher = File("src/main/java/com/zyke/message/data/logo/LogoFetcher.kt").readText()
        val hosts = Regex("https://([a-z.]+)/").findAll(fetcher).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("www.google.com", "icons.duckduckgo.com"), hosts)
        // The brand's own site is the third source, and the address is always built from the known-domain list.
        assertTrue("https://{domain}/apple-touch-icon.png" in fetcher)

        val quotes = File("src/main/java/com/zyke/message/data/markets/QuoteFetcher.kt").readText()
        val quoteHosts = Regex("https://([a-z0-9.]+)/").findAll(quotes).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("query1.finance.yahoo.com"), quoteHosts)
    }

    @Test
    fun backupsAreOff_andCleartextIsOff() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
        assertEquals("false", application.getAttributeNS(android, "usesCleartextTraffic"))
    }

    @Test
    fun onlyTheLauncherActivityIsExported_andTheOnlyServiceIsTheSystemBoundRcsListener() {
        val activities = manifest.getElementsByTagName("activity")
        assertEquals(1, activities.length)
        assertEquals("true", (activities.item(0) as Element).getAttributeNS(android, "exported"))

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
        assertEquals(3, services.length)
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

        // And it can only ever expose the reports folder of the private cache.
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue("""<cache-path name="reports" path="reports/" />""" in paths)
        assertFalse("<external" in paths || "<files-path" in paths || "<root-path" in paths)
    }

    @Test
    fun backupRulesExcludeEverything() {
        val rules = File("src/main/res/xml/backup_rules.xml").readText() +
            File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("sharedpref" in rules)
        assertFalse("<include" in rules)
    }
}
