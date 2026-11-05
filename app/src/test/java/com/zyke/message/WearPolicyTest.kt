package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch support must not widen the privacy promise: the phone app's normal build knows nothing of Google Play
 * services, and the watch app asks for no permission at all and has no network code.
 */
class WearPolicyTest {
    @Test
    fun theNormalPhoneBuildHasNoWearableCode() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "com.google.android.gms.wearable" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        // The Data Layer link exists only in the optional source set, compiled with -PwithWear=true.
        assertTrue(File("src/wearOn/java/com/zyke/message/wear/DataLayerWatchLink.kt").exists())
    }

    @Test
    fun theSnapshotSentToTheWatchIsBuiltInTheDomain_andNeverFromMessageText() {
        val sync = File("src/main/java/com/zyke/message/wear").walkTopDown().filter { it.isFile }.joinToString { it.readText() }
        assertFalse("body" in sync)
        assertFalse("sender" in sync.lowercase())
    }

    @Test
    fun theWatchAppAsksForNoPermission_hasNoNetworkCode_andExportsOnlyWhatTheSystemMustBind() {
        val manifest = File("../wear/src/main/AndroidManifest.xml")
        if (!manifest.exists()) return
        val text = manifest.readText()
        assertFalse("<uses-permission" in text)
        val network = Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp|HttpClient|Socket\\(")
        val offenders = File("../wear/src").walkTopDown().filter { it.isFile && it.extension == "kt" && network.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        assertTrue("BIND_TILE_PROVIDER" in text && "BIND_COMPLICATION_PROVIDER" in text)
    }
}
