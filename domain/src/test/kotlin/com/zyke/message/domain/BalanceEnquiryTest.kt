package com.zyke.message.domain

import com.zyke.message.domain.balance.BalanceCheckRepository
import com.zyke.message.domain.balance.BalanceEnquiry
import com.zyke.message.domain.balance.BalanceRequest
import com.zyke.message.domain.balance.BalanceRequests
import com.zyke.message.domain.balance.EnquiryEdit
import com.zyke.message.domain.balance.EnquiryPlan
import com.zyke.message.domain.balance.EnquirySetup
import com.zyke.message.domain.balance.InMemoryBalanceCheck
import com.zyke.message.domain.balance.RequestPhase
import com.zyke.message.domain.balance.RequestStatus
import com.zyke.message.domain.balance.Uan
import com.zyke.message.domain.model.BankName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceEnquiryTest {
    private val none = EnquirySetup()

    private fun ready(plan: EnquiryPlan) = plan as EnquiryPlan.Ready

    @Test fun hdfcSendsTheBankNumberAndTheWordBalance() {
        val plan = ready(BalanceEnquiry.planForAccount(BankName.HDFC, "HDFC", "1234", none))
        assertEquals("7308080808", plan.number)
        assertEquals("Balance", plan.text)
    }

    @Test fun federalNeedsTheLastFourDigitsAndPutsThemInTheText() {
        val plan = ready(BalanceEnquiry.planForAccount(BankName.FEDERAL_BANK, "Federal Bank", "5678", none))
        assertEquals("9895088888", plan.number)
        assertEquals("BAL SB5678", plan.text)
        assertTrue(BalanceEnquiry.planForAccount(BankName.FEDERAL_BANK, "Federal Bank", "", none) is EnquiryPlan.NeedsLast4)
        assertTrue(BalanceEnquiry.planForAccount(BankName.FEDERAL_BANK, "Federal Bank", "56x8", none) is EnquiryPlan.NeedsLast4)
    }

    @Test fun indianBankIsNeverSentBecauseItNeedsAnMpin() {
        val plan = BalanceEnquiry.planForAccount(BankName.INDIAN_BANK, "Indian Bank", "1234", none)
        assertEquals(BalanceEnquiry.MPIN_REASON, (plan as EnquiryPlan.Blocked).reason)
        assertEquals("Needs your MPIN, so Zyke can't send this for you", plan.reason)
    }

    @Test fun anUnknownBankIsUnsupportedUntilSetUp() {
        val plan = BalanceEnquiry.planForAccount(BankName.OTHERS, "Acme Bank", "1234", none)
        assertEquals("Acme Bank", (plan as EnquiryPlan.Unsupported).bankLabel)
        val key = BalanceEnquiry.bankKey(BankName.OTHERS, "Acme Bank")
        assertEquals("other:acme bank", key)
        val setup = EnquirySetup(mapOf(key to EnquiryEdit("5676700", "BAL {last4}", "Acme Bank")))
        val custom = ready(BalanceEnquiry.planForAccount(BankName.OTHERS, "ACME BANK ", "9876", setup))
        assertEquals("5676700", custom.number)
        assertEquals("BAL 9876", custom.text)
    }

    @Test fun thePersonsChangeReplacesThePresetForThatBankOnly() {
        val setup = EnquirySetup(mapOf("HDFC" to EnquiryEdit("7308080808", "BAL")))
        assertEquals("BAL", ready(BalanceEnquiry.planForAccount(BankName.HDFC, "HDFC", "1234", setup)).text)
        assertEquals("BAL SB1234", ready(BalanceEnquiry.planForAccount(BankName.FEDERAL_BANK, "Federal", "1234", setup)).text)
    }

    @Test fun anIndianBankChangeIsAllowedOnlyWithoutASecret() {
        assertNotNull(BalanceEnquiry.templateProblem("BAL 1234"))
        assertNotNull(BalanceEnquiry.templateProblem("BAL 123456"))
        assertNotNull(BalanceEnquiry.templateProblem("BAL MPIN"))
        assertNotNull(BalanceEnquiry.templateProblem("bal pin {last4}"))
        assertNull(BalanceEnquiry.templateProblem("IND BAL"))
        val setup = EnquirySetup(mapOf("INDIAN_BANK" to EnquiryEdit("9999999999", "IND BAL")))
        assertTrue(BalanceEnquiry.planForAccount(BankName.INDIAN_BANK, "Indian Bank", "1234", setup) is EnquiryPlan.Ready)
    }

    @Test fun templatesAreChecked() {
        assertNull(BalanceEnquiry.templateProblem("BAL SB{last4}"))
        assertNull(BalanceEnquiry.templateProblem("EPFOHO {uan} ENG"))
        assertNotNull(BalanceEnquiry.templateProblem(""))
        assertNotNull(BalanceEnquiry.templateProblem("BAL {mpin}"))
        assertNotNull(BalanceEnquiry.templateProblem("BAL; DROP"))
        assertNotNull(BalanceEnquiry.templateProblem("x".repeat(61)))
        assertNotNull(BalanceEnquiry.templateProblem("my password is x"))
        assertNotNull(BalanceEnquiry.templateProblem("send OTP"))
    }

    @Test fun numbersAreChecked() {
        assertEquals("7308080808", BalanceEnquiry.normalizeNumber(" 73080 80808 "))
        assertEquals("+917308080808", BalanceEnquiry.normalizeNumber("+91 73080-80808"))
        assertEquals("5676762", BalanceEnquiry.normalizeNumber("5676762"))
        assertNull(BalanceEnquiry.normalizeNumber("12ab"))
        assertNull(BalanceEnquiry.normalizeNumber("1234"))
        assertNull(BalanceEnquiry.normalizeNumber("12345678901234"))
        assertNull(BalanceEnquiry.normalizeNumber(""))
    }

    @Test fun anInvalidSavedNumberOrTextIsNeverReady() {
        val badNumber = EnquirySetup(mapOf("HDFC" to EnquiryEdit("abc", "Balance")))
        assertTrue(BalanceEnquiry.planForAccount(BankName.HDFC, "HDFC", "1234", badNumber) is EnquiryPlan.Unsupported)
        val badText = EnquirySetup(mapOf("HDFC" to EnquiryEdit("7308080808", "BAL 4321")))
        assertTrue(BalanceEnquiry.planForAccount(BankName.HDFC, "HDFC", "1234", badText) is EnquiryPlan.Unsupported)
    }

    @Test fun epfoNeedsTheTwelveDigitUanInTheText() {
        assertTrue(BalanceEnquiry.planForProvidentFund(none, "") is EnquiryPlan.NeedsUan)
        assertTrue(BalanceEnquiry.planForProvidentFund(none, "12345") is EnquiryPlan.NeedsUan)
        val plan = ready(BalanceEnquiry.planForProvidentFund(none, "100123456789"))
        assertEquals("7738299899", plan.number)
        assertEquals("EPFOHO 100123456789 ENG", plan.text)
    }

    @Test fun uanIsCleanedAndMasked() {
        assertEquals("100123456789", Uan.clean("1001 2345-6789 99"))
        assertTrue(Uan.isValid("100123456789"))
        assertFalse(Uan.isValid("10012345678"))
        assertEquals("••••••••6789", Uan.mask("100123456789"))
        assertFalse(Uan.mask("100123456789").contains("1001"))
    }

    @Test fun everyPresetIsMarkedForTheUserToConfirm() {
        // No preset is a verified fact: the notes that say so are part of every confirm.
        assertTrue(BalanceEnquiry.NOTE_UNVERIFIED.contains("published list"))
        assertTrue(BalanceEnquiry.NOTE_RATES.contains("SMS rates"))
        for (preset in BalanceEnquiry.presets) {
            if (preset.blockedReason == null) {
                assertNotNull(BalanceEnquiry.normalizeNumber(preset.number))
                assertNull(preset.key, BalanceEnquiry.templateProblem(preset.template))
            }
        }
        assertEquals(setOf("HDFC", "FEDERAL_BANK", "INDIAN_BANK", "EPFO"), BalanceEnquiry.presets.map { it.key }.toSet())
    }

    // ---- requests
    private val t0 = 1_700_000_000_000L

    @Test fun onlyOneRequestPerAccountInTwoMinutes() {
        val sent = BalanceRequest("a", t0, RequestStatus.SENT)
        assertEquals(0L, BalanceRequests.waitLeft(null, t0))
        assertEquals(120_000L, BalanceRequests.waitLeft(sent, t0))
        assertEquals(30_000L, BalanceRequests.waitLeft(sent, t0 + 90_000))
        assertEquals(0L, BalanceRequests.waitLeft(sent, t0 + 120_000))
        assertEquals("a failed request does not block a retry", 0L, BalanceRequests.waitLeft(sent.copy(status = RequestStatus.FAILED), t0 + 1))
    }

    @Test fun theTileWaitsThenSaysNoReplyThenClears() {
        val sent = BalanceRequest("a", t0, RequestStatus.SENT)
        assertEquals(RequestPhase.IDLE, BalanceRequests.phase(null, 0, t0))
        assertEquals(RequestPhase.WAITING, BalanceRequests.phase(sent, 0, t0 + 1_000))
        assertEquals(RequestPhase.WAITING, BalanceRequests.phase(sent, t0 - 1, t0 + 299_000))
        assertEquals(RequestPhase.NO_REPLY, BalanceRequests.phase(sent, 0, t0 + 300_000))
        assertEquals(RequestPhase.IDLE, BalanceRequests.phase(sent, 0, t0 + 31 * 60_000))
        assertEquals("Request sent · waiting for the reply", BalanceRequests.statusLine(RequestPhase.WAITING))
        assertEquals("No reply yet", BalanceRequests.statusLine(RequestPhase.NO_REPLY))
        assertNull(BalanceRequests.statusLine(RequestPhase.IDLE))
    }

    @Test fun aNewerBalanceAnswersTheRequestWhateverNumberItCameFrom() {
        val sent = BalanceRequest("a", t0, RequestStatus.SENT)
        assertEquals(RequestPhase.IDLE, BalanceRequests.phase(sent, t0 + 5_000, t0 + 6_000))
        assertEquals(RequestPhase.WAITING, BalanceRequests.phase(sent, t0 - 5_000, t0 + 6_000))
    }

    @Test fun aFailureShowsAsFailedAndStaysFailed() {
        val failed = BalanceRequest("a", t0, RequestStatus.FAILED)
        assertEquals(RequestPhase.FAILED, BalanceRequests.phase(failed, 0, t0 + 1))
        assertEquals(RequestStatus.FAILED, BalanceRequests.advance(RequestStatus.FAILED, RequestStatus.SENT))
        assertEquals(RequestStatus.SENT, BalanceRequests.advance(RequestStatus.SENDING, RequestStatus.SENT))
        assertEquals(RequestStatus.SENT, BalanceRequests.advance(RequestStatus.SENT, RequestStatus.SENDING))
    }

    @Test fun inMemoryStoreKeepsEditsUanAndRequests() {
        val store: BalanceCheckRepository = InMemoryBalanceCheck()
        store.saveEdit("HDFC", EnquiryEdit("7308080808", "BAL"))
        assertEquals("BAL", store.setup.value.edits["HDFC"]!!.template)
        store.clearEdit("HDFC")
        assertTrue(store.setup.value.edits.isEmpty())
        store.saveUan("1001 2345 6789")
        assertEquals("100123456789", store.uan.value)
        store.record(BalanceRequest("a", t0, RequestStatus.SENDING))
        store.updateStatus("a", t0, RequestStatus.SENT)
        assertEquals(RequestStatus.SENT, store.requests.value["a"]!!.status)
        store.updateStatus("a", t0 + 1, RequestStatus.FAILED)
        assertEquals("a report for an older request is ignored", RequestStatus.SENT, store.requests.value["a"]!!.status)
    }
}
