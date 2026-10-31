package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.LearnedTemplateRepository
import com.zyke.message.domain.teach.LearnedTemplate
import com.zyke.message.domain.teach.LearnedTemplates
import com.zyke.message.domain.teach.TeachPicks
import com.zyke.message.domain.teach.TemplateLearner
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachTemplateTest {
    private fun <T> nn(x: T?): T { assertNotNull(x); return x!! }

    private fun sms(body: String, sender: String = "AD-ZEBRAB-S", id: Long = 1) = SmsMessage(id, sender, body, 1_700_000_000_000L + id, true)

    private fun idx(body: String, text: String, nth: Int = 0) =
        TemplateLearner.tokenize(body).indexOfFirst { it.text == text }.let { first ->
            if (nth == 0) first else TemplateLearner.tokenize(body).withIndex().filter { it.value.text == text }[nth].index
        }

    private fun learn(body: String, amount: String, payee: String? = null, account: String? = null, received: Boolean = false, sender: String = "AD-ZEBRAB-S"): LearnedTemplate? {
        val tokens = TemplateLearner.tokenize(body)
        val a = tokens.indexOfFirst { it.text == amount }
        val p = payee?.let { text ->
            val words = TemplateLearner.tokenize(text).map { it.text }
            val start = tokens.indices.first { i -> words.indices.all { tokens.getOrNull(i + it)?.text == words[it] } }
            start..start + words.size - 1
        }
        val c = account?.let { tokens.indexOfFirst { t -> t.text == it } }
        return TemplateLearner.learn(sms(body, sender), TeachPicks(a, p, c, received))
    }

    @Test fun roundTripRupeesWithPayeeAndAccount() {
        val body = "Zebra Bank: Rs.1,250.00 spent at ACME STORES on A/c XX1234. Avl bal Rs.9,800.50"
        val t = nn(learn(body, "1,250.00", "ACME STORES", "1234"))
        val tx = nn(t.apply(sms(body)))
        assertEquals("1,250.00", tx.amount)
        assertEquals("ACME STORES", tx.payee)
        assertEquals("1234", tx.accountNumber)
        // A different amount, payee, account and balance in the same wording.
        val other = sms("Zebra Bank: Rs.75.50 spent at BOOK HUB LTD on A/c XX9876. Avl bal Rs.100.00", id = 2)
        val read = nn(t.apply(other))
        assertEquals("75.50", read.amount); assertEquals("BOOK HUB LTD", read.payee); assertEquals("9876", read.accountNumber)
    }

    @Test fun inrAmountWithoutPayee() {
        val body = "Your a/c 5566 is debited by INR 450 towards ZEBRA CARD"
        val t = nn(learn(body, "450", account = "5566"))
        assertEquals("450", t.apply(sms(body))?.amount)
        assertEquals("", t.apply(sms(body))?.payee)
    }

    @Test fun incomingDirection() {
        val body = "Zebra: Rs 2,000 credited to A/c 4321 from RAVI KUMAR via UPI"
        val t = nn(learn(body, "2,000", "RAVI KUMAR", "4321", received = true))
        assertTrue(t.isReceived)
        val c = MessageClassifier().fromLearned(sms(body), nn(t.apply(sms(body))))
        val tx = c.content as MessageContent.Transaction
        assertTrue(tx.isReceived); assertEquals("2000", tx.amount); assertEquals("4321", tx.accountNumber)
    }

    @Test fun nonMatchingStaysUnread() {
        val t = nn(learn("Zebra Bank: Rs.10 spent at SHOP on A/c 1111", "10", "SHOP", "1111"))
        assertNull(t.apply(sms("Zebra Bank: your statement is ready, pay by 5th")))
        assertNull(t.apply(sms("Zebra Bank: Rs.10 spent at SHOP on A/c 1111 and more words")))
        assertNull(t.apply(sms("Zebra Bank: Rs.0 spent at SHOP on A/c 1111")))
    }

    @Test fun otherSendersDoNotMatch() {
        val body = "Zebra Bank: Rs.10 spent at SHOP on A/c 1111"
        val t = nn(learn(body, "10", "SHOP", "1111"))
        nn(t.apply(sms(body, "VM-ZEBRAB-T")))
        assertNull(t.apply(sms(body, "AD-OTHERB-S")))
    }

    @Test fun specialCharactersAreEscaped() {
        val body = "Zebra (Pay+) *alert* Rs.55.00 debited. Ref: 12.3 [ok]"
        val t = nn(learn(body, "55.00"))
        nn(t.apply(sms(body)))
        assertNull(t.apply(sms("Zebra (Pay+) *alert* Rs.55.00 debitedX Ref: 12.3 [ok]")))
        assertNull(t.apply(sms("Zebra Pay+ alert Rs.55.00 debited. Ref: 12.3 [ok]")))
    }

    @Test fun refusesWhenNothingStableOrNotANumber() {
        assertNull(learn("Rs.100", "100"))
        val body = "Zebra spent Rs.100 at SHOP"
        assertNull(TemplateLearner.learn(sms(body), TeachPicks(amount = idx(body, "SHOP"))))
        assertNull(TemplateLearner.learn(sms(body), TeachPicks(amount = 99)))
    }

    private class Repo(list: List<LearnedTemplate>) : LearnedTemplateRepository {
        override val templates: StateFlow<List<LearnedTemplate>> = MutableStateFlow(list)
        override fun add(template: LearnedTemplate, index: Int) = Unit
        override fun remove(id: String) = Unit
    }

    @Test fun firstTaughtWinsAndPipelineReplacesGeneric() = runBlocking {
        val body = "Zebra notice: 300 rupees gone to CAFE from wallet 2222 today"
        val first = nn(learn(body, "300", "CAFE", "2222"))
        val second = first.copy(id = "second", groups = first.groups.copy(payee = null))
        assertEquals("CAFE", LearnedTemplates.firstMatch(listOf(first, second), sms(body))?.second?.payee)
        assertEquals("", LearnedTemplates.firstMatch(listOf(second, first), sms(body))?.second?.payee)

        val inbox = listOf(sms(body, id = 1), sms("Zebra notice: 40 rupees gone to TEA from wallet 2222 today", id = 2), sms("Hello there", id = 3))
        val sms = object : com.zyke.message.domain.repository.SmsRepository {
            override fun observeInbox() = kotlinx.coroutines.flow.flowOf(inbox)
        }
        val result = ObserveMessagesUseCase(sms, MessageClassifier(), Dispatchers.Unconfined, learnedTemplates = Repo(listOf(first))).invoke().first()
        assertTrue(MessageClassifier().classify(inbox[0]).content is MessageContent.Generic)
        assertEquals(2, result.count { it.content is MessageContent.Transaction })
        assertEquals(first.id, result.first { it.id == 2L }.learnedTemplateId)
        assertTrue(result.first { it.id == 3L }.content is MessageContent.Generic)
    }
}
