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
    fun onlyReadSmsIsRequested() {
        val permissions = manifest.getElementsByTagName("uses-permission").names("name")
        assertEquals(listOf("android.permission.READ_SMS"), permissions)
    }

    @Test
    fun hasNoInternetPermission() {
        val raw = File("src/main/AndroidManifest.xml").readText()
        assertFalse("INTERNET" in raw)
        assertFalse("ACCESS_NETWORK_STATE" in raw)
    }

    @Test
    fun backupsAreOff_andCleartextIsOff() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
        assertEquals("false", application.getAttributeNS(android, "usesCleartextTraffic"))
    }

    @Test
    fun onlyTheLauncherActivityIsExported_andNothingElseIsDeclared() {
        val activities = manifest.getElementsByTagName("activity")
        assertEquals(1, activities.length)
        assertEquals("true", (activities.item(0) as Element).getAttributeNS(android, "exported"))
        for (tag in listOf("service", "receiver", "provider")) {
            assertEquals("unexpected <$tag>", 0, manifest.getElementsByTagName(tag).length)
        }
    }

    @Test
    fun backupRulesExcludeEverything() {
        val rules = File("src/main/res/xml/backup_rules.xml").readText() +
            File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("sharedpref" in rules)
        assertFalse("<include" in rules)
    }
}
