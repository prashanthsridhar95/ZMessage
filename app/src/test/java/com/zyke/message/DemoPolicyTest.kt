package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo mode must show only made-up data and touch nothing real. These tests read the sources, so a change that opens a path
 * from the demo to the person's own preferences, files, notifications, backups or the network fails the build.
 */
class DemoPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun text(path: String) = File(main, path).readText()
    private fun kotlinFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every `by lazy` store in the container is built from `context` (which is a DemoContext in Demo mode), never from another context. */
    @Test fun theContainerBuildsEveryStoreFromItsOwnContext_andSwapsTheInbox() {
        val container = text("di/AppContainer.kt")
        val built = Regex("""\w+Impl\(([^)]*)\)""").findAll(container).map { it.groupValues[1] }.toList()
        assertTrue("stores are built from `context` only: $built", built.size > 30 && built.all { it == "context" || it == "smsDataSource" })
        assertTrue(container.contains("if (demo) DemoSmsRepository(demoData.messages) else SmsRepositoryImpl(smsDataSource)"))
        assertTrue("no disk cache of demo messages", container.contains("if (demo) NoClassificationCache else EncryptedClassificationCache"))
        assertTrue("no logo or picture downloads", container.contains("ImageStore(context, offline = demo)"))
        assertTrue("fixed market prices", container.contains("fixedQuotes = if (demo) demoData.quotes else null"))
        assertTrue("no Drive sign-in in the demo", container.contains("if (demo) null else"))
        assertTrue(container.contains("if (demo) DemoSeeder.seed(this, demoData)"))
        val app = text("ZykeMessagesApplication.kt")
        assertTrue(app.contains("AppContainer(com.zyke.message.data.demo.DemoContext(this), demo = true)"))
        // The switch is read before anything that can consult it.
        assertTrue(app.indexOf("DemoMode.init(this)") in 0 until app.indexOf("container.warmUp()"))
    }

    @Test fun theDemoContextHasNoWayBackToTheRealStorage() {
        val context = text("data/demo/DemoContext.kt")
        for (member in listOf("getSharedPreferences", "getFilesDir", "getCacheDir", "getDatabasePath", "openFileOutput", "openFileInput", "getApplicationContext", "getDir", "deleteSharedPreferences", "moveSharedPreferencesFrom")) {
            assertTrue("DemoContext must override $member", context.contains("fun $member("))
        }
        assertFalse("DemoContext must not hand out the base context", Regex("""\bbaseContext\b|\bgetBaseContext\b""").containsMatchIn(context))
        // The only real thing the demo package reads or writes is its own switch file.
        val real = Regex("""getSharedPreferences\(([^,)]+)""")
        val demoFiles = File(main, "data/demo").listFiles().orEmpty().filter { it.extension == "kt" }
        val used = demoFiles.flatMap { f -> real.findAll(f.readText()).map { it.groupValues[1].trim() } }.filter { !it.startsWith("name:") }
        assertTrue("only DemoMode.PREFS may be opened for real: $used", used.all { it == "PREFS" })
    }

    @Test fun theSwitchFileIsLeftOutOfEveryBackup() {
        val backup = text("data/backup/PrefsBackup.kt")
        assertTrue(Regex("""EXCLUDED = setOf\([^)]*"demo_mode"""").containsMatchIn(backup))
        assertEquals("demo_mode", Regex("""const val PREFS = "([^"]+)"""").find(text("data/demo/DemoMode.kt"))!!.groupValues[1])
        assertTrue(backup.contains("check(!com.zyke.message.data.demo.DemoMode.active)"))
        // Android's own backup takes only the encrypted file (ManifestPolicyTest); and the backup engine, runner, scheduler and job refuse in the demo.
        for (f in listOf("data/backup/BackupEngine.kt", "data/backup/BackupRunner.kt", "data/backup/BackupScheduler.kt", "data/backup/BackupJobService.kt")) {
            assertTrue("$f must refuse to run in Demo mode", text(f).contains("DemoMode.active"))
        }
        assertTrue("Delete all must not reach the real files from the demo", text("data/DataWipe.kt").contains("DemoMode.active"))
    }

    @Test fun nothingIsPostedScheduledOrReadInTheBackgroundWhileDemoIsOn() {
        for (f in listOf(
            "notifications/SmsReceiver.kt", "notifications/DigestReceiver.kt", "notifications/WeeklyReceiver.kt", "notifications/BootReceiver.kt",
            "notifications/ReminderAlarms.kt", "notifications/DigestScheduler.kt", "notifications/WeeklyScheduler.kt", "notifications/LiveAlerts.kt",
            "notifications/MarketCloseReceiver.kt", "notifications/MarketCloseScheduler.kt",
            "notifications/CodeSharer.kt", "notifications/CodeShareStatusReceiver.kt", "presentation/settings/CodeSharingUi.kt",
            "notifications/BalanceRequester.kt", "presentation/home/BalanceCheckUi.kt",
            "notifications/DigestProvider.kt", "notifications/DaysAheadWidget.kt", "notifications/QuickAddWidget.kt", "data/sms/RcsNotificationListener.kt",
            "presentation/settings/AppIconSwitcher.kt"
        )) assertTrue("$f must check DemoMode.active", text(f).contains("DemoMode.active"))
        val digest = text("notifications/DigestReceiver.kt")
        assertTrue("canNotify is false in the demo", Regex("""fun canNotify\(context: Context\): Boolean =\s*!com\.zyke\.message\.data\.demo\.DemoMode\.active""").containsMatchIn(digest))
    }

    @Test fun theDemoNeverSendsATextOrReadsContacts() {
        val container = text("di/AppContainer.kt")
        assertTrue("no code sharing in the demo", container.contains("if (demo) com.zyke.message.domain.sharing.NoCodeSharing else com.zyke.message.data.repository.CodeSharingRepositoryImpl(context)"))
        assertTrue("balance requests are in memory only in the demo", container.contains("if (demo) com.zyke.message.domain.balance.InMemoryBalanceCheck() else com.zyke.message.data.repository.BalanceCheckRepositoryImpl(context)"))
        val requester = text("notifications/BalanceRequester.kt")
        assertTrue("a balance request stops in the demo", requester.substringAfter("fun send(").take(200).contains("DemoMode.active"))
        // The sheet answers a Send in the demo with a labelled stand-in, before anything is handed to the requester.
        val checkUi = text("presentation/home/BalanceCheckUi.kt")
        assertTrue(checkUi.indexOf("Demo: balance updated") in 0 until checkUi.indexOf("BalanceRequester.send("))
        val sharer = text("notifications/CodeSharer.kt")
        for (door in listOf("fun onLiveMessage(", "fun sendTest(", "fun sendPlain(")) {
            assertTrue("$door must stop in the demo", sharer.substringAfter(door).take(300).contains("DemoMode.active"))
        }
        assertTrue("canSend is false in the demo", sharer.contains("fun canSend(context: Context): Boolean =\n        !DemoMode.active"))
        val page = text("presentation/settings/CodeSharingUi.kt")
        // The page is shown as a notice only; the picker and the permission prompts are in CodeSharingContent.
        assertTrue(page.substringAfter("internal fun CodeSharingPage(").substringBefore("CodeSharingContent()").contains("DemoMode.active"))
    }

    @Test fun theDemoNeverAsksForPermissionsOrReadsTheInbox() {
        val activity = text("presentation/MainActivity.kt")
        val flow = activity.substringAfter("private fun startPermissionFlow()").substringBefore("private fun askNextPermission")
        assertTrue(flow.contains("DemoMode.active"))
        for (screen in listOf("presentation/home/HomeScreen.kt", "presentation/messages/MessagesScreen.kt", "presentation/dashboard/DashboardScreen.kt", "presentation/settings/SettingsScreen.kt")) {
            assertTrue("$screen must treat the demo as permitted", text(screen).contains("DemoMode.active || ContextCompat.checkSelfPermission"))
        }
        assertFalse("the demo repository must not touch the provider", text("data/demo/DemoSmsRepository.kt").let { "ContentResolver" in it || "Telephony" in it || "SmsDataSource" in it })
        assertTrue(text("presentation/common/PicturePicker.kt").contains("DemoMode.active"))
    }

    @Test fun marketPricesAreFixedInTheDemo_andNoOtherFileGainsNetworkCode() {
        val ticker = text("data/markets/MarketTicker.kt")
        assertTrue(ticker.contains("fixedQuotes"))
        // Each of the ticker's four network doors returns early with fixed prices.
        for (door in listOf("suspend fun refresh()", "suspend fun check(", "suspend fun checkTyped(", "suspend fun search(", "suspend fun history(")) {
            val body = ticker.substringAfter(door).take(600)
            assertTrue("$door must answer from fixed prices in the demo", body.contains("fixedQuotes"))
        }
        val demoCode = kotlinFiles().filter { it.path.contains("/demo/") }.joinToString("\n") { it.readText() }
        assertFalse(Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp").containsMatchIn(demoCode))
    }

    @Test fun theBannerIsOnEveryTab_andSettingsCanStartAndLeave() {
        assertTrue(text("presentation/AppRoot.kt").contains("DemoBanner()"))
        val settings = text("presentation/settings/SettingsScreen.kt")
        assertTrue(settings.contains("SettingsGroup.DEMO -> com.zyke.message.presentation.demo.DemoModeSettings()"))
        val ui = text("presentation/demo/DemoUi.kt")
        assertTrue(ui.contains("DemoSwitch.enter(context)") && ui.contains("DemoSwitch.exit(context)"))
        assertTrue("leaving asks first", ui.contains("ExitDemoDialog"))
    }
}
