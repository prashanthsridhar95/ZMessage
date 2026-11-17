package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A category the person picks shows up in the same frame: the screens apply it on the messages they already hold before the
 * choice goes through the pipeline, the pipeline gives a change the person made no waiting time, and no delay sits in the way.
 */
class CategoryInstantPolicyTest {
    private fun root(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message") }.first { it.isDirectory }

    private fun read(path: String) = File(root(), path).readText()

    /** The text of the `is Intent.<name> ->` branch, up to the next `is Intent.` branch. */
    private fun branch(source: String, name: String): String {
        val start = source.indexOf("is Intent.$name ->")
        assertTrue("no branch for $name", start >= 0)
        val next = source.indexOf("is Intent.", start + 10).let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    private val screens = mapOf("presentation/messages/MessagesViewModel.kt" to "Messages", "presentation/home/HomeViewModel.kt" to "Home")

    @Test
    fun pickingACategoryAppliesItOnTheStateBeforeTheRepositoryWrite() {
        val source = read("presentation/common/MessageActions.kt")
        for (function in listOf("setCategory", "setMessageCategory")) {
            val start = source.indexOf("fun $function(")
            assertTrue("no $function", start >= 0)
            val next = source.indexOf("\n    fun ", start + 5).let { if (it < 0) source.length else it }
            val text = source.substring(start, next)
            val shown = text.indexOf("showCategoryNow(")
            val written = text.indexOf("categoryOverrides.set(")
            assertTrue("$function must show the choice at once", shown >= 0)
            assertTrue("$function must still save the choice", written >= 0)
            assertTrue("$function must show the choice before it is written", shown < written)
        }
        // The overlay is applied on the state before the (queued) write is even built.
        val show = source.substring(source.indexOf("private fun showCategoryNow("))
        assertTrue(show.indexOf("pending.add(") in 0 until show.indexOf("showOverlay("))
    }

    @Test
    fun bothScreensFileCategoriesThroughTheSharedActions() {
        for ((path, name) in screens) {
            val source = read(path)
            assertTrue("$name must use MessageActions", source.contains("MessageActions("))
            for (intent in listOf("SetCategory", "SetMessageCategory")) {
                assertTrue("$name $intent must delegate", branch(source, intent).contains("actions.${intent.replaceFirstChar { it.lowercase() }}("))
            }
            assertFalse("$name must not write overrides itself", source.contains("categoryOverrides.set("))
        }
    }

    @Test
    fun theInboxThatComesBackIsSettledAgainstThePendingChoices() {
        for ((path, name) in screens) {
            assertTrue("$name must settle each inbox against pending choices", read(path).contains("pending.settle(inbox)"))
        }
    }

    @Test
    fun theMessagesRowsAreNotLeftStaleByAFeedBuiltBeforeTheChoice() {
        val source = read("presentation/messages/MessagesViewModel.kt")
        assertTrue(source.contains("FeedPatch.sync("))
        assertTrue(source.contains("FeedPatch.changed("))
    }

    @Test
    fun noDelayIsPutOnTheCategoryPath() {
        val files = listOf(
            "presentation/messages/MessagesViewModel.kt", "presentation/home/HomeViewModel.kt",
            "presentation/dashboard/DashboardViewModel.kt", "data/repository/CategoryOverrideRepositoryImpl.kt"
        )
        for (path in files) {
            val code = read(path).lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
            assertFalse("$path must not debounce or sample", Regex("""\.(debounce|sample)\(""").containsMatchIn(code))
        }
        // The only wait in the use case is the gap between bulk re-reads of a big inbox, and a change the person made skips it.
        val useCase = listOf("../domain/src/main/kotlin", "domain/src/main/kotlin")
            .map { File(it, "com/zyke/message/domain/usecase/ObserveMessagesUseCase.kt") }.firstOrNull { it.isFile }
        if (useCase != null) assertTrue(useCase.readText().contains("urgent.getAndSet(false)"))
    }

    @Test
    fun theChoiceIsPublishedBeforeItIsWrittenToDisk() {
        val source = read("data/repository/CategoryOverrideRepositoryImpl.kt")
        val set = source.substring(source.indexOf("override fun set("), source.indexOf("override fun setAll("))
        assertTrue("set must publish the state before the preferences write", set.indexOf("_overrides.value =") in 0 until set.indexOf("editor.apply()"))
        val all = source.substring(source.indexOf("override fun setAll("), source.indexOf("override fun clearAll("))
        assertTrue("setAll must publish the state before the preferences write", all.indexOf("_overrides.value =") in 0 until all.indexOf("editor.apply()"))
        assertEquals("the write stays asynchronous", 0, Regex("""editor\.commit\(\)|\.commit\(\)""").findAll(source).count())
    }
}
