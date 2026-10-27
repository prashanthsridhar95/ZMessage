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
    fun onlyReadSmsAndTheLogoInternetPermissionAreRequested() {
        val permissions = manifest.getElementsByTagName("uses-permission").names("name")
        assertEquals(listOf("android.permission.READ_SMS", "android.permission.INTERNET"), permissions)
        assertFalse("ACCESS_NETWORK_STATE" in File("src/main/AndroidManifest.xml").readText())
    }

    /**
     * INTERNET exists for one feature (the brand-logo fetch, on by default, can be turned off). The promise "your messages never leave the phone"
     * is kept by making sure the network is reachable from exactly one file, which sends only a brand's website name.
     */
    @Test
    fun networkCodeLivesInOneFile_andOnlyAsksPublicIconServices() {
        val network = Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp|HttpClient|Socket\\(")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && network.containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("LogoFetcher.kt"), offenders)

        val fetcher = File("src/main/java/com/zyke/message/data/logo/LogoFetcher.kt").readText()
        val hosts = Regex("https://([a-z.]+)/").findAll(fetcher).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("www.google.com", "icons.duckduckgo.com"), hosts)
        // The brand's own site is the third source, and the address is always built from the known-domain list.
        assertTrue("https://{domain}/apple-touch-icon.png" in fetcher)
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

        assertEquals("unexpected <receiver>", 0, manifest.getElementsByTagName("receiver").length)

        val services = manifest.getElementsByTagName("service")
        assertEquals(1, services.length)
        val service = services.item(0) as Element
        assertEquals(".data.sms.RcsNotificationListener", service.getAttributeNS(android, "name"))
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
