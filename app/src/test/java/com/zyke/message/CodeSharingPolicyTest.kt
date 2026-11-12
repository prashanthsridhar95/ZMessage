package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Share codes with trusted people" sends an SMS on its own, so the doors to it are pinned here: where it can be called from,
 * who may touch the SMS sender, and that no code, number or message text reaches a log, a file or a notification.
 */
class CodeSharingPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun kotlinFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun text(path: String) = File(main, path).readText()
    private fun code(file: File) = file.readLines().filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/*") }

    @Test fun onlyTheLiveReceiverCallsTheSender_soReadingTheInboxNeverForwardsAnything() {
        val callers = kotlinFiles().filter { f -> code(f).any { it.contains("onLiveMessage(") } && f.name != "CodeSharer.kt" }.map { it.name }
        assertEquals(listOf("SmsReceiver.kt"), callers)
        val decide = kotlinFiles().filter { f -> code(f).any { it.contains("CodeSharing.decide(") || it.contains("CodeSharing.review(") } }.map { it.name }
        assertEquals(listOf("CodeSharer.kt"), decide)
        // The test code is started from the page but takes the very same path (CodeSharer.handle), nothing of its own.
        val testCallers = kotlinFiles().filter { f -> code(f).any { it.contains("sendTestCode(") } && f.name != "CodeSharer.kt" }.map { it.name }
        assertEquals(listOf("CodeSharingUi.kt"), testCallers)
        val sharer = text("notifications/CodeSharer.kt")
        assertTrue(sharer.substringAfter("fun sendTestCode").substringBefore("fun sendPlain").contains("handle(context, container, fake, rules, test = contact)"))
        assertEquals(2, Regex("""\bhandle\(context, container""").findAll(code(File(main, "notifications/CodeSharer.kt")).joinToString("\n")).count())
        // And it is called after classification, from the broadcast of a new message.
        val receiver = text("notifications/SmsReceiver.kt")
        assertTrue(receiver.indexOf("messageClassifier.classify") in 0 until receiver.indexOf("CodeSharer.onLiveMessage"))
        assertTrue(receiver.contains("SMS_RECEIVED_ACTION"))
    }

    @Test fun onlyCodeSharerSendsTexts_andNothingAsksForContacts() {
        val senders = kotlinFiles().filter { f -> code(f).any { Regex("""SmsManager|sendTextMessage|sendMultipartTextMessage""").containsMatchIn(it) } }.map { it.name }
        assertEquals(listOf("CodeSharer.kt"), senders)
        val permission = kotlinFiles().filter { f -> code(f).any { it.contains("permission.SEND_SMS") } }.map { it.name }.sorted()
        // "Check balance by SMS" asks on its own Send button (see BalanceCheckPolicyTest); it sends through CodeSharer.sendPlain.
        assertEquals(listOf("BalanceCheckUi.kt", "CodeSharer.kt", "CodeSharingUi.kt"), permission)
        assertFalse("READ_CONTACTS must never be requested", File("src/main/AndroidManifest.xml").readText().contains("CONTACTS"))
        assertFalse(kotlinFiles().any { f -> code(f).any { it.contains("READ_CONTACTS") } })
    }

    @Test fun theSendPermissionIsOnlyAskedForWhenTheSwitchIsTurnedOn() {
        val page = text("presentation/settings/CodeSharingUi.kt")
        // The runtime request sits behind the master switch, the "allow" buttons of the health card and the test button.
        assertTrue(page.contains("askToTurnOn.launch(needed)"))
        for (other in kotlinFiles().filter { it.name != "CodeSharingUi.kt" && it.name != "BalanceCheckUi.kt" }) {
            assertFalse("${other.name} must not request SEND_SMS", code(other).any { it.contains("SEND_SMS") && it.contains("launch") })
        }
        assertEquals(1, Regex("""android\.permission\.SEND_SMS""").findAll(File("src/main/AndroidManifest.xml").readText()).count())
    }

    @Test fun noCodeNumberOrMessageTextIsLoggedStoredOrPutInANotification() {
        val files = listOf("notifications/CodeSharer.kt", "notifications/CodeShareStatusReceiver.kt", "data/repository/CodeSharingRepositoryImpl.kt", "presentation/settings/CodeSharingUi.kt", "presentation/settings/CodeSharingSources.kt")
        val risky = Regex("""\.number|\.text\b|codeKey|\.body\b|\.code\b|\$\{?(code|number|text|body)\b""")
        for (name in files) {
            for (line in code(File(main, name))) {
                if (Regex("""DevLog\.|Log\.[dievw]\(|println|BlackBox""").containsMatchIn(line)) {
                    assertFalse("$name logs something that may hold a code, number or text: ${line.trim()}", risky.containsMatchIn(line))
                }
            }
        }
        // Exceptions are logged by class only, never their message (it can quote a number).
        for (name in files) assertFalse("$name", code(File(main, name)).filter { !it.startsWith("import ") && !it.startsWith("package ") }.any { Regex("""\b(e|it|error|t)\.message\b|localizedMessage|stackTraceToString""").containsMatchIn(it) })
        // The saved log has these fields and no others.
        val repo = text("data/repository/CodeSharingRepositoryImpl.kt")
        val logFields = Regex("""\.put\("(\w+)"""").findAll(repo.substringAfter("private fun logJson").substringBefore("private companion")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("id", "service", "contact", "name", "at", "status", "test", "label"), logFields)
        // The notification text comes from the log wording, which has the service and the name only.
        val sharer = text("notifications/CodeSharer.kt")
        assertTrue(sharer.contains(".setContentTitle(ShareLog.headline(entry))"))
    }

    @Test fun theLogIsLeftOutOfBackups_andTheSetupIsNot() {
        val backup = text("data/backup/PrefsBackup.kt")
        assertTrue(Regex("""EXCLUDED = setOf\([^)]*"code_sharing_log"""").containsMatchIn(backup))
        assertFalse(Regex("""EXCLUDED = setOf\([^)]*"code_sharing"[,)]""").containsMatchIn(backup))
    }

    @Test fun theSeenListHoldsOnlyHeadersTimesAndOutcomes_andIsLeftOutOfBackups() {
        val repo = text("data/repository/CodeSharingRepositoryImpl.kt")
        val seenFields = Regex("""\.put\("(\w+)"""").findAll(repo.substringAfter("private fun seenJson").substringBefore("private fun readContacts")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("id", "at", "header", "service", "outcome", "detail", "test", "label"), seenFields)
        val backup = text("data/backup/PrefsBackup.kt")
        assertTrue(Regex("""EXCLUDED = setOf\([^)]*"code_sharing_seen"""").containsMatchIn(backup))
        // What is added to the list is built by SeenCodes.entry (header masked, detail cleaned) and nothing else.
        val adders = kotlinFiles().filter { f -> code(f).any { it.contains("addSeen(") } && f.name != "CodeSharingRepositoryImpl.kt" }
        assertEquals(listOf("CodeSharer.kt"), adders.map { it.name })
        for (line in code(File(main, "notifications/CodeSharer.kt")).filter { it.contains("addSeen(") }) assertTrue(line, line.contains("SeenCodes.entry("))
        // The receiver notes a time only.
        val receiver = text("notifications/SmsReceiver.kt")
        assertTrue(receiver.contains("noteReceiverFired(System.currentTimeMillis())"))
        // No code, body or number goes into the seen entry.
        val handle = text("notifications/CodeSharer.kt").substringAfter("private fun handle").substringBefore("enum class TestResult")
        for (line in handle.lines().filter { it.contains("SeenCodes.entry(") }) assertFalse(line, Regex("""\.code\b|\.body\b|\.number\b|\.text\b""").containsMatchIn(line))
    }

    @Test fun demoModeNeverReachesTheSendOrTheLog() {
        val sharer = text("notifications/CodeSharer.kt")
        assertTrue(sharer.substringAfter("fun onLiveMessage").substringBefore("private fun handle").contains("if (DemoMode.active) return"))
        assertTrue(sharer.substringAfter("fun sendTestCode").substringBefore("val service").contains("DemoMode.active"))
        assertTrue(text("notifications/SmsReceiver.kt").contains("DemoMode.active) return"))
        assertTrue(File("src/main/java/com/zyke/message/di/AppContainer.kt").readText().contains("if (demo) com.zyke.message.domain.sharing.NoCodeSharing"))
    }

    @Test fun thePageExplainsItselfAndTheTeachingNeedsAConfirmation() {
        val page = text("presentation/settings/CodeSharingUi.kt")
        assertTrue(page.contains("Recent codes seen"))
        assertTrue(page.contains("Why wasn't it sent?"))
        assertTrue(page.contains("Last text seen by the receiver: "))
        assertTrue(page.contains("Choose any sender from the codes you've received. Bank and payment codes are never shared."))
        assertTrue(page.contains("which Zyke can't see."))
        assertTrue(page.contains("Send a test code"))
        // Sharing an unchosen sender: a dialog that names what will be forwarded, and the check that refuses banks, before anything is saved.
        val dialog = page.substringAfter("sharing?.let").substringBefore("renaming?.let")
        assertTrue(dialog.contains("will be texted to the people you tick"))
        assertTrue(dialog.contains("ShareSources.problem("))
        assertTrue(dialog.indexOf("ShareSources.problem(") < dialog.indexOf("repository.addSource("))
        assertTrue(page.contains("Share this sender's codes"))
        assertFalse("the old teaching flow is gone", page.contains("learnHeader") || page.contains("ShareLearning") || page.contains("Treat \${"))
        assertFalse(page.contains("developerMode"))
        // The manifest still has the one receiver, disabled until switched on, and no new permission.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.substringAfter("notifications.SmsReceiver").substringBefore("</receiver>").contains("android:enabled=\"false\""))
    }

    @Test fun chosenSendersAreNamesOnly_theListIsReadOnlyWhileThePickerIsOpen_andRemovalIsConfirmed() {
        val repo = text("data/repository/CodeSharingRepositoryImpl.kt")
        val sourceFields = Regex("""\.put\("(\w+)"""").findAll(repo.substringAfter("private fun sourceJson").substringBefore("private fun readLog")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("id", "label", "key", "header"), sourceFields.take(4))
        val picker = text("presentation/settings/CodeSharingSources.kt")
        // The inbox is only observed while the picker is open (the `enabled` flag), and only the pure builder sees the messages.
        assertTrue(picker.contains("if (!enabled) return@produceState"))
        assertTrue(picker.contains("ReceivedCodeSenders.build("))
        assertFalse(Regex("""\.(body|code)\b""").containsMatchIn(picker))
        assertTrue(picker.contains("ShareSources.BLOCKED_REASON"))
        assertTrue(picker.contains("No codes received yet"))
        assertTrue(picker.contains("AdaptiveSheet("))
        // Removing a source asks first, and the earlier learned-header store is only read to be migrated.
        val page = text("presentation/settings/CodeSharingUi.kt")
        assertTrue(page.substringAfter("removing?.let").substringBefore("if (picking)").contains("ConfirmDialog("))
        assertTrue(repo.contains("ShareMigration.fromLearned("))
        assertFalse(Regex("""learnHeader|forgetHeader|ShareLearning""").containsMatchIn(kotlinFiles().joinToString("\n") { it.readText() }))
    }
}
