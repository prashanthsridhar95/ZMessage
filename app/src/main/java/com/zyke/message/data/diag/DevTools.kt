package com.zyke.message.data.diag

import com.zyke.message.di.AppContainer
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checks the developer options can run on demand: a snapshot of what the app holds (counts only), and a test that
 * renaming and filing under a category reach the screen. Each writes its findings to the log as well as returning them.
 */
object DevTools {
    private const val WAIT_MS = 4_000L
    private const val TEST_NAME = "ZykeSelfTest"

    /** Counts and fingerprints of what is held, to compare with what the screens show. */
    suspend fun snapshot(container: AppContainer): List<String> {
        val lines = mutableListOf<String>()
        fun say(text: String) { lines += text; DevLog.d("snapshot") { text } }

        val messages = withTimeoutOrNull(WAIT_MS) { container.observeMessages().first() }.orEmpty()
        say("messages in the pipeline: ${messages.size}, renamed: ${messages.count { it.merchant.renamed }}, with a chosen category: ${messages.count { it.categoryOverridden }}")
        say("progress: ${container.observeMessages.progress.value}")

        val nicknames = container.nicknames.nicknames.value
        say("nicknames stored: ${nicknames.size}")
        nicknames.keys.forEach { key ->
            val own = key.startsWith("msg:")
            val matches = messages.count { if (own) CategoryKey.messageKey(it.id) == key else CategoryKey.of(it) == key }
            say("  nickname ${DiagLog.key(key)} ${if (own) "(one transaction)" else "(sender or receiver)"} matches $matches messages")
        }
        val overrides = container.categoryOverrides.overrides.value
        say("category choices stored: ${overrides.size}")
        overrides.forEach { (key, category) ->
            val own = key.startsWith("msg:")
            val matches = messages.count { if (own) CategoryKey.messageKey(it.id) == key else CategoryKey.of(it) == key }
            say("  choice ${DiagLog.key(key)} -> $category ${if (own) "(one transaction)" else "(sender or receiver)"} matches $matches messages")
        }
        say("hand-made entries: ${container.manualEntries.entries.value.size}, left out of totals: ${container.setAsides.state.value.excluded.size}, transfers: ${container.setAsides.state.value.transfers.size}")
        val settings = container.settings.settings.value
        say("settings: paused=${settings.readingPaused} liveAlerts=${settings.liveAlerts} digest=${settings.morningDigest} lock=${settings.appLock} theme=${settings.themeMode}")
        say("log file: ${DevLog.sizeBytes() / 1024} KB")
        return lines
    }

    /** Sets a name and a category, waits for them to show in what the screens read, and puts everything back. */
    suspend fun selfTest(container: AppContainer): List<String> {
        val lines = mutableListOf<String>()
        fun say(text: String) { lines += text; DevLog.d("selftest") { text } }

        val messages = withTimeoutOrNull(WAIT_MS) { container.observeMessages().first() }.orEmpty()
        val sample = messages.firstOrNull { CategoryKey.of(it) != null && !it.isPromotional && it.id > 0 }
        if (sample == null) {
            say("No message to test with yet: wait for messages to load, then try again.")
            return lines
        }
        val key = CategoryKey.of(sample)!!
        val own = CategoryKey.messageKey(sample.id)
        say("Testing with message id=${sample.id}, sender/receiver key ${DiagLog.key(key)}")

        suspend fun shows(check: (ClassifiedMessage) -> Boolean): Long? {
            val start = System.currentTimeMillis()
            val seen = withTimeoutOrNull(WAIT_MS) {
                container.observeMessages().first { list -> list.firstOrNull { it.id == sample.id }?.let(check) == true }
            }
            return if (seen != null) System.currentTimeMillis() - start else null
        }

        // A name for the sender or receiver.
        val oldName = container.nicknames.nicknames.value[key]
        container.nicknames.set(key, TEST_NAME)
        say("rename sender/receiver: " + (shows { it.merchant.name == TEST_NAME && it.merchant.renamed }?.let { "PASS (${it} ms)" } ?: "FAIL: the new name did not reach the messages in ${WAIT_MS} ms"))
        container.nicknames.set(key, oldName)

        // A name for this one transaction.
        val oldOwnName = container.nicknames.nicknames.value[own]
        container.nicknames.set(own, TEST_NAME)
        say("rename one transaction: " + (shows { it.merchant.name == TEST_NAME && it.nicknameOwn }?.let { "PASS (${it} ms)" } ?: "FAIL: the new name did not reach the message in ${WAIT_MS} ms"))
        container.nicknames.set(own, oldOwnName)

        // A category for the sender or receiver, then for this one transaction.
        val target = if (sample.category == MessageCategory.FOOD) MessageCategory.SHOPPING else MessageCategory.FOOD
        val oldCategory = container.categoryOverrides.overrides.value[key]
        container.categoryOverrides.set(key, target)
        say("category for sender/receiver: " + (shows { it.category == target }?.let { "PASS (${it} ms)" } ?: "FAIL: the category did not reach the messages in ${WAIT_MS} ms"))
        container.categoryOverrides.set(key, oldCategory)

        val oldOwnCategory = container.categoryOverrides.overrides.value[own]
        container.categoryOverrides.set(own, target)
        say("category for one transaction: " + (shows { it.category == target && it.categoryOwn }?.let { "PASS (${it} ms)" } ?: "FAIL: the category did not reach the message in ${WAIT_MS} ms"))
        container.categoryOverrides.set(own, oldOwnCategory)

        say("Everything was put back as it was.")
        return lines
    }
}
