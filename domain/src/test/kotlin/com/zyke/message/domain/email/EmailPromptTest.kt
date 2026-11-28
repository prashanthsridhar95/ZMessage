package com.zyke.message.domain.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailPromptTest {
    private val fresh = EmailAlertSettings()
    private fun kind(s: EmailAlertSettings = fresh, demo: Boolean = false, isFresh: Boolean = true, blocked: Boolean = false) = EmailPrompt.kind(s, demo, isFresh, blocked)

    @Test
    fun aFreshInstallGetsTheOnboarding_andAnUpdateGetsTheNewPage() {
        assertEquals(EmailPromptKind.ONBOARDING, kind())
        assertEquals(EmailPromptKind.UPDATE, kind(isFresh = false))
        assertNotEquals(EmailPrompt.headline(EmailPromptKind.ONBOARDING), EmailPrompt.headline(EmailPromptKind.UPDATE))
        assertEquals("Catch payments that arrive by email", EmailPrompt.headline(EmailPromptKind.ONBOARDING))
        assertEquals("New: email alerts", EmailPrompt.headline(EmailPromptKind.UPDATE))
    }

    @Test
    fun neverInTheDemo_neverWhileBlocked_neverTwice() {
        assertEquals(EmailPromptKind.NONE, kind(demo = true))
        assertEquals(EmailPromptKind.NONE, kind(blocked = true))
        assertEquals(EmailPromptKind.NONE, kind(fresh.copy(emailPrompted = true)))
        assertEquals(EmailPromptKind.NONE, kind(fresh.copy(emailPrompted = true), isFresh = false))
    }

    @Test
    fun anyoneAlreadyConnectedOrOnCountsAsAnswered() {
        assertTrue(EmailPrompt.answered(fresh.copy(connected = true)))
        assertTrue(EmailPrompt.answered(fresh.copy(enabled = true)))
        assertFalse(EmailPrompt.answered(fresh.copy(explained = true, hdfcNoticeAnswered = true)))
        assertEquals(EmailPromptKind.NONE, kind(fresh.copy(connected = true), isFresh = false))
    }

    @Test
    fun threePlainPoints_andEqualButtonsWords() {
        assertEquals(3, EmailPrompt.POINTS.size)
        assertEquals("Set up", EmailPrompt.SET_UP)
        assertEquals("Not now", EmailPrompt.NOT_NOW)
        assertTrue(EmailPrompt.PROMISE.contains("Rs 500"))
    }
}
