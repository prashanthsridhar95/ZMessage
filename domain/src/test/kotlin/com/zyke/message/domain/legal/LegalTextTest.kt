package com.zyke.message.domain.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextTest {
    private val text = LegalText.fullText()

    @Test fun theRequiredSectionsAreThereInOrder() {
        assertEquals(
            listOf("on-phone", "leaves", "permissions", "control", "terms", "age", "refunds", "contact"),
            LegalText.sections.map { it.id }
        )
        LegalText.sections.forEach { assertTrue("${it.id} has no title", it.title.isNotBlank() && it.intro.isNotBlank()) }
    }

    @Test fun everyPermissionAndEveryHostIsExplained() {
        LegalText.permissions.forEach { assertTrue("$it is not explained", it in text) }
        LegalText.networkHosts.forEach { assertTrue("$it is not named", it in text) }
    }

    @Test fun theTermsCoverWhatTheyMust() {
        val terms = LegalText.sections.first { it.id == "terms" }.let { s -> s.items.joinToString(" ") { (it.heading ?: "") + " " + it.body } }.lowercase()
        listOf("as is", "financial", "tax", "wrong", "own risk", "carrier charges", "trust", "third-party", "liab", "change").forEach {
            assertTrue("terms miss '$it'", it in terms)
        }
    }

    @Test fun ageAndRefundsAreStated() {
        assertTrue("for people 18 and over" in text)
        assertTrue("no data about children is collected on purpose" in text.lowercase())
        assertTrue("Zyke is free and has no purchases; there is nothing to refund." in text)
    }

    @Test fun theOperatorAndDateAreShownFromOnePlace() {
        assertTrue(LegalInfo.OPERATOR_NAME in text)
        assertTrue(LegalInfo.CONTACT_EMAIL in text)
        assertTrue(LegalInfo.LAST_UPDATED in text)
        assertTrue("Version ${LegalInfo.TEXT_VERSION}" in text)
        assertFalse("an empty address must not leave a heading", "Address" in text && LegalInfo.OPERATOR_ADDRESS.isBlank())
    }

    @Test fun noMessageContentIsEverSaidToBeSent() {
        // The page promises this; guard the wording against a careless edit that drops it.
        assertTrue("never sends message text" in text || "Message text is never sent" in text)
    }
}
