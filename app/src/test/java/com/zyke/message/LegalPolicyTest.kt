package com.zyke.message

import com.zyke.message.domain.legal.LegalInfo
import com.zyke.message.domain.legal.LegalText
import com.zyke.message.domain.legal.OpenSourceNotices
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Privacy and terms page and the Open-source licences page say what the app really does, and cannot drift from it:
 * every manifest permission is explained, the hosts the page names are the hosts in the code, every dependency is credited.
 * This keeps the words honest; it is not a legal review (see docs/LEGAL_NOTES.md).
 */
class LegalPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private fun repoRoot(): File = appRoot().canonicalFile.let { if (it.name == "app") it.parentFile else it }
    private fun main(path: String) = File(appRoot(), "src/main/java/com/zyke/message/$path").readText()

    @Test
    fun everyManifestPermissionIsExplainedOnThePage() {
        val manifest = File(appRoot(), "src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission\s+android:name="android\.permission\.([A-Z_]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue("no permissions found", declared.isNotEmpty())
        assertEquals("LegalText.permissions must list exactly the manifest's permissions", declared, LegalText.permissions.toSet())
        val text = LegalText.fullText()
        declared.forEach { assertTrue("$it is not explained in LegalText", it in text) }
        val privacyDoc = File(repoRoot(), "docs/PRIVACY.md").readText()
        declared.forEach { assertTrue("$it is not mentioned in docs/PRIVACY.md", it in privacyDoc) }
    }

    @Test
    fun theHostsOnThePageAreTheHostsInTheCode() {
        val hostPattern = Regex("""https://([a-z0-9.]+)/""")
        val files = listOf(
            "data/logo/LogoFetcher.kt", "data/markets/QuoteFetcher.kt", "data/markets/FlightFetcher.kt",
            "data/backup/DriveBackupClient.kt", "presentation/common/ImageSearchSheet.kt"
        )
        val inCode = files.flatMap { f -> hostPattern.findAll(main(f)).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals("the hosts named by LegalText.networkHosts differ from the hosts in the network code", inCode, LegalText.networkHosts)
        val text = LegalText.fullText()
        val doc = File(repoRoot(), "docs/PRIVACY.md").readText()
        LegalText.networkHosts.forEach {
            assertTrue("$it missing from the page text", it in text)
            assertTrue("$it missing from docs/PRIVACY.md", it in doc)
        }
    }

    @Test
    fun theLegalPagesAreReachableFromSettings() {
        val screen = main("presentation/settings/SettingsScreen.kt")
        assertTrue("SettingsGroup.LEGAL -> LegalContent(" in screen)
        assertTrue("SettingsGroup.LICENCES -> LicencesContent()" in screen)
        // The point-of-use links: both SMS-sending features, and the first-run screen.
        assertTrue("SeeWhatIsSentLink()" in main("presentation/settings/CodeSharingUi.kt"))
        assertTrue("SeeWhatIsSentLink()" in main("presentation/home/BalanceCheckUi.kt"))
        assertTrue("By continuing you agree to the Terms and Privacy" in main("presentation/backup/WelcomeBack.kt"))
    }

    @Test
    fun theOperatorDetailsLiveInOnePlaceAndAreFlaggedForTheOwner() {
        val info = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/legal/LegalInfo.kt").readText()
        assertTrue("LegalInfo must carry a TODO(owner) to confirm the name and address", "TODO(owner)" in info)
        // The app's code never spells the operator out itself; it all comes from LegalInfo.
        val offenders = File(appRoot(), "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && LegalInfo.CONTACT_EMAIL in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun everyDependencyIsCreditedOnTheLicencesPage() {
        val covered = OpenSourceNotices.coveredCoordinates
        val toml = File(repoRoot(), "gradle/libs.versions.toml").readText().substringAfter("[libraries]").substringBefore("[plugins]")
        val coordinates = toml.lines().mapNotNull { line ->
            val module = Regex("""module\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val group = Regex("""group\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val name = Regex("""name\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            module ?: if (group != null && name != null) "$group:$name" else null
        }
        assertTrue("no libraries parsed from libs.versions.toml", coordinates.size > 10)
        coordinates.forEach { assertTrue("$it is not credited in OpenSourceNotices", it in covered) }
        // Libraries written straight into a build file.
        listOf("app/build.gradle.kts", "wear/build.gradle.kts").forEach { path ->
            Regex(""""([a-z0-9.\-]+:[a-z0-9.\-]+):[0-9][^"]*"""").findAll(File(repoRoot(), path).readText()).map { it.groupValues[1] }.forEach {
                assertTrue("$it (in $path) is not credited in OpenSourceNotices", it in covered)
            }
        }
    }

    @Test
    fun theFontAndTheNoticeFileAreInTheRepository() {
        assertTrue(File(repoRoot(), "third_party/fonts/PlusJakartaSans-OFL.txt").isFile)
        assertEquals(
            "NOTICE is generated from OpenSourceNotices.noticeFileText(); regenerate it when the list changes",
            OpenSourceNotices.noticeFileText(), File(repoRoot(), "NOTICE").readText()
        )
    }

    @Test
    fun theDocsSayTheyAreNotLegalAdvice() {
        val notes = File(repoRoot(), "docs/LEGAL_NOTES.md").readText().lowercase()
        assertTrue("lawyer" in notes && "not legal advice" in notes)
        assertTrue("restricted" in notes && "send_sms" in notes)
    }
}
