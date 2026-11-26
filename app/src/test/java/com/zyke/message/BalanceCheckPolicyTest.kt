package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Check balance by SMS" sends a text, so its doors are pinned here: a text leaves only through CodeSharer, only from the Send button
 * of the sheet that showed the exact number and words (the first time for a bank) or from a tap on a bank the person already
 * confirmed there, or, for "Refresh balances before my digest", from the one receiver the digest's own alarm starts and only for banks
 * confirmed there; and no number, words or UAN reach a log, a notification or a backup.
 */
class BalanceCheckPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun kotlinFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun text(path: String) = File(main, path).readText()
    private fun code(file: File) = file.readLines().filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/*") }
    private val ui = "presentation/home/BalanceCheckUi.kt"
    private val ours = listOf("notifications/BalanceRequester.kt", ui, "data/repository/BalanceCheckRepositoryImpl.kt", "notifications/BalanceRefresher.kt")

    @Test fun onlyCodeSharerTouchesTheSmsManager() {
        val senders = kotlinFiles().filter { f -> code(f).any { Regex("""SmsManager|sendTextMessage|sendMultipartTextMessage""").containsMatchIn(it) } }.map { it.name }
        assertEquals(listOf("CodeSharer.kt"), senders)
        for (name in ours) assertFalse(name, code(File(main, name)).any { Regex("""SmsManager|sendTextMessage|SmsMessage\.|Telephony\.Sms""").containsMatchIn(it) })
    }

    @Test fun aRequestIsSentOnlyFromTheSendButton_orATapOnAnAlreadyConfirmedBank() {
        val callers = kotlinFiles().filter { f -> code(f).any { it.contains("BalanceRequester.send(") } }.map { it.name }
        // The second caller is the refresh before the digest (see theRefreshBeforeTheDigest...).
        assertEquals(listOf("BalanceCheckUi.kt", "BalanceRefresher.kt"), callers.sorted())
        val page = text(ui)
        assertEquals(1, Regex("""BalanceRequester\.send\(""").findAll(page).count())
        // One Send button; it reaches the requester only through onSend, which asks for permission first if it has none.
        assertEquals(1, Regex("""AppButton\("Send", \{ onSend\(plan\) \}""").findAll(page).count())
        assertEquals(1, Regex("""\bonSend\(""").findAll(page.replace("fun onSend(", "")).count())
        // The number and the words on screen and the ones sent are the same plan.
        assertTrue(page.contains("Text(plan.number,") && page.contains("Text(plan.text,"))
        assertTrue(page.contains("send(ready)") && page.contains("ready.number, ready.text"))
        // Both doors reach the requester through one function.
        assertEquals(2, Regex("""dispatchRequest\(context, container, target\.requestKey, ready\)""").findAll(page).count())
        // The second door, a tap, sends at once only when the flow says so: the bank was confirmed before and the plan is complete.
        val tap = page.substringAfter("fun rememberCheckTap(").substringBefore("fun CheckBalanceRow")
        assertTrue(tap.contains("BalanceCheckFlow.onTap(confirmKey(target) in skip, plan) == TapAction.CONFIRM_FIRST) openSheet()"))
        assertTrue("a provident fund always shows the sheet", tap.contains("target.isProvidentFund ||"))
        // "Skip the sheet" is written only from the sheet, which shows the checkbox (ticked by default) and the settings switch.
        assertTrue(page.contains("var alwaysSend by rememberSaveable { mutableStateOf(true) }"))
        assertEquals(3, Regex("""setSkipConfirm\(""").findAll(page).count())
        for (f in kotlinFiles().filter { it.name != "BalanceCheckUi.kt" && it.name != "BalanceCheckRepositoryImpl.kt" }) assertFalse(f.name, f.readText().contains("setSkipConfirm("))
        // Demo sends nothing from either door.
        assertTrue(tap.contains("if (DemoMode.active)"))
        // The confirm: equal-width Cancel and Send in a ResponsiveRow, the rates note and the published-list note.
        val footer = page.substringAfter("plan is EnquiryPlan.Ready -> ResponsiveRow").substringBefore("else -> AppButton")
        assertTrue(footer.contains("""AppButton("Cancel", onDismiss, Modifier.cell()""") && footer.contains("""AppButton("Send", { onSend(plan) }, Modifier.cell()"""))
        assertTrue(page.contains("BalanceEnquiry.NOTE_RATES") && page.contains("BalanceEnquiry.NOTE_UNVERIFIED"))
        assertTrue(page.contains("Always send without asking for this bank") && page.contains("Ask before sending"))
    }

    @Test fun theSlotKeepsOneSize_andThePreferenceStaysOnThisPhone() {
        val page = text(ui)
        // The progress is determinate (driven by a one-second tick): no looping animation.
        assertFalse(page.contains("infiniteRepeatable") || page.contains("CircularProgressIndicator()"))
        assertTrue(page.contains("LinearProgressIndicator(progress = { progress }"))
        assertTrue(page.contains("""heightIn(min = 48.dp)"""))
        assertTrue(text("data/repository/BalanceCheckRepositoryImpl.kt").contains("""device.edit().putStringSet(KEY_SKIP"""))
        assertTrue(text("presentation/home/AccountsUi.kt").contains("Balance check settings"))
    }

    @Test fun nothingIsScheduledAndNoReceiverStartsARequest() {
        for (name in ours) {
            assertFalse(name, code(File(main, name)).any { Regex("""AlarmManager|WorkManager|JobScheduler|postDelayed|Handler\(|setExact|schedule""").containsMatchIn(it) })
        }
        // No receiver but the status receiver (which only moves an existing request on) knows the requester.
        val receivers = kotlinFiles().filter { f -> code(f).any { it.contains(": BroadcastReceiver()") } && f.name != "CodeShareStatusReceiver.kt" }
        assertTrue(receivers.isNotEmpty())
        for (receiver in receivers) assertFalse(receiver.name, receiver.readText().contains("BalanceRequester"))
        // The status receiver only moves an existing request's status.
        val status = text("notifications/CodeShareStatusReceiver.kt")
        assertTrue(status.contains("BalanceRequester.onReport("))
        assertFalse(status.contains("BalanceRequester.send("))
    }

    @Test fun permissionIsAskedOnlyOnTheFirstSendTap_andNoPhoneStateIsNeeded() {
        val page = text(ui)
        assertEquals(1, Regex("""askToSend\.launch\(""").findAll(page).count())
        assertTrue(page.substringAfter("fun onSend(").substringBefore("fun openMessagesApp").contains("askToSend.launch(Manifest.permission.SEND_SMS)"))
        // The tap door asks the same way, once, and falls back to the sheet when it is refused.
        assertEquals(1, Regex("""askFirst\.launch\(""").findAll(page).count())
        assertTrue(page.contains("if (ok && ready != null) fire(ready) else openSheet()"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("the SIM is the phone's default for texts: READ_PHONE_STATE is not needed", manifest.contains("READ_PHONE_STATE"))
        assertFalse(kotlinFiles().any { f -> code(f).any { it.contains("getActiveSubscriptionInfoList") || it.contains("activeSubscriptionInfoList") } })
    }

    @Test fun noNumberWordsOrUanIsLoggedOrShownInANotification() {
        val risky = Regex("""\.number|\.text\b|\.template|\buan\b|\bUan\b|\bplan\b|\bready\b|\bnumber\b|\btext\b|\.body\b""")
        for (name in ours + "notifications/CodeSharer.kt") {
            for (line in code(File(main, name))) {
                if (Regex("""DevLog\.|Log\.[dievw]\(|println|BlackBox|NotificationCompat|notify\(""").containsMatchIn(line) && !line.contains("fun sendPlain")) {
                    assertFalse("$name logs or posts something that may hold a number, words or a UAN: ${line.trim()}", risky.containsMatchIn(line))
                }
            }
            assertFalse(name, code(File(main, name)).filter { !it.startsWith("import ") }.any { Regex("""\b(e|it|error|t)\.message\b|localizedMessage|stackTraceToString""").containsMatchIn(it) })
        }
        assertFalse("the requester never posts a notification", text("notifications/BalanceRequester.kt").contains("Notification"))
    }

    @Test fun theUanStaysOnThisPhone_andOnlyThePresetChangesTravelInABackup() {
        val backup = text("data/backup/PrefsBackup.kt")
        assertTrue(Regex("""EXCLUDED = setOf\([^)]*"balance_check_device"""").containsMatchIn(backup))
        assertFalse(Regex("""EXCLUDED = setOf\([^)]*"balance_check"[,)]""").containsMatchIn(backup))
        val repo = text("data/repository/BalanceCheckRepositoryImpl.kt")
        // The UAN is written to the device file only.
        assertTrue(repo.contains("""device.edit().putString(KEY_UAN"""))
        assertFalse(repo.contains("""prefs.edit().putString(KEY_UAN"""))
        val saved = Regex("""\.put\("(\w+)"""").findAll(repo.substringAfter("private fun changeSetup").substringBefore("private fun changeRequests")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("key", "number", "template", "name"), saved)
        val requests = Regex("""\.put\("(\w+)"""").findAll(repo.substringAfter("private fun changeRequests").substringBefore("private fun readSetup")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("key", "at", "status"), requests)
    }

    @Test fun presetsAreMarkedUnverified_andAnMpinIsNeverStored() {
        val domain = File("../domain/src/main/kotlin/com/zyke/message/domain/balance/BalanceEnquiry.kt").readText()
        assertTrue(domain.contains("UNVERIFIED"))
        assertTrue(domain.contains("MPIN_REASON"))
        assertFalse(Regex("""(?i)(?:val|var)\s+(?!MPIN_REASON)\w*mpin\w*\s*[:=]""").containsMatchIn(domain + ours.joinToString { text(it) }))
    }

    @Test fun theRefreshBeforeTheDigestIsOffByDefault_hasOneDoor_andTextsOnlyWhatWasConfirmed() {
        val refresher = text("notifications/BalanceRefresher.kt")
        val receiver = text("notifications/BalanceRefreshReceiver.kt")
        val scheduler = text("notifications/DigestScheduler.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // Off until chosen, in the model and in what is read back.
        assertTrue(File("../domain/src/main/kotlin/com/zyke/message/domain/model/AppSettings.kt").readText().contains("val refreshBalancesBeforeDigest: Boolean = false"))
        assertTrue(text("data/repository/SettingsRepositoryImpl.kt").contains("prefs.getBoolean(REFRESH_BALANCES, false)"))
        // One call into the requester, started only by its own receiver, which is not exported, has no filter and does nothing in Demo.
        assertEquals(1, Regex("""BalanceRequester\.send\(""").findAll(refresher).count())
        assertEquals(listOf("BalanceRefreshReceiver.kt"), kotlinFiles().filter { f -> code(f).any { it.contains("BalanceRefresher.run(") } }.map { it.name })
        val block = manifest.substringAfter("notifications.BalanceRefreshReceiver").substringBefore("/>")
        assertTrue(block.contains("""android:exported="false"""") && !block.contains("intent-filter"))
        assertTrue(receiver.contains("DemoMode.active) return") && !receiver.contains("BalanceRequester"))
        // Only when the digest and the switch are both on, never in Demo; only banks confirmed with "Always send without asking",
        // through the pure rules, to the number and words of a ready plan.
        assertTrue(refresher.contains("!DemoMode.active && settings.morningDigest && settings.refreshBalancesBeforeDigest"))
        assertTrue(refresher.contains("BalanceEnquiry.bankKey(a.bankName, a.bankLabel) in confirmed"))
        assertTrue(refresher.contains("BalanceRefresh.plan(") && refresher.contains("as? EnquiryPlan.Ready"))
        assertTrue(refresher.contains("BalanceRequester.send(context, container, entry.key, plan.number, plan.text)"))
        // Never a card or a provident fund, and the spacing and window live in the tested domain object.
        assertTrue(refresher.contains("!it.isCard && !it.isProvidentFund"))
        // The alarm is the digest's own kind: inexact, allowed while idle, no exact alarm and no new permission, set only by the scheduler.
        assertTrue(scheduler.contains("setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, refreshIntent(forDigest))"))
        assertFalse(Regex("""setExact|setAlarmClock|SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM""").containsMatchIn(scheduler + manifest))
        assertEquals(1, Regex("""android\.permission\.SEND_SMS""").findAll(manifest).count())
        assertEquals(listOf("DigestScheduler.kt"), kotlinFiles().filter { f -> code(f).any { it.contains("refreshIntent(") } }.map { it.name })
        // The switch says what it does, asks for the permission when it is turned on, and is disabled without the digest.
        val setting = text("presentation/settings/BalanceRefreshSetting.kt")
        assertTrue(setting.contains("These are real texts and may cost money on some plans."))
        assertTrue(setting.contains("asker.launch(Manifest.permission.SEND_SMS)") && setting.contains("enabled = digestOn"))
        // The log shows the outcome per account, in the settings and in each bank's sheet.
        assertTrue(setting.contains("BalanceRefresh.logLine(") && text(ui).contains("BalanceRefresh.logLine("))
        // The digest only reads the outcome (a count, no amount) and waits at most the capped time.
        assertTrue(text("notifications/DigestReceiver.kt").contains("BalanceRefresher.awaitReplies(") && refresher.contains("BalanceRefresh.DIGEST_WAIT_CAP_MS"))
    }
}
