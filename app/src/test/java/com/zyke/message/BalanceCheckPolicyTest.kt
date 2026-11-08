package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Check balance by SMS" sends a text, so its doors are pinned here: a text leaves only through CodeSharer, only from the Send button
 * of the sheet that showed the exact number and words, never on a schedule or from a receiver, and no number, words or UAN reach a
 * log, a notification or a backup.
 */
class BalanceCheckPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun kotlinFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun text(path: String) = File(main, path).readText()
    private fun code(file: File) = file.readLines().filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/*") }
    private val ui = "presentation/home/BalanceCheckUi.kt"
    private val ours = listOf("notifications/BalanceRequester.kt", ui, "data/repository/BalanceCheckRepositoryImpl.kt")

    @Test fun onlyCodeSharerTouchesTheSmsManager() {
        val senders = kotlinFiles().filter { f -> code(f).any { Regex("""SmsManager|sendTextMessage|sendMultipartTextMessage""").containsMatchIn(it) } }.map { it.name }
        assertEquals(listOf("CodeSharer.kt"), senders)
        for (name in ours) assertFalse(name, code(File(main, name)).any { Regex("""SmsManager|sendTextMessage|SmsMessage\.|Telephony\.Sms""").containsMatchIn(it) })
    }

    @Test fun aRequestIsSentOnlyFromTheSendButton_afterTheExactTextWasShown() {
        val callers = kotlinFiles().filter { f -> code(f).any { it.contains("BalanceRequester.send(") } }.map { it.name }
        assertEquals(listOf("BalanceCheckUi.kt"), callers)
        val page = text(ui)
        assertEquals(1, Regex("""BalanceRequester\.send\(""").findAll(page).count())
        // One Send button; it reaches the requester only through onSend, which asks for permission first if it has none.
        assertEquals(1, Regex("""AppButton\("Send", \{ onSend\(plan\) \}""").findAll(page).count())
        assertEquals(1, Regex("""\bonSend\(""").findAll(page.replace("fun onSend(", "")).count())
        // The number and the words on screen and the ones sent are the same plan.
        assertTrue(page.contains("Text(plan.number,") && page.contains("Text(plan.text,"))
        assertTrue(page.contains("send(ready)") && page.contains("ready.number, ready.text"))
        // The confirm: equal-width Cancel and Send in a ResponsiveRow, the rates note and the published-list note.
        val footer = page.substringAfter("plan is EnquiryPlan.Ready -> ResponsiveRow").substringBefore("else -> AppButton")
        assertTrue(footer.contains("""AppButton("Cancel", onDismiss, Modifier.cell()""") && footer.contains("""AppButton("Send", { onSend(plan) }, Modifier.cell()"""))
        assertTrue(page.contains("BalanceEnquiry.NOTE_RATES") && page.contains("BalanceEnquiry.NOTE_UNVERIFIED"))
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
}
