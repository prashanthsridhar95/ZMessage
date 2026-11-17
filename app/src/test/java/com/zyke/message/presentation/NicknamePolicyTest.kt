package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A nickname is the name the person sees, so every lookup that starts from a name starts from it: the picture search, the brand
 * logo, and the sheets' titles. The name in the message is only the second try. A nickname shows at once, like a category.
 */
class NicknamePolicyTest {
    private val root = File("src/main/java/com/zyke/message")
    private fun read(path: String) = File(root, path).readText()
    private val detail = read("presentation/messages/components/MessageDetailSheet.kt")
    private val picker = read("presentation/common/PicturePicker.kt")
    private val search = read("presentation/common/ImageSearchSheet.kt")
    private val store = read("data/images/ImageStore.kt")

    @Test
    fun theDetailSheetsPictureSearchStartsFromTheNameShown() {
        assertFalse("the picker's label must not be the raw payee / sender text", Regex("rememberPicturePicker\\([^)]*CategoryKey\\.label").containsMatchIn(detail))
        val calls = Regex("rememberPicturePicker\\(([^\\n]*)").findAll(detail).map { it.groupValues[1] }.toList()
        assertEquals("one picker for the payee, one for this payment", 2, calls.size)
        assertTrue(calls.all { "Search.primary" in it && "Search.alternative" in it })
        assertTrue("PictureSearchQuery.forPayee(" in detail && "PictureSearchQuery.of(item)" in detail)
    }

    @Test
    fun everyPickerLabelIsAnArgumentNotAHardCodedHeader() {
        val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in all) {
            val text = file.readText()
            for (call in Regex("rememberPicturePicker\\(([^\\n]*)").findAll(text).map { it.groupValues[1] }) {
                assertFalse("${file.name}: the picker must not be given the sender's header", "message.sender" in call || "SenderKey" in call)
            }
        }
    }

    @Test
    fun theSearchPageLetsThePersonEditTheNameAndOffersTheOriginalOne() {
        assertTrue("alternative: String? = null" in picker && "alternative = alternative" in picker)
        assertTrue("alternative: String? = null" in search)
        assertTrue("a field prefilled with the name shown", "SearchField(typed" in search && "mutableStateOf(query)" in search)
        assertTrue("the second search is offered by name", "instead" in search && "search(alternative)" in search)
        assertTrue("only a cleaned name is sent", "PictureSearchQuery.clean(" in search)
        assertFalse("the page address is not built from the raw query", Regex("searchAddress\\(query\\)").containsMatchIn(search))
    }

    @Test
    fun theSheetTitlesAndLogoRowsUseTheNameShown() {
        assertFalse("category sheet title", "\"Category for \${CategoryKey.label(item)}\"" in detail)
        assertTrue("logo turned off / on covers both names", "store.hideLogo(name, original)" in detail && "store.showLogo(name, original)" in detail)
        assertFalse("the logo rows must not look up the nickname alone", "store.hasLogo(name)" in detail)
        assertTrue("hiding a logo hides every name it could be found by", "BrandLogo.withLogo(" in store && "fun hideLogo(name: String, original: String? = null)" in store)
        assertTrue("the logo is looked up through the one resolution order", "BrandLogo.resolve(BrandLogo.names(name, original)" in store)
    }

    @Test
    fun everyChipThatLooksUpALogoByTheMerchantNameAlsoPassesTheOriginalName() {
        val files = File(root, "presentation").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in files) {
            val text = file.readText()
            var from = 0
            while (true) {
                val start = text.indexOf("MerchantChip(", from)
                if (start < 0) break
                from = start + 1
                val head = text.substring(start, minOf(text.length, start + 420))
                if (Regex("[ (,]name = [A-Za-z.]*merchant\\.name").containsMatchIn(head)) {
                    assertTrue("${file.name}: a chip named by merchant.name must pass merchant.originalName", "originalName = " in head)
                }
            }
        }
    }

    @Test
    fun aNicknameShowsAtOnceOnBothScreensAndTheStageIsShared() {
        for (path in listOf("presentation/messages/MessagesViewModel.kt", "presentation/home/HomeViewModel.kt")) {
            val source = read(path)
            assertTrue("$path must hold PendingNicknames", "PendingNicknames()" in source)
            assertTrue("$path must show a changed nickname in the same reduction", "pendingNames.changed()" in source && "pendingNames.overlay(messages, names)" in source)
            assertTrue("$path must bring a list from the pipeline up to the nicknames", "pendingNames.overlay(actions.pending.settle(inbox)" in source)
        }
        val pipeline = File("../domain/src/main/kotlin/com/zyke/message/domain/usecase/ObserveMessagesUseCase.kt")
        if (pipeline.exists()) assertTrue("the pipeline uses the same stage the screens reapply", "NicknameStage.apply(" in pipeline.readText())
    }

    @Test
    fun theSettingsRulesCanGiveAndTakeAwayAName() {
        val rules = read("presentation/settings/RulesUi.kt")
        assertTrue("a name is optional in the edit sheet", "primaryEnabled = true" in rules)
        assertFalse("the Name field is no longer only for rules that already have one", "if (rule.nickname != null) FormField(\"Name\"" in rules)
        assertTrue("a forgotten single payment loses its own category too", Regex("singles[\\s\\S]{0,900}customCategories\\.assign\\(rule\\.key, null\\)").containsMatchIn(rules))
    }
}
