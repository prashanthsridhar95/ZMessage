package com.zyke.message

import com.zyke.message.domain.legal.LegalText
import com.zyke.message.domain.logo.LogoList
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logo list: a brand's logo is loaded from a web address that ships with the app, and the person's own web pictures are
 * recorded as shop name and address only. These tests keep the file clean, the network rules strict, the person's choice first,
 * and the privacy page honest about it.
 */
class LogoListPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private fun repoRoot(): File = appRoot().canonicalFile.let { if (it.name == "app") it.parentFile else it }
    private fun main(path: String) = File(appRoot(), "src/main/java/com/zyke/message/$path").readText()

    @Test
    fun theShippedListIsInTheDocumentedFormat_withEveryEntryClean() {
        val parsed = LogoList.parse(File(appRoot(), "src/main/assets/known_logos.json").readText())
        assertNotNull("known_logos.json must be {\"version\":1,\"logos\":[...]}", parsed)
        assertEquals("every entry must be a clean key and an https address", 0, parsed!!.rejected)
        val file = File(appRoot(), "src/main/assets/known_logos.json").readText()
        assertEquals("the file is kept in the form the export writes (sorted, one entry a line)", LogoList.toJson(parsed.entries), file)
        // Nothing is pre-seeded that nobody has looked at: every address is https on a public host, so the hosts are all there is to ask.
        parsed.entries.forEach { assertEquals(LogoList.cleanUrl(it.url), it.url) }
    }

    @Test
    fun theHostsOfTheBundledListAreCoveredByTheStatementOnThePageAndInTheDocs() {
        val privacy = File(repoRoot(), "docs/PRIVACY.md").readText()
        val page = LegalText.fullText()
        assertTrue("known_logos.json" in privacy && "bundled logo list" in privacy)
        assertTrue("the page must say logos are asked from the app's own list's web addresses", "logo list" in page && "web address" in page)
        for (statement in listOf(page, privacy)) {
            assertTrue("no cookies" in statement.lowercase() || "No cookies" in statement)
            assertTrue("Fetch brand logos" in statement)
        }
    }

    @Test
    fun theOnlyNetworkClassAsksForAListedLogoStrictly() {
        val fetcher = main("data/logo/LogoFetcher.kt")
        val listed = fetcher.substringAfter("fun fetchListed(").substringBefore("private fun download(")
        assertTrue("https only", "\"https\"" in listed && "followListed" in listed)
        assertTrue("no automatic redirects", "instanceFollowRedirects = false" in listed)
        assertTrue("a redirect stays on the same host", "next.host.equals(host" in listed)
        assertTrue("a size cap", "LISTED_MAX_BYTES" in listed && "LISTED_MAX_SIDE" in listed)
        assertTrue("no connection cache", "useCaches = false" in listed)
        for (forbidden in listOf("Cookie", "Referer", "Authorization", "setDoOutput", "POST", "openStream")) {
            assertFalse("$forbidden must not be used to ask for a listed logo", forbidden in listed)
        }
        assertFalse("the fetcher never installs a cookie store", "CookieHandler" in fetcher || "CookieManager" in fetcher)
        // The class is still the only one that touches the network (ManifestPolicyTest pins the list of files).
        val others = File(appRoot(), "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "LogoFetcher.kt" }
            .filter { Regex("HttpURLConnection|openConnection|java\\.net\\.URL").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertFalse("a new class asks the network", others.any { it == "ImageStore.kt" || it == "BundledLogos.kt" || it == "LogoListRepositoryImpl.kt" })
    }

    @Test
    fun aListedLogoFollowsTheSameSwitchAsOtherLogos_andIsCachedAndLazy() {
        val store = main("data/images/ImageStore.kt")
        val fetch = store.substringAfter("private fun fetchWebLogo(").substringBefore("private fun scaledDown(")
        assertTrue("offline (demo) and the Fetch brand logos switch stop it", "offline || !logosEnabled()" in fetch)
        assertTrue("an address that is not clean is never asked for", "LogoList.cleanUrl(url) != url" in fetch)
        assertTrue("a miss is not retried at once", "recentlyFailed(url)" in fetch)
        assertTrue("it is kept on disk and read from there first", fetch.indexOf("decodeFile(file)") in 0 until fetch.indexOf("logoFetcher.fetchListed"))
        assertTrue("one request at a time per address", "inFlight.add(url)" in fetch)
        // Turning logos off forgets these files too (they live in the same folder).
        assertTrue("logoDir.listFiles()?.forEach { it.delete() }" in store.substringAfter("fun clearLogos()").substringBefore("private fun recentlyFailed"))
        // Lazy: nothing is downloaded until a screen asks for a logo it is showing; the list is read off the main thread.
        assertFalse("peekLogo never reads a file or the network", "fetchWebLogo" in store.substringAfter("fun peekLogo(").substringBefore("private fun webLogoUrl("))
        val bundled = main("data/logo/BundledLogos.kt")
        assertTrue("Dispatchers.IO" in bundled && "loadInBackground" in bundled && "by lazy" !in bundled)
        assertTrue("the demo never reads or fetches the list", "if (!demo) bundledLogos.loadInBackground" in main("di/AppContainer.kt"))
    }

    @Test
    fun thePersonsOwnPictureWinsOverTheListedOne() {
        val common = main("presentation/messages/components/MessageCommon.kt")
        val picture = common.substringAfter("private fun rememberSenderPicture(").substringBefore("private const val MONOGRAM_SCALE")
        // A chosen picture (imageKey) is looked at before any logo, in both the in-memory and the loading branch.
        assertEquals(2, Regex("""imageKey != null ->""").findAll(picture).count())
        assertTrue(picture.indexOf("imageKey != null -> store.peekChosen") < picture.indexOf("store.peekLogo"))
        assertTrue(picture.indexOf("imageKey != null -> store.loadChosen") < picture.indexOf("store.loadLogo"))
        // And a logo turned off for a name is off for the listed one too.
        val store = main("data/images/ImageStore.kt")
        assertTrue("LogoList.resolve(BrandLogo.names(name, original), table, ::isHidden)" in store)
        assertTrue("hasWebLogo" in store.substringAfter("fun hideLogo(").substringBefore("fun showLogo("))
    }

    @Test
    fun onlyAPayeesWebPictureIsRecorded_withNothingButItsKeyAndAddress() {
        val detail = main("presentation/messages/components/MessageDetailSheet.kt")
        assertTrue("only the payee's picture records, never a single payment's or a sender's", "logoName = if (payeeCase) it else null" in detail)
        assertFalse("the single-payment picker records nothing", Regex("""rememberPicturePicker\(thisMessageKey[^\n]*logoName""").containsMatchIn(detail))
        val picker = main("presentation/common/PicturePicker.kt")
        assertTrue("it is recorded only after the picture was saved", picker.indexOf("saveChosenBitmapDetached(key, bitmap)") < picker.indexOf("logoList.record(logoName, address)"))
        assertTrue("a photo from the phone forgets the old address", "logoName?.let(logoList::forget)" in picker)
        assertFalse("the picker records no bytes", "compress(" in picker)
        val repo = main("data/repository/LogoListRepositoryImpl.kt")
        assertTrue("LogoList.keyOf(name)" in repo && "LogoList.cleanUrl(url)" in repo)
        // The export is LogoList.toJson of the person's own list, shared as a file the way the parsing report is.
        val vm = main("presentation/settings/SettingsViewModel.kt")
        val export = vm.substringAfter("Intent.ExportLogoList ->").substringBefore("Intent.ExportSetup ->")
        assertTrue("LogoList.toJson(entries)" in export && "Effect.ShareData(" in export && "application/json" in export)
        assertFalse("the export reads nothing but the logo list", listOf("inbox", "nicknames", "categoryOverrides", "cardRepository", "setupExtras").any { it in export })
        assertTrue("the Settings card is on the Messages page next to Parsing health", "LogoListCard(onExport = { onIntent(Intent.ExportLogoList) })" in main("presentation/settings/SettingsScreen.kt"))
    }

    @Test
    fun theOwnListTravelsWithTheBackupLikeTheNicknames_andTheBundledOneIsNotAPreferenceFile() {
        val backup = main("data/backup/PrefsBackup.kt")
        assertFalse("logo_urls is the person's own setup, backed up like the nicknames", Regex("""EXCLUDED = setOf\([^)]*"logo_urls"""").containsMatchIn(backup))
        assertFalse("nicknames are backed up", Regex("""EXCLUDED = setOf\([^)]*"nicknames"""").containsMatchIn(backup))
        assertTrue("\"logo_urls\"" in main("data/repository/LogoListRepositoryImpl.kt"))
    }

    @Test
    fun noLabelledReturnInTheNewComposables() {
        for (path in listOf("presentation/settings/LogoListUi.kt", "presentation/common/PicturePicker.kt")) {
            assertFalse("D8 rejects a labelled return in a composable lambda ($path)", Regex("""return@\w+""").containsMatchIn(main(path)))
        }
    }
}
