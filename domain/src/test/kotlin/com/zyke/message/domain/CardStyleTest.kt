package com.zyke.message.domain

import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.cards.CardThemes
import com.zyke.message.domain.cards.UserCardCodec
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.UserCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CardStyleTest {
    private fun lum(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    @Test
    fun everyThemeStopHasReadableWhiteText() {
        CardThemes.all.forEach { t ->
            t.colors.forEach { c -> assertTrue("${t.id} ${c.toString(16)}", 1.05 / (lum(c) + 0.05) >= 4.5) }
        }
        assertEquals(CardThemes.all.size, CardThemes.all.map { it.id }.toSet().size)
    }

    @Test
    fun oldStoredCardsLoadWithDefaults() {
        val old = """[{"id":"a","type":"CREDIT","last4":"1234","name":"HDFC Regalia","bankName":"HDFC Bank","statementDay":15},
            {"id":"b","type":"ACCOUNT","last4":"9999","name":"","bankName":"SBI","statementDay":null}]"""
        val cards = UserCardCodec.decode(old)
        assertEquals(2, cards.size)
        val a = cards[0]
        assertEquals(15, a.statementDay)
        assertNull(a.network); assertEquals("", a.variant); assertEquals("", a.themeId); assertNull(a.dueDay); assertNull(a.creditLimit)
        assertEquals("ocean", a.theme.id)
        assertNull(cards[1].statementDay)
    }

    @Test
    fun roundTripKeepsEverything() {
        val card = UserCard("x", CardType.FOREX, "0042", "My \"forex\"\nline", 7, "Axis Bank", CardNetwork.MASTERCARD, "Magnus", "violet", 25, 500000L)
        assertEquals(listOf(card), UserCardCodec.decode(UserCardCodec.encode(listOf(card))))
        assertEquals(emptyList<UserCard>(), UserCardCodec.decode("not json"))
        assertEquals(emptyList<UserCard>(), UserCardCodec.decode(null))
    }

    @Test
    fun unknownNetworkOrTypeIsTolerated() {
        val cards = UserCardCodec.decode("""[{"id":"a","type":"CREDIT","last4":"1","network":"ZZZ"},{"id":"b","type":"WHAT","last4":"2"}]""")
        assertEquals(1, cards.size)
        assertNull(cards[0].network)
    }

    @Test
    fun suggestionsAndAutoTheme() {
        assertTrue("Regalia" in CardCatalog.variantSuggestions("HDFC Bank"))
        assertTrue("Amazon Pay ICICI" in CardCatalog.variantSuggestions("icici"))
        assertEquals(listOf("Flipkart Axis"), CardCatalog.variantSuggestions("Axis Bank", "flip"))
        assertTrue("SimplyCLICK" in CardCatalog.variantSuggestions("State Bank of India", "click"))
        assertTrue(CardCatalog.variantSuggestions("Unknown Bank").isNotEmpty())
        assertEquals(BankName.HDFC, CardCatalog.bank("HDFC Bank"))
        assertEquals(BankName.SBI, CardCatalog.bank("State Bank of India"))
        assertEquals(CardThemes.auto("Some Co-op Bank"), CardThemes.auto("Some Co-op Bank"))
        assertEquals("violet", UserCard("i", CardType.CREDIT, "1", "", themeId = "violet", bankName = "HDFC").theme.id)
        assertEquals(CardNetwork.AMEX, CardCatalog.guessNetwork("American Express", ""))
    }

    @Test
    fun spokenDescription() {
        val c = UserCard("i", CardType.CREDIT, "1234", "", bankName = "HDFC", network = CardNetwork.VISA, variant = "Regalia")
        assertEquals("HDFC credit card ending 1234, Visa, Regalia", c.spokenDescription)
        assertEquals("ending 5678", UserCard("i", CardType.CREDIT, "5678", "x").spokenDescription.replace("credit card ", ""))
    }
}
