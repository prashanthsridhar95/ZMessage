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
        val decide = kotlinFiles().filter { f -> code(f).any { it.contains("CodeSharing.decide(") } }.map { it.name }
        assertEquals(listOf("CodeSharer.kt"), decide)
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
        val files = listOf("notifications/CodeSharer.kt", "notifications/CodeShareStatusReceiver.kt", "data/repository/CodeSharingRepositoryImpl.kt", "presentation/settings/CodeSharingUi.kt")
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
        assertEquals(listOf("id", "service", "contact", "name", "at", "status", "test"), logFields)
        // The notification text comes from the log wording, which has the service and the name only.
        val sharer = text("notifications/CodeSharer.kt")
        assertTrue(sharer.contains(".setContentTitle(ShareLog.headline(entry))"))
    }

    @Test fun theLogIsLeftOutOfBackups_andTheSetupIsNot() {
        val backup = text("data/backup/PrefsBackup.kt")
        assertTrue(Regex("""EXCLUDED = setOf\([^)]*"code_sharing_log"""").containsMatchIn(backup))
        assertFalse(Regex("""EXCLUDED = setOf\([^)]*"code_sharing"[,)]""").containsMatchIn(backup))
    }
}
