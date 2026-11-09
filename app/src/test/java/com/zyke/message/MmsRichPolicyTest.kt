package com.zyke.message

import com.zyke.message.domain.legal.LegalText
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picture messages (MMS) are read from the phone's MMS provider with the permission the app already has, and chat (RCS) messages only
 * from notifications. These pin the doors: read-only, bounded, no network, never in the demo, and the words that say what is and is not read.
 */
class MmsRichPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun text(path: String) = File(main, path).readText()
    private fun code(path: String) = File(main, path).readLines().filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun theMmsProviderIsOnlyEverRead() {
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" && ("content://mms" in it.readText() || "Telephony.Mms" in it.readText()) }.map { it.name }.toSet()
        assertTrue("only the two readers know the MMS provider: $files", files == setOf("MmsDataSource.kt", "MmsImages.kt"))
        for (f in listOf("data/sms/MmsDataSource.kt", "data/sms/MmsImages.kt")) {
            assertFalse("$f must only read", Regex("""\.(insert|update|delete|bulkInsert|applyBatch)\(""").containsMatchIn(code(f)))
            assertFalse("$f must not use the network", Regex("""http|URLConnection|java\.net\.URL|okhttp""", RegexOption.IGNORE_CASE).containsMatchIn(code(f)))
            assertFalse("$f must not use API 33 stream helpers", code(f).contains("readNBytes"))
        }
    }

    @Test fun picturesAreReadOnDemand_shrunk_andNeverInTheDemo() {
        val images = text("data/sms/MmsImages.kt")
        assertTrue(images.contains("DemoMode.active"))
        assertTrue(images.contains("inSampleSize"))
        assertTrue(images.contains("MmsMessages.imagePartIds"))
        // Pictures are loaded by the open detail sheet only, off the main thread, never by the list or the scan.
        val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" && "MmsImages.load(" in it.readText() }.map { it.name }
        assertTrue(callers.toList() == listOf("ChatSourceUi.kt"))
        assertTrue(text("presentation/messages/components/ChatSourceUi.kt").contains("Dispatchers.IO"))
        assertTrue(text("presentation/messages/components/MessageDetailSheet.kt").contains("MmsPictures(item.id)"))
        val demo = text("data/demo/DemoSmsRepository.kt")
        assertFalse(demo.contains("Mms"))
    }

    @Test fun theListReadsOnlyTheMmsInbox_andTheScanTouchesNoPart() {
        val source = text("data/sms/MmsDataSource.kt")
        assertTrue(source.contains("content://mms/inbox"))
        val stubs = source.substringAfter("fun stubs(").substringBefore("fun readWindow(")
        assertFalse("the scan must not read parts", stubs.contains("PART") || stubs.contains("addr"))
    }

    @Test fun chatNotificationsAreReadInFull() {
        val listener = text("data/sms/RcsNotificationListener.kt")
        for (extra in listOf("EXTRA_BIG_TEXT", "EXTRA_TEXT_LINES", "EXTRA_MESSAGES", "EXTRA_CONVERSATION_TITLE", "EXTRA_TEXT", "EXTRA_TITLE")) assertTrue(extra, listener.contains(extra))
        assertTrue(listener.contains("RichNotificationText.capture("))
        assertTrue(listener.contains("DemoMode.active"))
    }

    @Test fun theDetailSaysWhatCannotBeRead() {
        val ui = text("presentation/messages/components/ChatSourceUi.kt")
        assertTrue(ui.contains("Image and buttons from the original message are not available here. Open it in Messages."))
        assertTrue(ui.contains("Open in Messages"))
        assertTrue(text("presentation/messages/components/MessageDetailSheet.kt").contains("ChatNote()"))
    }

    @Test fun theLegalTextAndDocsSayWhatIsRead() {
        val legal = LegalText.fullText()
        assertTrue(legal.contains("picture messages (MMS)"))
        assertTrue(legal.contains("never a banner image or a button"))
        val root = listOf(File("."), File("..")).first { File(it, "docs/PRIVACY.md").exists() }
        val privacy = File(root, "docs/PRIVACY.md").readText()
        assertTrue(privacy.contains("picture messages (MMS)") && privacy.contains("banner image or buttons"))
        assertTrue(File(root, "SECURITY.md").readText().contains("MMS"))
        assertTrue(File(root, "docs/ARCHITECTURE.md").readText().contains("## Picture messages (MMS) and rich chat (RCS) messages"))
    }

    @Test fun theBalanceTileHintsAtNotificationAccessOnlyWhileWaiting() {
        val ui = text("presentation/home/BalanceCheckUi.kt")
        assertTrue(ui.contains("RichReplyHint(BalanceRequests.RICH_REPLY_HINT)"))
        assertTrue(ui.contains("ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        assertTrue(ui.substringAfter("fun RichReplyHint(").take(200).contains("DemoMode.active"))
    }
}
